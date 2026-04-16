package chaos.inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import chaos.core.BaseFaultInject;

/**
 * 大规模事务回滚故障注入。
 * 模拟高并发事务下频繁触发回滚的场景，用于观察数据库撤销日志压力及性能抖动。
 */
public class MassiveRollbackInject extends BaseFaultInject {

    private final AtomicLong clientCommits = new AtomicLong(0);
    private final AtomicLong clientRollbacks = new AtomicLong(0);
    
    private String dbNameSql = "";
    private String statsSql = "";
    private String targetDbName = "";
    private String loadTableName = "";

    public MassiveRollbackInject(String dbType) {
        super(dbType, "MASSIVE_ROLLBACK");
        initSqlTemplates();
    }

    private void initSqlTemplates() {
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            this.dbNameSql = "SELECT current_database()";
            this.statsSql = "SELECT xact_commit, xact_rollback FROM pg_stat_database WHERE datname = ?";
        } else if ("mysql".equals(sqlType)) {
            this.dbNameSql = "SELECT DATABASE()";
            // MySQL 通过全局状态变量获取 Com_commit 和 Com_rollback
            this.statsSql = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_commit', 'Com_rollback')";
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        int durationSec = 60;
        int threads = 16;
        int batchSize = 10;
        double rollbackRate = 0.7;

        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (i + 1 < args.length) {
                String val = args[i + 1];
                switch (key) {
                    case "-duration": durationSec = Integer.parseInt(val); i++; break;
                    case "-threads": threads = Integer.parseInt(val); i++; break;
                    case "-batchsize": batchSize = Integer.parseInt(val); i++; break;
                    case "-rate": rollbackRate = Double.parseDouble(val); i++; break;
                }
            }
        }

        // 1. 环境探测与初始指标采集
        detectTargetDbName();
        loadTableName = "chaos_rollback_load_" + (System.currentTimeMillis() / 1000);
        createPermanentLoadTable();
        long[] initialStats = getDatabaseTransactionStats();
        long endTimeMs = System.currentTimeMillis() + (durationSec * 1000L);

        System.out.println("[大规模回滚] 目标库: " + targetDbName + " | 压测表: " + loadTableName + " | 线程: " + threads + " | 每事务批量: " + batchSize + " | 预期回滚率: " + (rollbackRate * 100) + "%");

        try {
            // 2. 执行并发负载
            runRollbackLoad(endTimeMs, threads, rollbackRate, batchSize);

            // 3. 结果汇总
            System.out.println(">>> 注入结束，正在采集最终指标...");
            Thread.sleep(2000);
            long[] finalStats = getDatabaseTransactionStats();

            displayReport(initialStats, finalStats);
        } finally {
            dropPermanentLoadTable();
        }
    }

    private void detectTargetDbName() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(dbNameSql)) {
            if (rs.next()) targetDbName = rs.getString(1);
        } catch (SQLException e) {
            System.err.println("无法获取数据库名称: " + e.getMessage());
        }
    }

    private void createPermanentLoadTable() {
        String sqlType = getStandardDbType();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + loadTableName);
            if ("postgresql".equals(sqlType)) {
                stmt.execute("CREATE TABLE " + loadTableName + " (id BIGINT, worker_id INT, val DOUBLE PRECISION)");
            } else {
                stmt.execute("CREATE TABLE " + loadTableName + " (id BIGINT, worker_id INT, val DOUBLE)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("创建永久压测表失败: " + e.getMessage());
        }
    }

    private void dropPermanentLoadTable() {
        if (loadTableName == null || loadTableName.isEmpty()) return;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + loadTableName);
            System.out.println(">>> 已清理压测表: " + loadTableName);
        } catch (SQLException e) {
            System.err.println("清理压测表失败: " + e.getMessage());
        }
    }

    private void runRollbackLoad(long endTimeMs, int threads, double rollbackRate, int batchSize) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int workerId = i;
            executor.execute(() -> {
                try (Connection conn = getConnection()) {
                    conn.setAutoCommit(false);
                    String insertSql = "INSERT INTO " + loadTableName + " VALUES (?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        long k = 0;
                        while (System.currentTimeMillis() < endTimeMs) {
                            for (int batchIndex = 0; batchIndex < batchSize; batchIndex++) {
                                ps.setLong(1, k++);
                                ps.setInt(2, workerId);
                                ps.setDouble(3, Math.random());
                                ps.addBatch();
                            }
                            ps.executeBatch();

                            if (Math.random() < rollbackRate) {
                                conn.rollback();
                                clientRollbacks.incrementAndGet();
                            } else {
                                conn.commit();
                                clientCommits.incrementAndGet();
                            }
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("负载执行异常: " + e.getMessage());
                }
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException ignored) {}
    }

    /**
     * 通用事务指标获取方法
     * 返回数组: [提交数, 回滚数]
     */
    private long[] getDatabaseTransactionStats() {
        long[] stats = new long[]{0, 0};
        String sqlType = getStandardDbType();
        
        try (Connection conn = getConnection()) {
            if ("postgresql".equals(sqlType)) {
                try (PreparedStatement ps = conn.prepareStatement(statsSql)) {
                    ps.setString(1, targetDbName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            stats[0] = rs.getLong(1);
                            stats[1] = rs.getLong(2);
                        }
                    }
                }
            } else if ("mysql".equals(sqlType)) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(statsSql)) {
                    while (rs.next()) {
                        String varName = rs.getString(1);
                        if ("Com_commit".equalsIgnoreCase(varName)) stats[0] = rs.getLong(2);
                        else if ("Com_rollback".equalsIgnoreCase(varName)) stats[1] = rs.getLong(2);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("指标采集失败: " + e.getMessage());
        }
        return stats;
    }

    private void displayReport(long[] initial, long[] last) {
        long cCommits = clientCommits.get();
        long cRollbacks = clientRollbacks.get();
        long dbCommits = last[0] - initial[0];
        long dbRollbacks = last[1] - initial[1];

        System.out.println("\n----------------- 大量事务回滚 --------------------");
        System.out.format("客户端统计 -> 总事务: %-8d | 提交: %-8d | 回滚: %-8d\n", 
                (cCommits + cRollbacks), cCommits, cRollbacks);
        System.out.format("数据库统计 -> 总事务: %-8d | 提交: %-8d | 回滚: %-8d\n", 
                (dbCommits + dbRollbacks), dbCommits, dbRollbacks);
        
        if (dbRollbacks > 0 && (cCommits + cRollbacks) > 0) {
            double actualRate = (double) dbRollbacks / (dbCommits + dbRollbacks) * 100;
            System.out.format("系统观测到的实际回滚占比: %.2f%%\n", actualRate);
        }
        System.out.println("----------------------------------------------------");
    }
}