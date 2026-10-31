package chaos.inject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import chaos.core.BaseFaultInject;
import chaos.core.PreflightChecks;

/**
 * 二阶段提交（Prepared Transaction）槽位故障注入，只走槽位与 WAL 持久化路径。
 *
 * <p>不访问业务表，也不获取目录元数据锁：
 * <ul>
 *   <li>一个 anchor 事务在整个窗口内保持 prepared，钉住 OldestXmin，阻塞 vacuum 回收；</li>
 *   <li>其余槽位按数据库实际上限自适应填满，并持续 prepare -> rollback 换手，制造 WAL fsync 压力；</li>
 *   <li>闭环探针使用 txid_current() 的自提交延迟，它和业务提交共享 XID 分配与 WAL flush 路径。</li>
 * </ul>
 * 因此故障强度随实例配置（max_prepared_transactions、max_connections、内存）自适应，
 * 而不会被单纯的 CPU/内存扩容抵消。
 */
public class MaxPreparedInject extends BaseFaultInject {
    @Override
    public void reportTest() {
        printTestReport();
    }


    static { DriverManager.setLoginTimeout(10); }

    private static final String PREFIX = "chaos_prepared_";
    private static final long SAMPLE_MS = 3000;

    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong churnCycles = new AtomicLong();
    private final AtomicInteger gidSeq = new AtomicInteger();
    // Number of prepared (or in-flight prepare) transactions owned by this injector, anchor included.
    private final AtomicInteger slots = new AtomicInteger();
    private final Queue<String> held = new ConcurrentLinkedQueue<String>();
    private final Set<String> messages = Collections.synchronizedSet(new HashSet<String>());
    private final List<Thread> workers = Collections.synchronizedList(new ArrayList<Thread>());
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Set<Statement> statements = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<String> activeSql = new ThreadLocal<String>();

    private String showLimitSql;
    private String batchId;
    private String anchorGid;
    private String probeSql;
    private int targetPrepared;
    private int ceiling;
    private int workerCap;
    private boolean closedLoop = true;
    private boolean fsyncSensitiveProbe;
    private double degradeFactor = 2.0;
    private double minDegradeMs = 2.0;
    private volatile double baselineProbeMs = Double.MAX_VALUE;
    private volatile boolean running;
    private volatile Thread controller;

    public MaxPreparedInject(String dbType) {
        super(dbType, "MAX_PREPARED");
        initSqlTemplates();
    }

