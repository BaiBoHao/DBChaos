package chaos.inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import chaos.core.BaseFaultInject;

/**
 * 事务重复执行与并发冲突故障注入。
 * 逻辑：模拟高并发场景下多个事务同时操作同一热点行，诱发锁等待、超时、死锁或唯一约束冲突。
 * 支持多种模式：UPDATE (行锁争抢) 和 INSERT (主键冲突)。
 */
public class DuplicateTxnInject extends BaseFaultInject {

    private static final String SCHEMA_NAME = "dup_txn_inject";

    private String setTimeoutSql;
    private String lockStatsSql;
    
    // 统计指标
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger timeoutCount = new AtomicInteger(0);
    private final AtomicInteger deadlockCount = new AtomicInteger(0);
    private final AtomicInteger conflictCount = new AtomicInteger(0);

    public DuplicateTxnInject(String dbType) {
        super(dbType, "DUPLICATE_TXN");
        initSqlTemplates();
    }

    private void initSqlTemplates() {
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            // PostgreSQL/openGauss 语法
            this.setTimeoutSql = "SET statement_timeout = '%ds'";
            this.lockStatsSql = "SELECT mode, count(*) FROM pg_locks WHERE relation::regclass::text LIKE '%%s%' GROUP BY mode";
        } else if ("mysql".equals(sqlType)) {
            // MySQL 语法 (超时单位为毫秒)
            this.setTimeoutSql = "SET max_execution_time = %d";
            this.lockStatsSql = "SELECT lock_mode, count(*) FROM information_schema.innodb_locks GROUP BY lock_mode";
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        // 1. 初始参数解析
        int tmpSessions = 50;
        int tmpDuration = 30;
        String tmpMode = "UPDATE"; // 可选：UPDATE, INSERT
        String tableName = SCHEMA_NAME + ".chaos_hot_row";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-sessions": tmpSessions = Integer.parseInt(args[++i]); break;
                case "-duration": tmpDuration = Integer.parseInt(args[++i]); break;
                case "-mode": tmpMode = args[++i].toUpperCase(); break;
            }
        }

        // 2. 转换为 final 变量以供 Lambda 引用
        final int sessions = tmpSessions;
        final int duration = tmpDuration;
        final String mode = tmpMode;
        final String finalTable = tableName;

        System.out.println("[故障信息] 模式: " + mode + " | 目标表: " + finalTable);
        System.out.println("[配置信息] 并发会话: " + sessions + " | 持续时间: " + duration + "s");

        try {
            // 3. 环境准备
            prepareEnvironment(finalTable, mode);

            // 4. 并发注入
            ExecutorService executor = Executors.newFixedThreadPool(sessions);
            long endTime = System.currentTimeMillis() + (duration * 1000L);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < sessions; i++) {
                final int sessionId = i;
                futures.add(executor.submit(() -> runTask(finalTable, mode, sessionId, endTime)));
            }

            // 5. 状态监测
            while (System.currentTimeMillis() < endTime) {
                printLockStats(finalTable);
                Thread.sleep(5000);
            }

            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // 6. 输出总结
            printSummary();

        } finally {
            cleanup(finalTable);
        }
    }

    private void prepareEnvironment(String tableName, String mode) throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            if ("postgresql".equals(getStandardDbType())) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA_NAME);
            }
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, value INT, info TEXT)");

            if ("UPDATE".equals(mode)) {
                stmt.execute("INSERT INTO " + tableName + " VALUES (1, 100, 'initial_data')");
            }
        }
    }

    private void runTask(String tableName, String mode, int sessionId, long endTime) {
        String sqlType = getStandardDbType();
        while (System.currentTimeMillis() < endTime && !Thread.currentThread().isInterrupted()) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    // 设置语句级超时
                    String timeoutCmd = "postgresql".equals(sqlType) 
                        ? String.format(setTimeoutSql, 10) 
                        : String.format(setTimeoutSql, 10000);
                    stmt.execute(timeoutCmd);

                    if ("UPDATE".equals(mode)) {
                        stmt.executeUpdate("UPDATE " + tableName + " SET value = value + 1 WHERE id = 1");
                    } else {
                        stmt.executeUpdate("INSERT INTO " + tableName + " VALUES (1, " + sessionId + ", 'conflict')");
                    }
                    conn.commit();
                    successCount.incrementAndGet();
                } catch (SQLException e) {
                    handleException(conn, e);
                }
            } catch (Exception e) {
                // 连接异常
            }
        }
    }

    private void handleException(Connection conn, SQLException e) {
        try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
        
        String state = e.getSQLState();
        String msg = e.getMessage().toLowerCase();

        if ("57014".equals(state) || msg.contains("timeout") || msg.contains("expired")) {
            timeoutCount.incrementAndGet();
        } else if (msg.contains("deadlock")) {
            deadlockCount.incrementAndGet();
        } else if (msg.contains("duplicate") || msg.contains("unique") || msg.contains("primary key")) {
            conflictCount.incrementAndGet();
        }
    }

    private void printLockStats(String tableName) {
        try (Connection conn = getConnection(); 
             PreparedStatement pstmt = conn.prepareStatement(String.format(lockStatsSql, tableName))) {
            try (ResultSet rs = pstmt.executeQuery()) {
                System.out.print("[" + System.currentTimeMillis() / 1000 + "] 实时锁监测: ");
                boolean hasData = false;
                while (rs.next()) {
                    System.out.print(rs.getString(1) + "=" + rs.getInt(2) + "  ");
                    hasData = true;
                }
                if (!hasData) System.out.print("无锁阻塞");
                System.out.println();
            }
        } catch (Exception ignored) {}
    }

    private void printSummary() {
        System.out.println("\n-------------------------------------------");
        System.out.println("故障注入结果总结:");
        System.out.println("  - 成功执行事务数: " + successCount.get());
        System.out.println("  - 锁超时次数: " + timeoutCount.get());
        System.out.println("  - 检测到死锁次数: " + deadlockCount.get());
        System.out.println("  - 主键/唯一约束冲突: " + conflictCount.get());
        System.out.println("-------------------------------------------");
    }

    private void cleanup(String tableName) {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
        } catch (SQLException ignored) {}
    }
}