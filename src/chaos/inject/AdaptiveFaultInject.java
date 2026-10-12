package chaos.inject;

import chaos.core.BaseFaultInject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Shared lifecycle for the seven adaptive white-box cases; no database access until execute. */
abstract class AdaptiveFaultInject extends BaseFaultInject {
    static { DriverManager.setLoginTimeout(10); } // bound connection attempts so a wedged probe cannot hang teardown

    protected final AtomicLong ok = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    protected volatile boolean running;
    private final Set<String> messages = new HashSet<>();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Set<Statement> statements = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<String> activeSql = new ThreadLocal<>();
    private final List<Thread> workers = Collections.synchronizedList(new ArrayList<>());
    // Closures registered through worker(); the controller re-spawns copies to escalate pressure.
    private final List<Work> workPool = new CopyOnWriteArrayList<>();
    private volatile Thread controller;
    private volatile double baselineProbeMs;

    // Closed loop: keep adding workers until a trivial query becomes unresponsive (this many times slower
    // than baseline, or the absolute floor). Uses a self-probe rather than database commit rate so the
    // injector's own commits cannot inflate the signal and mask the degradation it is trying to create.
    protected boolean closedLoop = true;
    protected double degradeFactor = 4.0;
    protected double minDegradeMs = 10.0;
    protected static final long SAMPLE_MS = 3000;

    AdaptiveFaultInject(String dbType, String fault) { super(dbType, fault); }

