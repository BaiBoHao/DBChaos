package chaos.inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import chaos.core.BaseFaultInject;

/**
 * 计划跳变震荡故障注入实现。
 * 通过在沙盒 Schema 中交替注入倾斜数据和平衡数据，诱导内核自动触发统计信息更新。
 * 实现跨 Schema 的资源挤兑，验证数据库系统的韧性隔离能力。
 */
public class PlanFlipInject extends BaseFaultInject {

    private final AtomicLong totalLatency = new AtomicLong(0);
    private final AtomicLong queryCount = new AtomicLong(0);

    // SQL 模板泛化
    private String setAutoStatsSql = "";
    private String checkStatsSql = "";

    public PlanFlipInject(String dbType) {
        super(dbType, "PLAN_FLIP");
        initSqlTemplates();
    }

    private void initSqlTemplates() {
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            // PostgreSQL/openGauss: 强制开启局部 autovacuum 并将阈值降为最低
            this.setAutoStatsSql = "ALTER TABLE %s SET (autovacuum_enabled = true, " +
                                   "autovacuum_analyze_scale_factor = 0, autovacuum_analyze_threshold = 10)";
            this.checkStatsSql = "SELECT last_autoanalyze FROM pg_stat_user_tables WHERE relname = ?";
        } else if ("mysql".equals(sqlType)) {
            // MySQL/OceanBase: 开启持久化统计信息自动重新计算
            this.setAutoStatsSql = "ALTER TABLE %s STATS_AUTO_RECALC = 1, STATS_SAMPLE_PAGES = 100";
            this.checkStatsSql = "SELECT update_time FROM information_schema.tables WHERE table_name = ?";
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        // 参数编排
        String tableName = "chaos_sandbox_" + (System.currentTimeMillis() / 1000 % 10000);
        int threads = 16;
        long durationMs = 300000; // 默认 5 分钟
        int skewCount = 1000000;  // 倾斜数据量
        long flipInterval = 60000; // 两次跳变的间隔

        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "-table": tableName = args[++i]; break;
                case "-threads": threads = Integer.parseInt(args[++i]); break;
                case "-duration": durationMs = Long.parseLong(args[++i]); break;
                case "-count": skewCount = Integer.parseInt(args[++i]); break;
                case "-interval": flipInterval = Long.parseLong(args[++i]); break;
            }
        }

        System.out.println("[画像构造] 引擎: " + this.dbType + " | 沙盒表: " + tableName + " | 并发: " + threads);
        runPlanOscillation(tableName, threads, durationMs, skewCount, flipInterval);
    }

    private void runPlanOscillation(String tableName, int threads, long durationMs, int skewCount, long interval) throws Exception {
        try (Connection initConn = getConnection()) {
            setupEnvironment(initConn, tableName);

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            long endTime = System.currentTimeMillis() + durationMs;
            
            // 启动负载模拟模块：背景并发查询
            startQueryLoad(executor, tableName, endTime);

            // 机制触发循环：开始震荡计划
            int cycle = 0;
            while (System.currentTimeMillis() < endTime) {
                cycle++;
                System.out.println("\n--- 第 " + cycle + " 轮计划跳变循环 ---");
                
                // 1. 注入倾斜数据诱导跳变
                injectSkewedPhase(initConn, tableName, skewCount);
                
                // 2. 观察期：监控延迟和内核统计刷新
                monitorPhase(initConn, tableName, interval / 2);

                // 3. 恢复数据分布
                restoreBalancedPhase(initConn, tableName);
                
                // 4. 稳定期：等待计划回归
                monitorPhase(initConn, tableName, interval / 2);
            }

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } finally {
            cleanup(tableName);
        }
    }

    private void setupEnvironment(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute("CREATE TABLE " + tableName + " (id int, val varchar(64), info text)");
            stmt.execute("CREATE INDEX idx_" + tableName + " ON " + tableName + " (val)");
            stmt.execute(String.format(setAutoStatsSql, tableName));
        }
    }

    private void injectSkewedPhase(Connection conn, String tableName, int count) throws SQLException {
        System.out.println("[" + getNow() + "] 触发机制: 注入大量倾斜数据以诱导 Seq Scan...");
        insertData(conn, tableName, "SKEW_A", count);
    }

    private void restoreBalancedPhase(Connection conn, String tableName) throws SQLException {
        System.out.println("[" + getNow() + "] 触发机制: 清理倾斜数据以诱导计划回归 Index Scan...");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE " + tableName);
        }
        insertData(conn, tableName, "BALANCED_B", 1000);
    }

    private void monitorPhase(Connection conn, String tableName, long monitorTime) throws Exception {
        long phaseEnd = System.currentTimeMillis() + monitorTime;
        while (System.currentTimeMillis() < phaseEnd) {
            long avgLat = getAverageLatency();
            String lastAuto = getKernelLastAnalyze(conn, tableName);
            System.out.println("[" + getNow() + "] 当前平均延迟: " + avgLat + " ns | 内核自动分析记录: " + lastAuto);
            
            Thread.sleep(5000);
            resetStats();
        }
    }

    private void startQueryLoad(ExecutorService executor, String tableName, long endTime) {
        String sql = "SELECT count(*) FROM " + tableName + " WHERE val = 'SKEW_A'";
        for (int i = 0; i < 16; i++) { // 固定内部并发数或根据 threads 调整
            executor.execute(() -> {
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    while (System.currentTimeMillis() < endTime) {
                        long s = System.nanoTime();
                        try (ResultSet rs = pstmt.executeQuery()) { if (rs.next()) rs.getInt(1); }
                        totalLatency.addAndGet(System.nanoTime() - s);
                        queryCount.incrementAndGet();
                    }
                } catch (SQLException ignored) {}
            });
        }
    }

    private String getKernelLastAnalyze(Connection conn, String tableName) {
        try (PreparedStatement ps = conn.prepareStatement(checkStatsSql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object val = rs.getObject(1);
                    return (val == null) ? "None" : val.toString().split("\\.")[0];
                }
            }
        } catch (SQLException e) { return "Error"; }
        return "Unknown";
    }

    private void insertData(Connection conn, String table, String val, int n) throws SQLException {
        conn.setAutoCommit(false);
        String sql = "INSERT INTO " + table + " VALUES (?, ?, 'chaos_resilience_payload')";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < n; i++) {
                pstmt.setInt(1, i);
                pstmt.setString(2, val);
                pstmt.addBatch();
                if (i % 20000 == 0) pstmt.executeBatch();
            }
            pstmt.executeBatch();
            conn.commit();
        }
        conn.setAutoCommit(true);
    }

    private String getNow() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private long getAverageLatency() {
        long c = queryCount.get();
        return (c == 0) ? 0 : totalLatency.get() / c;
    }

    private void resetStats() {
        totalLatency.set(0);
        queryCount.set(0);
    }

    private void cleanup(String tableName) {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
        } catch (SQLException ignored) {}
    }
}