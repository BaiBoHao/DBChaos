package chaos.inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import chaos.core.BaseFaultInject;

/**
 * 未提交事务（长事务锁冲突）故障注入实现。
 * 逻辑：由 Holder 线程获取行级锁后保持不提交，使 Waiter 线程进入锁定等待状态。
 * 支持 PostgreSQL (openGauss) 与 MySQL 语系的泛化处理。
 */
public class UncommittedTxnInject extends BaseFaultInject {

    private String setTimeoutSql;
    private String createTableSql;
    private final List<Connection> activeHolders = new ArrayList<>();

    public UncommittedTxnInject(String dbType) {
        super(dbType, "UNCOMMITTED_TXN");
        initSqlTemplates();
    }

    private void initSqlTemplates() {
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            // PostgreSQL/openGauss 风格
            this.setTimeoutSql = "SET statement_timeout = '%ds'";
            this.createTableSql = "CREATE UNLOGGED TABLE %s (id SERIAL PRIMARY KEY, value INT)";
        } else if ("mysql".equals(sqlType)) {
            // MySQL 风格 (max_execution_time 单位为毫秒)
            this.setTimeoutSql = "SET max_execution_time = %d";
            this.createTableSql = "CREATE TABLE %s (id INT AUTO_INCREMENT PRIMARY KEY, value INT) ENGINE=InnoDB";
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        // 1. 使用临时变量进行参数解析
        int tmpHolders = 1;
        int tmpWaiters = 10;
        int tmpDuration = 60;
        int tmpRows = 5;
        int tmpTimeout = 30;
        String tableName = "fault_inject.chaos_lock_test";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-holders": tmpHolders = Integer.parseInt(args[++i]); break;
                case "-waiters": tmpWaiters = Integer.parseInt(args[++i]); break;
                case "-duration": tmpDuration = Integer.parseInt(args[++i]); break;
                case "-rows": tmpRows = Integer.parseInt(args[++i]); break;
                case "-timeout": tmpTimeout = Integer.parseInt(args[++i]); break;
            }
        }

        // 2. 【核心修复】定义为 final 变量，确保 Lambda 引用安全
        final int holders = tmpHolders;
        final int waiters = tmpWaiters;
        final int duration = tmpDuration;
        final int rows = tmpRows;
        final int timeout = tmpTimeout;

        System.out.println("[故障信息] 目标表: " + tableName + " | 受影响行数: " + rows);
        System.out.println("[配置信息] 持锁线程: " + holders + " | 等待线程: " + waiters + " | 持续时间: " + duration + "s");

        try {
            // 1. 初始化沙盒环境
            prepareEnvironment(tableName, rows);

            // 2. 启动持锁线程 (Holders)
            ExecutorService holderPool = Executors.newFixedThreadPool(holders);
            for (int i = 0; i < holders; i++) {
                final int id = i;
                // 此时引用的是上面定义的 final rows
                holderPool.execute(() -> startHolder(tableName, id, rows));
            }

            Thread.sleep(2000);

            // 3. 启动等待线程 (Waiters)
            ExecutorService waiterPool = Executors.newFixedThreadPool(waiters);
            for (int i = 0; i < waiters; i++) {
                final int id = i;
                // 此时引用的是上面定义的 final rows 和 timeout
                waiterPool.execute(() -> startWaiter(tableName, id, rows, timeout));
            }

            // ... 后续逻辑保持不变
        } finally {
            cleanup(tableName);
        }
    }

    private void prepareEnvironment(String tableName, int rows) throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            if ("postgresql".equals(getStandardDbType())) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS fault_inject");
            }
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute(String.format(createTableSql, tableName));

            // 预填充数据
            conn.setAutoCommit(false);
            String insertSql = "INSERT INTO " + tableName + " (value) VALUES (0)";
            for (int i = 0; i < rows; i++) {
                stmt.addBatch(insertSql);
            }
            stmt.executeBatch();
            conn.commit();
        }
    }

    private void startHolder(String tableName, int id, int rows) {
        try {
            Connection conn = getConnection();
            conn.setAutoCommit(false); // 关键：关闭自动提交以持有锁
            synchronized (activeHolders) { activeHolders.add(conn); }

            String sql = "UPDATE " + tableName + " SET value = value + 1 WHERE id <= ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, rows);
                int count = pstmt.executeUpdate();
                System.out.println("[Holder-" + id + "] 成功锁定 " + count + " 行，事务保持中...");
            }
        } catch (SQLException e) {
            System.err.println("[Holder-" + id + "] 获取锁失败: " + e.getMessage());
        }
    }

    private void startWaiter(String tableName, int id, int rows, int timeout) {
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                // 设置语句超时，防止线程无限期挂死
                String sqlTimeout = "postgresql".equals(getStandardDbType()) 
                    ? String.format(setTimeoutSql, timeout) 
                    : String.format(setTimeoutSql, timeout * 1000);
                stmt.execute(sqlTimeout);

                String sqlUpdate = "UPDATE " + tableName + " SET value = value + 1 WHERE id <= " + rows;
                System.out.println("[Waiter-" + id + "] 尝试获取锁...");
                stmt.executeUpdate(sqlUpdate);
            }
        } catch (SQLException e) {
            // 预期内的超时错误或锁冲突错误不打印堆栈
            System.out.println("[Waiter-" + id + "] 状态: " + e.getMessage());
        }
    }

    private void releaseHolders() {
        synchronized (activeHolders) {
            for (Connection conn : activeHolders) {
                try {
                    if (conn != null && !conn.isClosed()) {
                        conn.commit();
                        conn.close();
                    }
                } catch (SQLException ignored) {}
            }
            activeHolders.clear();
        }
    }

    private void cleanup(String tableName) {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
        } catch (SQLException ignored) {}
    }
}