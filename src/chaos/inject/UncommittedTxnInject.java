package chaos.inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import chaos.core.BaseFaultInject;

/**
 * 未提交事务（长事务锁冲突）故障注入实现。
 * 逻辑：Holder 线程对业务表执行 SELECT ... FOR UPDATE 进行纯加锁，不修改数据。
 * duration 到时统一回滚释放锁。
 */
public class UncommittedTxnInject extends BaseFaultInject {
    // ANSI 颜色定义
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final List<Connection> activeHolders = new ArrayList<>();
    private static final String IDENTIFIER_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$";

    public UncommittedTxnInject(String dbType) {
        super(dbType, "UNCOMMITTED_TXN");
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        // 获取参数
        String durationStr = getArg(args, "-duration");
        String tableSpec = getArg(args, "-table");
        if (tableSpec == null) tableSpec = getArg(args, "-tables");
        
        String holdersStr = getArg(args, "-holders");
        String rowsStr = getArg(args, "-rows");

        if (durationStr == null || tableSpec == null) {
            System.err.println(RED + " ✘ 错误：缺失必填参数 -duration 或 -table" + RESET);
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);
        int holders = (holdersStr != null) ? Integer.parseInt(holdersStr) : 2;
        int rows = (rowsStr != null) ? Integer.parseInt(rowsStr) : 500;
        final List<String> targetTables = parseAndValidateTableNames(tableSpec);


        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动长事务注入: " + RESET + YELLOW + "行级锁持有" + RESET);
        System.out.println("   目标表: " + String.join(", ", targetTables) + " | 锁定行数/线程: " + rows);
        System.out.println("   持锁线程: " + holders + " | 持续时间: " + durationMs + "ms");
        
        ExecutorService holderPool = null;

        try {
            // 启动持锁线程，使用 SELECT ... FOR UPDATE 对业务表加锁。
            holderPool = Executors.newFixedThreadPool(holders);
            for (int i = 0; i < holders; i++) {
                final int id = i;
                holderPool.execute(() -> startHolder(targetTables, id, rows));
            }

            // 持锁指定时长，到时统一回滚释放锁。
            Thread.sleep(durationMs);
            System.out.println(CYAN + "\n >>> 注入时间到，开始释放所有长事务..." + RESET);
            
        } finally {
            releaseHolders();
            if (holderPool != null) {
                holderPool.shutdownNow();
                holderPool.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n" + BOLD + "故障画像用法: " + YELLOW + "uncommitted_txn" + RESET);
        System.out.println("  模拟业务中未提交的长事务，通过对指定表记录加锁，制造锁冲突，测试系统的并发处理与韧性。");
        System.out.println("\n" + BOLD + "参数列表:" + RESET);
        System.out.printf("  %-15s %s\n", "-duration", "必填。故障持有锁的时长 (ms)");
        System.out.printf("  %-15s %s\n", "-table", "必填。目标业务表，多个表用逗号分隔");
        System.out.printf("  %-15s %s\n", "-holders", "选填。持锁并发线程数 (默认 2)");
        System.out.printf("  %-15s %s\n", "-rows", "选填。每个线程在每个表上锁定的行数 (默认 500)");
        System.out.println("\n" + BOLD + "示例:" + RESET);
        System.out.println(CYAN + "  ... uncommitted_txn -duration 60000 -table bmsql_stock -holders 2 -rows 500" + RESET);
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


    private List<String> parseAndValidateTableNames(String tableSpec) {
        String[] parts = tableSpec.split(",");
        List<String> tableNames = new ArrayList<>();
        for (String part : parts) {
            String tableName = part.trim();
            if (tableName.isEmpty() || !tableName.matches(IDENTIFIER_PATTERN)) {
                throw new IllegalArgumentException("非法表名: " + part + "，请使用 schema.table 或 table 格式");
            }
            tableNames.add(tableName);
        }
        return tableNames;
    }
}