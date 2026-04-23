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
 */
public class MassiveRollbackInject extends BaseFaultInject {

    private final AtomicLong clientCommits = new AtomicLong(0);
    private final AtomicLong clientRollbacks = new AtomicLong(0);
    
    private String dbNameSql = "";
    private String statsSql = "";
    private String targetDbName = "";
    private final String tableName = "chaos_rollback_heavy"; // 统一使用该表名

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
            this.statsSql = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_commit', 'Com_rollback')";
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        String durationStr = getArg(args, "-duration");
        String threadsStr = getArg(args, "-threads");
        String rateStr = getArg(args, "-rate");

        if (durationStr == null) {
            System.err.println("\u001B[31m ✘ 错误：缺失必填参数 -duration (ms)\u001B[0m");
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);
        int threads = (threadsStr != null) ? Integer.parseInt(threadsStr) : 16;
        double rollbackRate = (rateStr != null) ? Double.parseDouble(rateStr) : 0.7;

        detectTargetDbName();
        long[] initialStats = getDatabaseTransactionStats();

        // 使用 try-finally 确保无论注入是否成功，最后都会尝试清理环境
        try {
            runRollbackLoad(durationMs, threads, rollbackRate);
            
            System.out.println(">>> 注入结束，正在采集最终指标...");
            Thread.sleep(2000);
            long[] finalStats = getDatabaseTransactionStats();
            displayReport(initialStats, finalStats);
        } finally {
            cleanupEnvironment();
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n\u001B[1m故障画像用法: \u001B[33mmassive_rollback\u001B[0m");
        System.out.println("  该故障用于模拟高频事务回滚，压力点在于 Undo/Redo 日志写入及 Buffer Pool 换页频繁。");
        System.out.println("\n\u001B[1m参数列表:\u001B[0m");
        System.out.printf("  %-15s %s\n", "-duration", "必填。故障持续时长 (ms)");
        System.out.printf("  %-15s %s\n", "-threads", "选填。并发执行事务的线程数 (默认 16)");
        System.out.printf("  %-15s %s\n", "-rate", "选填。事务回滚概率 [0.0 - 1.0] (默认 0.7)");
        System.out.println("\n\u001B[1m示例:\u001B[0m");
        System.out.println("  ... massive_rollback -duration 60000 -threads 32 -rate 0.9");
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

    private void runRollbackLoad(long durationMs, int threads, double rollbackRate) {
        // 1. 前置环境准备
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            System.out.println(" ➤ 准备持久化表 [" + tableName + "]");
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute("CREATE TABLE " + tableName + " (id BIGINT, val FLOAT)");
        } catch (SQLException e) {
            System.err.println("✘ 环境准备失败: " + e.getMessage());
            return;
        }

        long endTimeMs = System.currentTimeMillis() + durationMs;
        System.out.println("[大规模回滚] 目标库: " + targetDbName + " | 线程: " + threads + " | 预期回滚率: " + (rollbackRate * 100) + "%");

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.execute(() -> {
                try (Connection conn = getConnection()) {
                    conn.setAutoCommit(false);
                    String insertSql = "INSERT INTO " + tableName + " VALUES (?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        while (System.currentTimeMillis() < endTimeMs) {
                            ps.setLong(1, System.nanoTime());
                            ps.setDouble(2, Math.random());
                            ps.executeUpdate();

                            if (Math.random() < rollbackRate) {
                                conn.rollback();
                                clientRollbacks.incrementAndGet();
                            } else {
                                conn.commit();
                                clientCommits.incrementAndGet();
                            }
                        }
                    }
                } catch (SQLException ignored) {
                    // 注入期间的连接中断通常是预期内的
                }
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException ignored) {}
    }

    private void cleanupEnvironment() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            System.out.println(" ➤ 清理环境：删除持久化表 [" + tableName + "]");
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
        } catch (SQLException e) {
            System.err.println("✘ 环境清理失败: " + e.getMessage());
        }
    }

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