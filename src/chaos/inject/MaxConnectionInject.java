package chaos.inject;

import chaos.core.BaseFaultInject;
import chaos.monitor.OperationMetrics;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Connection pressure, measured separately from business availability. */
public class MaxConnectionInject extends BaseFaultInject {
    private int dbMaxConnections, dbMaxThreads, requested, actualConcurrency;
    private Boolean threadPoolEnabled;
    private String mode, poolConfigSource;
    private long durationMs, pressureStart, pressureEnd, metricsEnd, holdNanos;
    private volatile boolean running;
    private boolean cleanupComplete, setupComplete;
    private OperationMetrics connects = new OperationMetrics(), queries = new OperationMetrics();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Set<Connection> closing = ConcurrentHashMap.newKeySet();
    private final Set<Statement> statements = ConcurrentHashMap.newKeySet();
    private final List<ExecutorService> executors = new ArrayList<>();
    private final AtomicInteger peakConnections = new AtomicInteger(), heldPeak = new AtomicInteger();
    private final AtomicInteger inFlightQueries = new AtomicInteger(), peakQueries = new AtomicInteger();
    private final AtomicLong teardownFailures = new AtomicLong(), cancelledStatements = new AtomicLong();
    private final AtomicLong abortedConnections = new AtomicLong();
    private final AtomicLong setupFailures = new AtomicLong();
    private final AtomicLong pressureQueryFailures = new AtomicLong();

    public MaxConnectionInject(String dbType) { super(dbType, "MAX_CONNECTION"); }

    private void reset() {
        if ((pressureStart != 0 && !cleanupComplete) || !connections.isEmpty() || executors.stream().anyMatch(e -> !e.isTerminated()))
            throw new IllegalStateException("上轮任务尚未停止，不能复用注入器");
        mode = null; poolConfigSource = "未读取"; dbMaxConnections = dbMaxThreads = requested = actualConcurrency = 0;
        threadPoolEnabled = null; durationMs = pressureStart = pressureEnd = metricsEnd = holdNanos = 0;
        cleanupComplete = setupComplete = false; running = false;
        connects = new OperationMetrics(); queries = new OperationMetrics(); executors.clear();
        statements.clear(); closing.clear(); setupFailures.set(0); peakConnections.set(0); heldPeak.set(0); inFlightQueries.set(0); peakQueries.set(0);
        teardownFailures.set(0); cancelledStatements.set(0); abortedConnections.set(0); pressureQueryFailures.set(0);
    }