    @Override public final void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) { printHelp(); return; }
        String duration = getArg(args, "-duration");
        if (duration == null) { printHelp(); return; }
        long ms = Long.parseLong(duration);
        if (ms <= 0) throw new IllegalArgumentException("-duration must be positive");
        // Intensity knobs shared by all cases: -loop off pins the case's own sizing (no closed loop),
        // -factor N scales how deep the loop pushes before it stops.
        String loop = getArg(args, "-loop");
        if (loop != null) closedLoop = !(loop.equalsIgnoreCase("off") || loop.equalsIgnoreCase("false"));
        String factor = getArg(args, "-factor");
        if (factor != null) {
            try { degradeFactor = Math.max(1.0, Double.parseDouble(factor)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("-factor must be a number >= 1"); }
        }
        ok.set(0); errors.set(0); messages.clear(); running = true;
        try {
            logAdaptive("Detected " + querySettings("max_connections", "max_process_memory"));
            int reserved = isEnterprise() ? 32 : 20;
            if (settingInt("max_connections", 100) <= reserved)
                throw new IllegalStateException("No connection budget after reserving " + reserved + " TPC-C sessions");
            configure(args);
            if (closedLoop) {
                baselineProbeMs = minProbeMs(5);
                if (baselineProbeMs < Double.MAX_VALUE)
                    logAdaptive(String.format(
                            "Baseline probe %.2f ms; closed-loop escalation enabled (stop when probe >= %.0f ms)",
                            baselineProbeMs, degradeThresholdMs()));
                else
                    logAdaptive("Probe signal unavailable; closed-loop escalation disabled");
            }
            startWork();
            if (closedLoop && baselineProbeMs < Double.MAX_VALUE) {
                controller = new Thread(this::runController, faultType + "-controller");
                controller.setDaemon(true);
                controller.start();
            }
            Thread.sleep(ms);
        } catch (Exception e) {
            failure(e);
        } finally {
            running = false;
            Thread ctrl = controller;
            if (ctrl != null) {
                ctrl.interrupt();
                try { ctrl.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            // Cancel in-flight JDBC operations before joining; interrupt alone does not stop JDBC.
            for (Statement stmt : statements) {
                try { stmt.cancel(); } catch (SQLException e) { failure("JDBC cancel", e); }
            }
            List<Thread> snapshot = new ArrayList<>(workers);
            for (Thread worker : snapshot) worker.interrupt();
            boolean interrupted = Thread.interrupted();
            for (Thread worker : snapshot) {
                while (worker.isAlive()) {
                    try { worker.join(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
            }
            workers.clear();
            for (Connection conn : connections) {
                try { conn.close(); } catch (SQLException e) { failure("JDBC close", e); }
            }
            connections.clear();
            try { cleanup(); } catch (Exception e) { failure(e); }
            if (interrupted) Thread.currentThread().interrupt();
            System.out.println("   [EffectiveWork] ok=" + ok.get() + " failed=" + errors.get());
        }
        if (ok.get() == 0) {
            System.err.println("   EFFECTIVE_WORK_ZERO: " + faultType);
            System.exit(3);
        }
    }

    protected abstract void configure(String[] args) throws Exception;
    protected abstract void startWork() throws Exception;
    protected void cleanup() throws Exception {}
    protected interface Work { void run(Connection conn) throws Exception; }

    protected void worker(Work work) {
        workPool.add(work);
        spawn(work);
    }

    private void spawn(final Work work) {
        Thread thread = new Thread(() -> {
            try (Connection conn = open()) {
                while (running && !Thread.currentThread().isInterrupted()) {
                    try { work.run(conn); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    catch (Exception e) {
                        failure(e);
                        if (!running || conn.isClosed()) break;
                        try { Thread.sleep(100); } catch (InterruptedException stop) { break; }
                    }
                }
            } catch (Exception e) { failure(e); }
        }, faultType + "-" + workers.size());
        workers.add(thread);
        thread.start();
    }

    protected double degradeThresholdMs() {
        return Math.max(baselineProbeMs * degradeFactor, minDegradeMs);
    }

    /**
     * Best-case round-trip latency (ms) of a trivial query issued over a fresh connection. Opening the
     * connection also exercises the thread pool, so an exhausted pool (a fresh session cannot be served)
     * shows up here as a large value or a timeout. Returns Double.MAX_VALUE when the probe cannot run,
     * which is itself treated as fully degraded.
     */
    private double probeMs() {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.setQueryTimeout(5);
            st.execute("SELECT 1"); // warm the connection outside the measurement
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                st.execute("SELECT 1");
                best = Math.min(best, System.nanoTime() - start);
            }
            return best / 1e6;
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }

    private double minProbeMs(int times) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < times; i++) {
            best = Math.min(best, probeMs());
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return best;
    }

    /**
     * Supplies one fresh, self-contained pressure unit for closed-loop escalation, or null if the case
     * cannot escalate. The default replicates a proportionally-chosen closure the case already registered,
     * which is correct as long as those closures hold no per-session state; a case that keeps per-connection
     * setup must override this (or make the setup per-session safe).
     */
    protected Work escalationWork() {
        return workPool.isEmpty() ? null : workPool.get(ThreadLocalRandom.current().nextInt(workPool.size()));
    }

    /**
     * Closed-loop escalator: while a trivial query stays responsive, add pressure workers until the probe
     * crosses the degradation threshold or the connection budget is reached. This makes the fault adapt to
     * the instance's real resisting capacity instead of trusting a static sizing formula.
     */
    private void runController() {
        int ceiling = Math.max(2, budget() - 2);
        double threshold = degradeThresholdMs();
        while (running && !Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(SAMPLE_MS); } catch (InterruptedException e) { return; }
            if (!running) return;
            double probe = probeMs();
            int current = workers.size();
            logAdaptive(String.format("Closed-loop: probe %.1f ms (threshold %.1f ms), workers=%d/%d",
                    probe, threshold, current, ceiling));
            if (probe >= threshold) continue; // instance is already unresponsive enough
            if (current >= ceiling) {
                logAdaptive("Closed-loop: at connection ceiling " + ceiling +
                        " without reaching target; raise -threads for more pressure");
                continue;
            }
            Work unit = escalationWork();
            if (unit == null || !running) continue;
            int step = Math.max(2, Math.min(ceiling - current, Math.max(4, ceiling / 8)));
            for (int i = 0; i < step && running && workers.size() < ceiling; i++)
                spawn(unit);
            logAdaptive("Closed-loop: escalated +" + step + " workers (probe still below threshold)");
        }
    }

    protected Connection open() throws SQLException {
        activeSql.set("JDBC connect");
        Connection conn = getConnection();
        connections.add(conn);
        return conn;
    }

    protected long sql(Connection conn, String sql) throws SQLException {
        return executeSql(conn, sql, false);
    }

    protected long scalar(Connection conn, String sql) throws SQLException {
        return executeSql(conn, sql, true);
    }

    private long executeSql(Connection conn, String sql, boolean scalar) throws SQLException {
        activeSql.set(sql);
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(60);
            statements.add(stmt);
            try {
                if (!stmt.execute(sql)) return Math.max(0, stmt.getUpdateCount());
                long rows = 0;
                try (ResultSet rs = stmt.getResultSet()) { while (rs.next()) rows = scalar ? rs.getLong(1) : rows + 1; }
                return rows;
            } finally { statements.remove(stmt); }
        }
    }

    protected void failure(Exception e) { failure(activeSql.get(), e); }
    protected synchronized void failure(String sql, Exception e) {
        errors.incrementAndGet();
        String message = String.valueOf(e.getMessage());
        if (messages.size() < 3 && messages.add(message))
            System.err.println("   [SQL Error] " + message + " | SQL: " + sql);
    }

    protected int arg(String[] args, String name, int def) {
        try { String v = getArg(args, name); return v == null ? def : Integer.parseInt(v); }
        catch (NumberFormatException e) { return def; }
    }
    protected int clamp(int value, int low, int high) { return Math.max(low, Math.min(value, high)); }
    protected int budget() { return Math.max(1, settingInt("max_connections", 100) - (isEnterprise() ? 32 : 20)); }
    protected int threads(String[] args, int base, int cap, int reserved) {
        if (budget() <= reserved) throw new IllegalStateException("Insufficient session budget for workers and anchors/scans");
        int upper = Math.min(scaleByMemory(base, cap), budget() - reserved);
        return clamp(arg(args, "-threads", upper), 1, upper);
    }
    protected long memoryKB() { return Math.max(2097152L, settingKB("max_process_memory", 2097152L)); }
    protected long units() { return Math.max(1L, memoryKB() / 2097152L); }
}
