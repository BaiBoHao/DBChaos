package chaos.inject;

import chaos.core.BaseFaultInject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Shared lifecycle for the seven adaptive white-box cases; no database access until execute. */
abstract class AdaptiveFaultInject extends BaseFaultInject {
    protected final AtomicLong ok = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    protected volatile boolean running;
    private final Set<String> messages = new HashSet<>();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Set<Statement> statements = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<String> activeSql = new ThreadLocal<>();
    private final List<Thread> workers = new ArrayList<>();

    AdaptiveFaultInject(String dbType, String fault) { super(dbType, fault); }

    @Override public final void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) { printHelp(); return; }
        String duration = getArg(args, "-duration");
        if (duration == null) { printHelp(); return; }
        long ms = Long.parseLong(duration);
        if (ms <= 0) throw new IllegalArgumentException("-duration must be positive");
        ok.set(0); errors.set(0); messages.clear(); running = true;
        try {
            logAdaptive("Detected " + querySettings("max_connections", "max_process_memory"));
            int reserved = isEnterprise() ? 32 : 20;
            if (settingInt("max_connections", 100) <= reserved)
                throw new IllegalStateException("No connection budget after reserving " + reserved + " TPC-C sessions");
            configure(args);
            startWork();
            Thread.sleep(ms);
        } catch (Exception e) {
            failure(e);
        } finally {
            running = false;
            // Cancel in-flight JDBC operations before joining; interrupt alone does not stop JDBC.
            for (Statement stmt : statements) {
                try { stmt.cancel(); } catch (SQLException e) { failure("JDBC cancel", e); }
            }
            for (Thread worker : workers) worker.interrupt();
            boolean interrupted = Thread.interrupted();
            for (Thread worker : workers) {
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
