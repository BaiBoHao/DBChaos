package chaos.inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import chaos.core.BaseFaultInject;

/**
 * 未提交事务（长事务锁冲突）故障注入实现。
 * 逻辑：Holder 线程对业务表执行 SELECT ... FOR UPDATE 进行纯加锁，不修改数据。
 * duration 到时统一回滚释放锁。
 */
public class UncommittedTxnInject extends BaseFaultInject {

    private final List<Connection> activeHolders = new ArrayList<>();
    private static final String IDENTIFIER_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$";

    public UncommittedTxnInject(String dbType) {
        super(dbType, "UNCOMMITTED_TXN");
        initSqlTemplates();
    }

    private void initSqlTemplates() {
        // 纯加锁模式不需要 SQL 模板初始化，保留方法以保持类结构。
    }

    @Override
    public void execute(String[] args) throws Exception {
        // 1. 使用临时变量进行参数解析
        int tmpHolders = 1;
        int tmpDuration = 60;
        int tmpRows = 5;
        String tableSpec = "bmsql_stock";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-holders": tmpHolders = Integer.parseInt(args[++i]); break;
                case "-duration": tmpDuration = Integer.parseInt(args[++i]); break;
                case "-rows": tmpRows = Integer.parseInt(args[++i]); break;
                case "-table": tableSpec = args[++i]; break;
                case "-tables": tableSpec = args[++i]; break;
                case "-waiters": i++; break;
                case "-timeout": i++; break;
            }
        }

        // 2. 【核心修复】定义为 final 变量，确保 Lambda 引用安全
        final int holders = tmpHolders;
        final int duration = tmpDuration;
        final int rows = tmpRows;
        final List<String> targetTables = parseAndValidateTableNames(tableSpec);

        if (holders <= 0 || duration <= 0 || rows <= 0) {
            throw new IllegalArgumentException("-holders/-duration/-rows 必须为正数");
        }

        System.out.println("[故障信息] 目标业务表: " + String.join(", ", targetTables) + " | 每线程每表锁定行数: " + rows);
        System.out.println("[配置信息] 持锁线程: " + holders + " | 持续时间: " + duration + "s");
        System.out.println("[提示] 当前模式为纯加锁，不执行任何 UPDATE/INSERT，也不启动 Waiter 线程。");

        ExecutorService holderPool = null;

        try {
            // 启动持锁线程，使用 SELECT ... FOR UPDATE 对业务表加锁。
            holderPool = Executors.newFixedThreadPool(holders);
            for (int i = 0; i < holders; i++) {
                final int id = i;
                holderPool.execute(() -> startHolder(targetTables, id, rows));
            }

            // 持锁指定时长，到时统一回滚释放锁。
            Thread.sleep(duration * 1000L);
            System.out.println("[控制信息] 持锁时间到，开始回滚并释放 Holder 锁...");
            releaseHolders();

            // 等待 Holder 线程退出。
            holderPool.shutdown();
            holderPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (holderPool != null && !holderPool.isShutdown()) {
                holderPool.shutdownNow();
            }
            releaseHolders();
        }
    }

    private void startHolder(List<String> tableNames, int id, int rows) {
        try {
            Connection conn = getConnection();
            conn.setAutoCommit(false); // 关键：关闭自动提交以持有锁
            synchronized (activeHolders) { activeHolders.add(conn); }

            int offset = id * rows;
            for (String tableName : tableNames) {
                String sql = "SELECT * FROM " + tableName + " ORDER BY 1 LIMIT ? OFFSET ? FOR UPDATE";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, rows);
                    pstmt.setInt(2, offset);
                    pstmt.executeQuery();
                    System.out.println("[Holder-" + id + "] 表 " + tableName + " 成功锁定 " + rows + " 行，事务保持中...");
                }
            }
        } catch (SQLException e) {
            System.err.println("[Holder-" + id + "] 获取锁失败: " + e.getMessage());
        }
    }

    private List<String> parseAndValidateTableNames(String tableSpec) {
        if (tableSpec == null || tableSpec.trim().isEmpty()) {
            throw new IllegalArgumentException("请通过 -table 或 -tables 指定目标业务表");
        }

        String[] parts = tableSpec.split(",");
        List<String> tableNames = new ArrayList<>();
        for (String part : parts) {
            String tableName = part.trim();
            if (tableName.isEmpty() || !tableName.matches(IDENTIFIER_PATTERN)) {
                throw new IllegalArgumentException("非法表名: " + part + "，请使用 schema.table 或 table 格式");
            }
            tableNames.add(tableName);
        }

        if (tableNames.isEmpty()) {
            throw new IllegalArgumentException("请至少指定一个目标表");
        }
        return tableNames;
    }

    private void releaseHolders() {
        synchronized (activeHolders) {
            for (Connection conn : activeHolders) {
                try {
                    if (conn != null && !conn.isClosed()) {
                        conn.rollback();
                        conn.close();
                    }
                } catch (SQLException ignored) {}
            }
            activeHolders.clear();
        }
    }

}