    @Override public void execute(String[] args) throws Exception {
        reset();
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) { printHelp(); return; }
        String selected = getArg(args, "-mode"), duration = getArg(args, "-duration");
        if (selected == null || duration == null) throw new IllegalArgumentException("必须提供 -mode 和 -duration");
        selected = selected.toLowerCase(Locale.ROOT);
        if (!Arrays.asList("conn_storm", "conn_exhaustion", "thread_saturation").contains(selected))
            throw new IllegalArgumentException("-mode 必须为 conn_storm/conn_exhaustion/thread_saturation");
        durationMs = Long.parseLong(duration);
        if (durationMs <= 0 || durationMs > Long.MAX_VALUE / 1000000 - 5000)
            throw new IllegalArgumentException("-duration 必须为有效的正整数毫秒");
        String count = getArg(args, "-count");
        if (hasArg(args, "-count") && count == null) throw new IllegalArgumentException("-count 缺少值");
        if (count != null && Integer.parseInt(count) <= 0) throw new IllegalArgumentException("-count 必须为正整数");
        if (selected.equals("thread_saturation") && durationMs < 1000)
            throw new IllegalArgumentException("thread_saturation 的 -duration 至少为 1000ms");
        mode = selected;
        detectDatabaseConfig();
        requested = count != null ? Integer.parseInt(count) : mode.equals("thread_saturation") ? 32
                : dbMaxConnections > 0 ? dbMaxConnections : 200;
        if (mode.equals("thread_saturation")) {
            int base = dbMaxThreads > 0 ? dbMaxThreads : 64;
            actualConcurrency = (int)Math.min((long)base + requested, dbMaxConnections > 2 ? dbMaxConnections - 2 : 10);
        } else actualConcurrency = requested;
        System.out.println("【环境探测】 最大连接数: " + dbMaxConnections
                + (mode.equals("thread_saturation") ? " | 配置线程池大小: " + dbMaxThreads + " | 线程池开启: " + threadPoolEnabled
                : " | 线程池参数: 当前模式无需读取"));
        System.out.println(">>> 执行模式: " + mode + " | 请求-count: " + requested
                + " | 目标并发/连接: " + actualConcurrency + " | 时长: " + durationMs + "ms");
        running = true; markFaultStarted(); pressureStart = System.nanoTime();
        try {
            if (mode.equals("conn_storm")) storm();
            else if (mode.equals("conn_exhaustion")) exhaustion();
            else saturation();
        } finally {
            pressureEnd = System.nanoTime(); running = false; cleanup(); metricsEnd = System.nanoTime();
        }
        if (!cleanupComplete || connects.snapshot().unfinished() > 0 || queries.snapshot().unfinished() > 0)
            throw new IllegalStateException("故障撤销未确认：仍有JDBC任务未结束，报告为部分统计");
        if (connects.snapshot().successes == 0)
            throw new TestExecutionException("EFFECTIVE_WORK_ZERO: 没有建立有效压力连接", 3);
        if (queries.snapshot().successes == 0 && pressureQueryFailures.get() > 0)
            throw new TestExecutionException("EFFECTIVE_WORK_ZERO: 没有完成任何压力SQL，请检查SQLState和服务状态", 3);
    }

    private ExecutorService pool(int size) {
        ExecutorService e = Executors.newFixedThreadPool(size, task -> {
            Thread t = new Thread(task, "max-connection-worker");
            t.setDaemon(true); return t; // A JDBC login ignoring interrupt must not pin the JVM.
        });
        executors.add(e); return e;
    }

    private Connection connect() throws SQLException {
        connects.begin(); long start = System.nanoTime(); Connection c;
        try { c = getConnection(); }
        catch (SQLException e) { connects.failure(System.nanoTime() - start, e); recordTestError(e); throw e; }
        connects.success(System.nanoTime() - start);
        connections.add(c); peakConnections.accumulateAndGet(connections.size(), Math::max);
        if (!running) { release(c); throw new SQLException("测试窗口已结束，关闭迟到连接", "57014"); }
        return c;
    }

    private void query(Connection c, String sql) throws SQLException {
        queries.begin(); long start = System.nanoTime();
        peakQueries.accumulateAndGet(inFlightQueries.incrementAndGet(), Math::max);
        try {
            try (Statement s = c.createStatement()) {
                statements.add(s);
                try { if (running) s.execute(sql); else throw new SQLException("撤销阶段取消SQL", "57014"); }
                finally { statements.remove(s); }
            }
            queries.success(System.nanoTime() - start);
        } catch (SQLException e) {
            queries.failure(System.nanoTime() - start, e);
            if (running) pressureQueryFailures.incrementAndGet(); else teardownFailures.incrementAndGet();
            recordTestError(e); throw e;
        } finally { inFlightQueries.decrementAndGet(); }
    }

    private void release(Connection c) {
        if (c == null || !connections.contains(c) || !closing.add(c)) return;
        try { c.close(); connections.remove(c); }
        catch (SQLException e) { teardownFailures.incrementAndGet(); recordTestError(e); }
        finally { closing.remove(c); }
    }

    private void storm() throws InterruptedException {
        ExecutorService e = pool(actualConcurrency); CountDownLatch start = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        for (int i = 0; i < actualConcurrency; i++) e.execute(() -> {
            try {
                start.await();
                while (running && deadline - System.nanoTime() > 0 && !Thread.currentThread().isInterrupted()) {
                    Connection c = null;
                    try { c = connect(); query(c, "SELECT 1"); }
                    catch (SQLException error) { if (running) Thread.sleep(10); }
                    finally { release(c); }
                }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        start.countDown(); e.shutdown(); e.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS);
    }

    private void exhaustion() throws InterruptedException {
        List<Connection> held = Collections.synchronizedList(new ArrayList<Connection>());
        AtomicBoolean accepting = new AtomicBoolean(true);
        ExecutorService connectors = pool(Math.min(100, actualConcurrency));
        for (int i = 0; i < actualConcurrency; i++) connectors.execute(() -> {
            Connection c = null;
            try {
                c = connect(); c.setAutoCommit(true);
                synchronized (held) {
                    if (accepting.get()) { held.add(c); heldPeak.accumulateAndGet(held.size(), Math::max); c = null; }
                }
            } catch (SQLException error) {
                if (c != null) { setupFailures.incrementAndGet(); recordTestError(error); }
            } finally { release(c); }
        });
        connectors.shutdown(); setupComplete = connectors.awaitTermination(20000, TimeUnit.MILLISECONDS);
        List<Connection> snapshot;
        synchronized (held) { accepting.set(false); snapshot = new ArrayList<>(held); }
        System.out.println("连接建立完毕，实际持有连接: " + snapshot.size());
        long start = System.nanoTime(), deadline = start + TimeUnit.MILLISECONDS.toNanos(durationMs);
        ExecutorService hearts = pool(Math.max(1, snapshot.size()));
        for (Connection c : snapshot) hearts.execute(() -> {
            while (running && deadline - System.nanoTime() > 0 && !Thread.currentThread().isInterrupted()) {
                try { query(c, "SELECT 1"); }
                catch (SQLException error) {
                    try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
        });
        hearts.shutdown();
        long left = deadline - System.nanoTime();
        if (left > 0) TimeUnit.NANOSECONDS.sleep(left);
        holdNanos = System.nanoTime() - start;
    }

    private void saturation() throws InterruptedException {
        ExecutorService e = pool(actualConcurrency);
        CountDownLatch connected = new CountDownLatch(actualConcurrency), start = new CountDownLatch(1);
        AtomicLong deadline = new AtomicLong();
        final String prefix = "mysql".equals(getStandardDbType()) ? "SELECT SLEEP(" : "SELECT pg_sleep(";
        for (int i = 0; i < actualConcurrency; i++) e.execute(() -> {
            Connection c = null;
            boolean ready = false;
            try {
                // Complete all handshakes before any sleep consumes a server worker.
                c = connect(); connected.countDown(); ready = true; start.await();
                while (running && !Thread.currentThread().isInterrupted()) {
                    long remaining = deadline.get() - System.nanoTime();
                    if (remaining <= 0) break;
                    double seconds = Math.min(1.0, Math.max(0.001, remaining / 1e9));
                    query(c, prefix + String.format(Locale.ROOT, "%.3f", seconds) + ")");
                }
            } catch (SQLException error) { /* Recorded by connect/query. */ }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { if (!ready) connected.countDown(); release(c); }
        });
        try {
            setupComplete = connected.await(20000, TimeUnit.MILLISECONDS);
            long begin = System.nanoTime(); deadline.set(begin + TimeUnit.MILLISECONDS.toNanos(durationMs));
            start.countDown(); e.shutdown();
            try { TimeUnit.MILLISECONDS.sleep(durationMs); }
            finally { holdNanos = System.nanoTime() - begin; }
        } finally { start.countDown(); }
    }

    private void cleanup() {
        for (ExecutorService e : executors) e.shutdownNow();
        ExecutorService closer = Executors.newCachedThreadPool(task -> {
            Thread t = new Thread(task, "max-connection-cleanup"); t.setDaemon(true); return t;
        });
        for (Connection c : connections) closer.execute(() -> {
            // close() can wait behind a blocked query. SQL cancellation may itself
            // need another connection, which cannot be admitted during exhaustion.
            // JDBC abort closes the transport without that extra server connection.
            try {
                c.abort(Runnable::run);
                if (c.isClosed()) abortedConnections.incrementAndGet();
            } catch (SQLFeatureNotSupportedException | AbstractMethodError unsupported) {
                // Older drivers still get the normal close path; unfinished work
                // remains a failed teardown rather than an assumed recovery.
            } catch (SQLException error) {
                teardownFailures.incrementAndGet(); recordTestError(error);
            } finally { release(c); }
        });
        closer.shutdown(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean interrupted = Thread.interrupted();
        List<ExecutorService> all = new ArrayList<>(executors); all.add(closer);
        for (ExecutorService e : all) {
            try { e.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException ex) { interrupted = true; }
        }
        cleanupComplete = all.stream().allMatch(ExecutorService::isTerminated) && connections.isEmpty();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void detectDatabaseConfig() throws SQLException {
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            boolean mysql = "mysql".equals(getStandardDbType());
            try (ResultSet r = s.executeQuery(mysql ? "SHOW VARIABLES LIKE 'max_connections'" : "SHOW max_connections")) {
                if (r.next()) dbMaxConnections = r.getInt(mysql ? 2 : 1);
            }
            if (dbMaxConnections <= 0) throw new SQLException("无法读取有效的max_connections，取消注入");
            // Neither storm nor exhaustion needs privileged thread-pool settings.
            if (mode.equals("thread_saturation") && !mysql
                    && ("opengauss".equalsIgnoreCase(dbType) || "og".equalsIgnoreCase(dbType))) {
                try (ResultSet r = s.executeQuery("SHOW enable_thread_pool")) {
                    if (r.next()) threadPoolEnabled = "on".equalsIgnoreCase(r.getString(1));
                }
                if (!Boolean.TRUE.equals(threadPoolEnabled))
                    throw new IllegalStateException("enable_thread_pool未开启，不能执行openGauss线程池饱和实验");
                String attr;
                try (ResultSet r = s.executeQuery("SHOW thread_pool_attr")) {
                    if (!r.next()) throw new SQLException("thread_pool_attr没有返回值");
                    attr = r.getString(1); poolConfigSource = "SHOW";
                } catch (SQLException denied) {
                    if (!"42501".equals(denied.getSQLState())) throw denied;
                    // A DBA-installed, no-argument function exposes only this one setting.
                    try (ResultSet r = s.executeQuery("SELECT dbchaos_monitor.thread_pool_attr()")) {
                        if (!r.next()) throw new SQLException("只读监控函数没有返回配置");
                        attr = r.getString(1); poolConfigSource = "只读监控函数";
                    } catch (SQLException missing) {
                        throw new IllegalStateException("普通账号无权读取thread_pool_attr，且只读监控函数不可用；"
                                + "请由管理员执行scripts/probe/install_connection_monitor.sql后重试，未启动故障", missing);
                    }
                }
                dbMaxThreads = parseThreadPoolSize(attr, dbMaxConnections);
                if (dbMaxThreads <= 0) throw new IllegalStateException("无效thread_pool_attr，取消线程饱和注入");
                System.out.println("[ConnectionConfig] 线程池配置来源=" + poolConfigSource + " 总线程=" + dbMaxThreads);
            }
        }
    }

    /** Numeric format is total threads, group count, binding; never multiply the first two. */
    public static int parseThreadPoolSize(String attr, int maxConnections) {
        if (attr == null || attr.trim().isEmpty()) return 0;
        String[] parts = attr.replace("\"", "").replace("'", "").trim().split(","); long total;
        if (parts[0].trim().matches("[0-9]+")) total = Long.parseLong(parts[0].trim());
        else {
            long groups = 0, perGroup = 0;
            for (String p : parts) {
                String[] pair = p.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals("group")) groups = Long.parseLong(pair[1].trim());
                if (pair.length == 2 && pair[0].equals("per_group")) perGroup = Long.parseLong(pair[1].trim());
            }
            total = Math.multiplyExact(groups, perGroup);
        }
        return total <= 0 ? 0 : (int)Math.min(total, maxConnections > 0 ? maxConnections : Integer.MAX_VALUE);
    }

    private static String number(double value) { return Double.isNaN(value) ? "N/A" : String.format(Locale.ROOT, "%.3f", value); }
    private static String pct(long numerator, long denominator) { return denominator <= 0 ? "N/A" : number(100.0 * numerator / denominator) + "%"; }
    private void printOperations(String name, OperationMetrics.Snapshot s, double seconds) {
        System.out.println("[ConnectionReport] " + name + " 尝试=" + s.attempts + " 成功=" + s.successes
                + " 失败=" + s.failures + " 未完成=" + s.unfinished() + " 完成请求成功率=" + number(s.successPct())
                + (Double.isNaN(s.successPct()) ? "" : "%") + " 完成速率=" + number(s.completedPerSecond(seconds)) + "/s 成功速率="
                + number(seconds > 0 ? s.successes / seconds : Double.NaN) + "/s");
        System.out.println("[ConnectionReport] " + name + " 全部完成请求延迟: avg=" + number(s.meanMs)
                + "ms p95上界估计=" + number(s.p95UpperMs) + "ms max=" + number(s.maxMs) + "ms SQLState=" + s.sqlStates);
    }

    @Override public void reportTest() {
        printTestReport();
        if (mode == null) { System.out.println("[ConnectionReport] 参数未就绪，没有执行连接故障，效果证据不足。"); return; }
        if (pressureStart == 0) {
            System.out.println("[ConnectionAnalysis] 配置预检失败，未启动故障注入；不能报告故障生效或恢复。"); return;
        }
        OperationMetrics.Snapshot c = connects.snapshot(), q = queries.snapshot();
        double seconds = pressureStart == 0 ? 0 : ((pressureEnd == 0 ? System.nanoTime() : pressureEnd) - pressureStart) / 1e9;
        double sampleSeconds = pressureStart == 0 ? 0 : ((metricsEnd == 0 ? System.nanoTime() : metricsEnd) - pressureStart) / 1e9;
        System.out.println("\n[ConnectionReport] 模式=" + mode + " 目标时长=" + durationMs + "ms 注入窗口=" + number(seconds)
                + "s 统计窗口(含准备/撤销)=" + number(sampleSeconds) + "s 请求-count=" + requested + " 目标并发/连接=" + actualConcurrency);
        printOperations("建连", c, cleanupComplete ? sampleSeconds : 0); printOperations("SQL", q, cleanupComplete ? sampleSeconds : 0);
        System.out.println("[ConnectionReport] 本地连接峰值=" + peakConnections.get() + " 客户端在途SQL峰值=" + peakQueries.get()
                + " 会话准备错误=" + setupFailures.get() + " 压力期SQL失败=" + pressureQueryFailures.get() + " 撤销期错误=" + teardownFailures.get()
                + " 已取消语句=" + cancelledStatements.get() + " 已中止连接=" + abortedConnections.get() + " 清理完成=" + cleanupComplete);
        if (mode.equals("conn_storm")) {
            System.out.println("[ConnectionAnalysis] 建连风暴: 成功建连速率=" + number(cleanupComplete && sampleSeconds > 0 ? c.successes / sampleSeconds : Double.NaN)
                    + "/s 容量拒绝=" + c.capacityFailures + "；这是压力端观测值，不是数据库最大建连能力。");
            if (c.successes == 0) System.out.println("[ConnectionAnalysis] 没有成功建连，不能声称已形成持续建连/断连风暴；检查认证、网络及连接限额。");
            else if (c.capacityFailures > 0) System.out.println("[ConnectionAnalysis] 已产生连接周转，并观察到连接容量拒绝；其他失败需按SQLState区分认证、网络或超时。");
            else System.out.println("[ConnectionAnalysis] 已产生连接周转，未观察到容量拒绝；是否影响其他客户端需业务/独立探针证据。");
        } else if (mode.equals("conn_exhaustion")) {
            System.out.println("[ConnectionAnalysis] 连接耗尽: 本地持有峰值=" + heldPeak.get() + " 目标达成率=" + pct(heldPeak.get(), requested)
                    + " 占配置max_connections比例=" + pct(heldPeak.get(), dbMaxConnections)
                    + " 持有窗口=" + number(holdNanos / 1e9) + "s 建连阶段完成=" + setupComplete);
            if (heldPeak.get() == 0) System.out.println("[ConnectionAnalysis] 未持有连接，效果证据不足，不能判定连接耗尽。");
            else System.out.println("[ConnectionAnalysis] 已占用连接；本地持有数不等于服务器全部在线连接数，心跳失败可能意味着连接已失效。"
                    + (c.capacityFailures > 0 ? "观察到建连容量拒绝。" : "未观察到容量拒绝，不能仅凭目标达成率证明槽位耗尽。"));
        } else {
            System.out.println("[ConnectionAnalysis] 线程饱和: 配置线程池=" + dbMaxThreads + " 开启=" + threadPoolEnabled
                    + " 配置来源=" + poolConfigSource + " 建连准备完成=" + setupComplete
                    + " 持续施压=" + number(holdNanos / 1e9) + "s 发起sleep=" + q.attempts + " 完成sleep=" + q.successes + " 在途峰值=" + peakQueries.get());
            if (!Boolean.TRUE.equals(threadPoolEnabled))
                System.out.println("[ConnectionAnalysis] 线程池未确认开启；当前最多说明会话/backend占用，不能判定线程池饱和。");
            else if (peakQueries.get() < dbMaxThreads || dbMaxThreads <= 0)
                System.out.println("[ConnectionAnalysis] 配置或并发覆盖证据不足，不能判定工作线程池已占满。");
            else System.out.println("[ConnectionAnalysis] 客户端在途sleep已覆盖配置线程数；在途包含服务器排队，不是实际worker占用数，需等待链/独立查询延迟确认饱和。");
        }
        if (!cleanupComplete || c.unfinished() > 0 || q.unfinished() > 0)
            System.out.println("[ConnectionAnalysis] 仍有未完成任务，当前统计为部分结果，故障撤销未确认；不能据此计算可靠恢复时间。");
        System.out.println("[ConnectionAnalysis] 上述成功率属于注入器自身，容量拒绝可能是目标故障；不等于业务新连接成功率。"
                + "未内置独立恢复探针；业务下降、持续服务、恢复结论由通用Resilience报告提供，无业务数据时为证据不足。");
    }

    @Override public void printHelp() {
        System.out.println("用法: session max_connection -mode <conn_storm|conn_exhaustion|thread_saturation> -duration <ms> [-count N]");
        System.out.println("-duration 必填正整数毫秒；thread_saturation至少1000ms。-count 必须为正整数。");
        System.out.println("conn_storm: -count为并发建连线程数；conn_exhaustion: 目标持有连接数；默认max_connections，探测失败200。");
        System.out.println("thread_saturation: -count为配置线程池之外的额外线程，默认32，按连接上限裁剪；openGauss配置不可读时取消注入。");
        System.out.println("线程饱和先完成建连，再循环执行至多1秒的sleep；管理员可安装只读配置函数以供普通账号读取。\nreportTest输出建连/SQL计数、成功率、延迟、速率及模式分析；p95是有界直方图的上界估计。");
        System.out.println("注入器指标不等于业务可用性；--wait-stable <结果目录>启用业务韧性报告。");
    }
}