    private void initSqlTemplates() {
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            this.showLimitSql = "SHOW max_prepared_transactions";
        } else if ("mysql".equals(sqlType)) {
            this.showLimitSql = "SHOW VARIABLES LIKE 'max_prepared_stmt_count'";
        }
    }

    @Override
    protected void preflight(String[] args) throws Exception {
        preflightLong(args, "-duration", 30000);
        if (hasArg(args, "-count")) preflightInt(args, "-count", 0);
        if (hasArg(args, "-concurrency")) preflightInt(args, "-concurrency", 0);
        String factor = preflightArg(args, "-factor");
        if (factor != null) {
            double value = Double.parseDouble(factor);
            PreflightChecks.require(Double.isFinite(value) && value >= 1, "-factor 必须为 >= 1 的有限数值");
        }
        try (PreflightChecks checks = new PreflightChecks(getConnection(), getStandardDbType())) {
            checks.prepared();
            // Adaptive worker counts are resolved later against the instance's connection budget.
            checks.connections(3);
        }
        preflightProbes().prepared();
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        runPreflight(args);

        String durationArg = getArg(args, "-duration");
        long durationMs = 30_000L;
        if (durationArg != null) {
            try { durationMs = Long.parseLong(durationArg); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("-duration 必须为正整数毫秒"); }
        }
        if (durationMs <= 0) throw new IllegalArgumentException("-duration 必须为正整数毫秒");

        int requestedCount = parseInt(args, "-count", 0);
        int requestedConcurrency = parseInt(args, "-concurrency", 0);
        String loop = getArg(args, "-loop");
        if (loop != null) closedLoop = !(loop.equalsIgnoreCase("off") || loop.equalsIgnoreCase("false"));
        String factor = getArg(args, "-factor");
        if (factor != null) {
            try { degradeFactor = Math.max(1.0, Double.parseDouble(factor)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("-factor 必须为 >= 1 的数字"); }
        }

        // 1. 探测实例配置并换算目标与并发
        String sqlType = getStandardDbType();
        int reportedLimit = getMaxLimit();
        int sysLimit;
        if ("mysql".equals(sqlType)) {
            // MySQL/OceanBase 的 XA 没有与 max_prepared_transactions 对等的硬槽位，
            // max_prepared_stmt_count 只作为参考，目标取保守容量。
            sysLimit = 1000;
            if (reportedLimit > 0) {
                logAdaptive("max_prepared_stmt_count=" + reportedLimit
                        + "（MySQL 无固定 XA 槽位上限，目标按保守容量 " + sysLimit + " 计）");
            }
        } else {
            if (reportedLimit < 0) {
                sysLimit = 100;
                logAdaptive("max_prepared_transactions 不可读，回退为目标 " + sysLimit);
            } else if (reportedLimit == 0) {
                System.err.println("✘ max_prepared_transactions=0：实例未启用两阶段提交，无法注入该故障。");
                throw new TestExecutionException("EFFECTIVE_WORK_ZERO: " + faultType, 3);
            } else {
                sysLimit = reportedLimit;
            }
        }

        int maxConn = maxConnections();
        int budget = maxConn - reservedSessions(maxConn);
        if (budget < 3) {
            throw new IllegalStateException("连接预算不足（max_connections=" + maxConn
                    + "，预留 " + reservedSessions(maxConn) + "），无法同时容纳注入器与探针");
        }
        ceiling = budget - 2;

        targetPrepared = requestedCount > 0 ? clamp(requestedCount, 1, sysLimit) : sysLimit;
        if (requestedCount > sysLimit) {
            logAdaptive("请求的 -count=" + requestedCount + " 超过实例上限，已收敛为 " + targetPrepared);
        }

        // Churners each roll back then refill a slot, so at most workerCap slots can be in flight.
        // Keeping that below a quarter of the target preserves >=75% occupancy while still
        // producing enough prepare/rollback traffic to pressure WAL flushing.
        workerCap = Math.max(1, Math.min(ceiling, Math.max(2, targetPrepared / 4)));
        int defaultWorkers = clamp(Math.min(scaledWorkers(4, 96), workerCap), 1, workerCap);
        int startWorkers = requestedConcurrency > 0
                ? clamp(requestedConcurrency, 1, workerCap) : defaultWorkers;

        // Recheck the resolved adaptive concurrency before starting probes or prepared transactions.
        try (PreflightChecks checks = new PreflightChecks(getConnection(), getStandardDbType())) {
            checks.connections((long) startWorkers + 2);
        } catch (Exception e) {
            recordTestError(e);
            throw new IllegalStateException("[Preflight] MAX_PREPARED 预检失败，退出测试: " + e.getMessage(), e);
        }

        logAdaptive("Detected max_prepared_transactions=" + sysLimit
                + ", max_connections=" + maxConn
                + ", max_process_memory=" + envSetting("max_process_memory")
                + ", synchronous_commit=" + envSetting("synchronous_commit")
                + ", fsync=" + envSetting("fsync"));
        logAdaptive(String.format(
                "target prepared=%d, start workers=%d (ceiling %d, connection budget %d), duration=%dms",
                targetPrepared, startWorkers, ceiling, budget, durationMs));
        if ("postgresql".equals(sqlType)
                && !"on".equalsIgnoreCase(envSetting("synchronous_commit"))
                && !"local".equalsIgnoreCase(envSetting("synchronous_commit"))
                && !"remote_write".equalsIgnoreCase(envSetting("synchronous_commit"))
                && !"remote_apply".equalsIgnoreCase(envSetting("synchronous_commit"))) {
            logAdaptive("synchronous_commit 未开启：fsync 排队压力被削弱，闭环会继续加压到连接上限");
        }

        // 2. 选择 QoS 探针。txid_current() 会分配 XID 并提交，与业务提交共享 WAL flush 路径。
        fsyncSensitiveProbe = "postgresql".equals(sqlType) && probeAvailable("SELECT txid_current()");
        probeSql = fsyncSensitiveProbe ? "SELECT txid_current()" : "SELECT 1";
        logAdaptive("QoS probe: " + probeSql + (fsyncSensitiveProbe
                ? "（提交路径：XID 分配 + WAL flush）"
                : "（回退探针，无法直接观测 fsync 排队）"));

        if (closedLoop) {
            baselineProbeMs = minProbeMs(5);
            if (baselineProbeMs < Double.MAX_VALUE) {
                logAdaptive(String.format(
                        "Baseline QoS probe %.2f ms; escalate until probe >= %.2f ms (factor %.1f)",
                        baselineProbeMs, degradeThresholdMs(), degradeFactor));
            } else {
                logAdaptive("QoS probe 不可用，关闭闭环升压");
                closedLoop = false;
            }
        }

        batchId = PREFIX + (System.currentTimeMillis() / 1000);
        anchorGid = batchId + "_anchor";
        long preparedBeforeCleanup = -1;

        try {
            // 3. 先放 anchor：整个窗口钉住 OldestXmin，让 vacuum 无法回收业务死元组。
            prepareAnchor();
            running = true;
            int effectiveWorkers = targetPrepared <= 1 ? 0 : startWorkers;
            for (int i = 0; i < effectiveWorkers; i++) spawnWorker();
            if (closedLoop && effectiveWorkers > 0) {
                controller = new Thread(this::runController, faultType + "-controller");
                controller.setDaemon(true);
                controller.start();
            } else if (effectiveWorkers > 0) {
                logAdaptive("Closed-loop disabled; churn runs at fixed sizing");
            }
            Thread.sleep(durationMs);
            preparedBeforeCleanup = preparedCount();
        } catch (Exception e) {
            report("run", e);
            throw e;
        } finally {
            running = false;
            shutdown();
        }

        System.out.println(">>> 注入任务执行结束。");
        System.out.println("   [EffectiveWork] ok=" + ok.get()
                + " failed=" + failures.get()
                + " churn=" + churnCycles.get()
                + " prepared_at_end=" + (preparedBeforeCleanup < 0 ? "n/a" : String.valueOf(preparedBeforeCleanup)));
        if (ok.get() == 0) {
            System.err.println("   EFFECTIVE_WORK_ZERO: " + faultType);
            throw new TestExecutionException("EFFECTIVE_WORK_ZERO: " + faultType, 3);
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n\033[1m不利 Case 用法: \033[33mtxn max_prepared\033[0m");
        System.out.println("  自适应填充 prepared 事务槽位，并在整个窗口内持续 prepare/rollback 换手。");
        System.out.println("  一个 anchor 事务全程持有以钉住 OldestXmin；不访问业务表，也不加目录元数据锁。");
        System.out.println("\n\033[1m参数列表:\033[0m");
        System.out.printf("  %-15s %s\n", "-duration", "选填。持续时长，单位毫秒 (默认 30000)");
        System.out.printf("  %-15s %s\n", "-count", "选填。目标 prepared 数 (默认 max_prepared_transactions，自动收敛)");
        System.out.printf("  %-15s %s\n", "-concurrency", "选填。起始工作线程数 (默认按内存与连接预算自适应)");
        System.out.printf("  %-15s %s\n", "-loop", "选填。off 关闭闭环升压，固定起始强度");
        System.out.printf("  %-15s %s\n", "-factor", "选填。闭环目标：探针延迟达到基线的倍数 (默认 2.0)");
        System.out.println("\n\033[1m示例:\033[0m");
        System.out.println("\033[36m  java -jar DBChaos-0.0.1.jar --db opengauss txn max_prepared -duration 60000\033[0m");
    }

    private void prepareAnchor() throws SQLException {
        try (Connection conn = getConnection()) {
            prepare(conn, anchorGid);
        }
        slots.incrementAndGet();
        ok.incrementAndGet();
        logAdaptive("Anchor prepared: " + anchorGid + "（全程钉住 OldestXmin）");
    }

    /** Claims one prepared slot, or returns false when the target is already reached. */
    private boolean reserveSlot() {
        while (true) {
            int current = slots.get();
            if (current >= targetPrepared) return false;
            if (slots.compareAndSet(current, current + 1)) return true;
        }
    }

    private void spawnWorker() {
        Thread thread = new Thread(() -> {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    Connection conn = null;
                    try {
                        conn = open();
                        workerLoop(conn);
                    } catch (Exception e) {
                        recordTestError(e);
                        if (running) {
                            report("worker", e);
                            backoff();
                        }
                    } finally {
                        if (conn != null) {
                            try { conn.close(); } catch (SQLException ignored) { recordTestError(ignored); }
                            connections.remove(conn);
                        }
                    }
                }
            } finally {
                workers.remove(Thread.currentThread());
            }
        }, faultType + "-" + workers.size());
        workers.add(thread);
        thread.start();
    }

    private void workerLoop(Connection conn) throws SQLException {
        while (running && !Thread.currentThread().isInterrupted()) {
            if (reserveSlot()) {
                String gid = batchId + "_t" + gidSeq.incrementAndGet();
                try {
                    prepare(conn, gid);
                    held.add(gid);
                    ok.incrementAndGet();
                } catch (SQLException e) {
                    recordTestError(e);
                    slots.decrementAndGet();
                    report("PREPARE " + gid, e);
                    if (conn.isClosed()) throw e;
                    backoff();
                }
                continue;
            }
            // Target reached: roll one prepared transaction back, then the next iteration refills the slot.
            String victim = held.poll();
            if (victim == null) {
                backoff();
                continue;
            }
            try {
                rollback(conn, victim);
                slots.decrementAndGet();
                churnCycles.incrementAndGet();
            } catch (SQLException e) {
                recordTestError(e);
                held.add(victim);
                report("ROLLBACK " + victim, e);
                if (conn.isClosed()) throw e;
                backoff();
            }
        }
    }

    private void prepare(Connection conn, String gid) throws SQLException {
        // Autocommit keeps PREPARE/ROLLBACK outside an implicit transaction block: openGauss rejects
        // "ROLLBACK PREPARED ... cannot run inside a transaction block" once the driver re-enters one.
        conn.setAutoCommit(true);
        if ("postgresql".equals(getStandardDbType())) {
            // One statement string makes BEGIN/SELECT/PREPARE a single transaction block so the
            // anchor allocates a real XID (an empty prepared transaction does not pin OldestXmin).
            execute(conn, "BEGIN; SELECT txid_current(); PREPARE TRANSACTION '" + gid + "'");
        } else {
            execute(conn, "XA START '" + gid + "'");
            execute(conn, "SELECT 1");
            execute(conn, "XA END '" + gid + "'");
            execute(conn, "XA PREPARE '" + gid + "'");
        }
    }

    private void rollback(Connection conn, String gid) throws SQLException {
        conn.setAutoCommit(true);
        execute(conn, "postgresql".equals(getStandardDbType())
                ? "ROLLBACK PREPARED '" + gid + "'"
                : "XA ROLLBACK '" + gid + "'");
    }

    private void execute(Connection conn, String sql) throws SQLException {
        activeSql.set(sql);
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(60);
            statements.add(stmt);
            try {
                stmt.execute(sql);
            } finally {
                statements.remove(stmt);
            }
        }
    }

    private void runController() {
        double threshold = degradeThresholdMs();
        boolean ceilingLogged = false;
        while (running && !Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(SAMPLE_MS); } catch (InterruptedException e) { return; }
            if (!running) return;
            double probe = probeMs();
            long prepared = preparedCount();
            int current = workers.size();
            logAdaptive(String.format(
                    "Closed-loop: probe %.2f ms (threshold %.2f ms), prepared=%s, workers=%d/%d",
                    probe, threshold, prepared < 0 ? "n/a" : String.valueOf(prepared), current, workerCap));
            if (probe >= threshold) {
                ceilingLogged = false;
                continue;
            }
            // Churners each free a slot while refilling it, so capping them bounds the occupancy dip.
            if (current >= workerCap) {
                if (!ceilingLogged) {
                    logAdaptive("Closed-loop: churn workers=" + current + " 已达上限 " + workerCap
                            + "（目标槽位 " + targetPrepared + " 的 1/4）；探针仍低于阈值，说明实例 IO 仍有余量");
                    ceilingLogged = true;
                }
                continue;
            }
            ceilingLogged = false;
            int step = Math.max(1, Math.min(workerCap - current, Math.max(2, workerCap / 8)));
            for (int i = 0; i < step && running && workers.size() < workerCap; i++) spawnWorker();
            logAdaptive("Closed-loop: escalated +" + step + " workers (probe below threshold)");
        }
    }

    private double degradeThresholdMs() {
        return Math.max(baselineProbeMs * degradeFactor, minDegradeMs);
    }

    private double probeMs() {
        if (probeSql == null) return Double.MAX_VALUE;
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.setQueryTimeout(5);
            st.execute(probeSql); // warm the connection outside the measurement
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                st.execute(probeSql);
                best = Math.min(best, System.nanoTime() - start);
            }
            return best / 1e6;
        } catch (Exception e) {
            recordTestError(e);
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

    private boolean probeAvailable(String sql) {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.setQueryTimeout(5);
            st.execute(sql);
            return true;
        } catch (Exception e) {
            recordTestError(e);
            return false;
        }
    }

    private long preparedCount() {
        if (!"postgresql".equals(getStandardDbType())) return -1;
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.setQueryTimeout(5);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_prepared_xacts")) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception e) {
            recordTestError(e);
            return -1;
        }
    }

    private List<String> discoverGids(Connection conn) {
        List<String> result = new ArrayList<String>();
        if (!"postgresql".equals(getStandardDbType())) return result;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT gid FROM pg_prepared_xacts WHERE gid LIKE ?")) {
            ps.setString(1, batchId + "%");
            ps.setQueryTimeout(10);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        } catch (Exception e) {
            report("discover prepared", e);
        }
        return result;
    }

    private void shutdown() {
        Thread ctrl = controller;
        if (ctrl != null) {
            ctrl.interrupt();
            try { ctrl.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        // Cancel in-flight JDBC operations before joining; interrupt alone does not stop JDBC.
        for (Statement stmt : statements) {
            try { stmt.cancel(); } catch (SQLException ignored) { recordTestError(ignored); }
        }
        List<Thread> snapshot = new ArrayList<Thread>(workers);
        for (Thread worker : snapshot) worker.interrupt();
        boolean interrupted = Thread.interrupted();
        for (Thread worker : snapshot) {
            while (worker.isAlive()) {
                try { worker.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        workers.clear();
        cleanupPrepared();
        for (Connection conn : connections) {
            try { conn.close(); } catch (SQLException ignored) { recordTestError(ignored); }
        }
        connections.clear();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void cleanupPrepared() {
        List<String> gids = new ArrayList<String>();
        String gid;
        while ((gid = held.poll()) != null) gids.add(gid);
        // Release the xmin pin last.
        if (anchorGid != null) gids.add(anchorGid);
        System.out.println(">>> 开始自动清理 prepared 事务（tracked=" + gids.size() + "）...");
        try (Connection conn = getConnection()) {
            for (String g : gids) {
                try { rollback(conn, g); } catch (SQLException ignored) { recordTestError(ignored); }
            }
            // Catch anything prepared after the snapshot (e.g. a timed-out PREPARE that still landed).
            for (String g : discoverGids(conn)) {
                try { rollback(conn, g); } catch (SQLException ignored) { recordTestError(ignored); }
            }
        } catch (Exception e) {
            report("cleanup", e);
        }
    }

    private Connection open() throws SQLException {
        Connection conn = getConnection();
        connections.add(conn);
        return conn;
    }

    private int getMaxLimit() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(showLimitSql)) {
            if (rs.next()) {
                String val = "mysql".equals(getStandardDbType()) ? rs.getString(2) : rs.getString(1);
                return Integer.parseInt(val.trim());
            }
        } catch (Exception e) {
            recordTestError(e);
            System.err.println("无法获取 prepared 事务上限: " + e.getMessage());
        }
        return -1;
    }

    private int maxConnections() {
        if (!"mysql".equals(getStandardDbType())) {
            return Math.max(1, settingInt("max_connections", 100));
        }
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'max_connections'")) {
            if (rs.next()) return Math.max(1, Integer.parseInt(rs.getString(2).trim()));
        } catch (Exception e) {
            recordTestError(e);
            logAdaptive("max_connections 不可读，回退 100: " + e.getMessage());
        }
        return 100;
    }

    private int reservedSessions(int maxConn) {
        if ("mysql".equals(getStandardDbType())) return maxConn >= 150 ? 32 : 20;
        return isEnterprise() ? 32 : 20;
    }

    private int scaledWorkers(int base, int cap) {
        long units = "mysql".equals(getStandardDbType()) ? 1L : memoryUnits();
        return (int) Math.min(cap, Math.max(base, base * Math.min(units, Integer.MAX_VALUE)));
    }

    private String envSetting(String name) {
        if ("mysql".equals(getStandardDbType())) return "n/a";
        return settingStr(name, "unknown");
    }

    private int parseInt(String[] args, String name, int def) {
        String value = getArg(args, name);
        if (value == null) return def;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " 必须为整数"); }
    }

    private int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }

    private void report(String sql, Exception e) {
        recordTestError(e);
        failures.incrementAndGet();
        String message = String.valueOf(e.getMessage());
        synchronized (messages) {
            if (messages.size() < 3 && messages.add(message)) {
                System.err.println("   [SQL Error] " + message + " | op: " + sql);
            }
        }
    }

    private void backoff() {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
