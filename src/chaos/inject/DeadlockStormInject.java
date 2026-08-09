package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交叉锁风暴故障注入。
 * 主目标不是单纯制造锁等待，而是放大死锁检测器和锁管理器的负载。
 */
public class DeadlockStormInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private static final String IDENTIFIER_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$";

    private String setTimeoutSql = "";
    private final List<Connection> anchorConnections = new ArrayList<Connection>();

    private final AtomicInteger successfulLoops = new AtomicInteger(0);
    private final AtomicInteger timeoutCount = new AtomicInteger(0);
    private final AtomicInteger deadlockCount = new AtomicInteger(0);
    private final AtomicInteger otherErrorCount = new AtomicInteger(0);

    public DeadlockStormInject(String dbType) {
        super(dbType, "DEADLOCK_STORM");
        initSqlTemplates();
    }

    private void initSqlTemplates() {
        if ("postgresql".equals(getStandardDbType())) {
            this.setTimeoutSql = "SET statement_timeout = '%ds'";
        } else if ("mysql".equals(getStandardDbType())) {
            this.setTimeoutSql = "SET max_execution_time = %d";
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        String tableName = getArg(args, "-table");
        if (tableName == null) {
            tableName = "bmsql_stock";
        }
        validateTableName(tableName);

        String durationStr = getArg(args, "-duration");
        if (durationStr == null) {
            System.err.println(RED + " 参数错误: 缺失必填参数 -duration (ms)" + RESET);
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);
        int anchors = parseIntArg(args, "-anchors", 2);
        int anchorRows = parseIntArg(args, "-anchor-rows", 20);
        int waiters = parseIntArg(args, "-waiters", 16);
        int hotRows = parseIntArg(args, "-hot-rows", 32);
        int stepDelayMs = parseIntArg(args, "-step-delay", 50);
        int statementTimeoutSec = parseIntArg(args, "-statement-timeout", 5);

        if (hotRows < 4) {
            throw new IllegalArgumentException("-hot-rows 至少为 4，才能形成交叉锁请求");
        }

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动交叉锁风暴注入" + RESET);
        System.out.println("   目标表: " + tableName);
        System.out.println("   锚点事务: " + anchors + " | 每锚点锁定行数: " + anchorRows);
        System.out.println("   交叉等待线程: " + waiters + " | 热点行数: " + hotRows + " | 持续时间: " + durationMs + " ms");

        final String finalTableName = tableName;
        final int finalAnchorRows = anchorRows;
        final int finalHotRows = hotRows;
        final int finalStepDelayMs = stepDelayMs;
        final int finalStatementTimeoutSec = statementTimeoutSec;
        final long endTimeMs = System.currentTimeMillis() + durationMs;

        ExecutorService anchorPool = null;
        ExecutorService waiterPool = null;

        try {
            anchorPool = Executors.newFixedThreadPool(Math.max(1, anchors));
            for (int i = 0; i < anchors; i++) {
                final int anchorId = i;
                anchorPool.execute(() -> openAnchorTransaction(finalTableName, anchorId, finalAnchorRows));
            }

            Thread.sleep(800);

            waiterPool = Executors.newFixedThreadPool(Math.max(2, waiters));
            for (int i = 0; i < waiters; i++) {
                final int workerId = i;
                waiterPool.execute(() -> runCrossLockWorker(finalTableName, workerId, finalHotRows, finalStepDelayMs, finalStatementTimeoutSec, endTimeMs));
            }

            while (System.currentTimeMillis() < endTimeMs) {
                Thread.sleep(5000);
                printLiveStats();
            }
        } finally {
            releaseAnchors();
            if (anchorPool != null) {
                anchorPool.shutdownNow();
                anchorPool.awaitTermination(5, TimeUnit.SECONDS);
            }
            if (waiterPool != null) {
                waiterPool.shutdownNow();
                waiterPool.awaitTermination(5, TimeUnit.SECONDS);
            }
        }

        printSummary();
    }

    @Override
    public void printHelp() {
        System.out.println("\n" + BOLD + "不利 Case 用法: " + YELLOW + "txn deadlock_storm" + RESET);
        System.out.println("  通过长事务锚点和交叉乱序加锁请求，诱导死锁检测器过载与高频回滚。");
        System.out.println("\n" + BOLD + "参数列表:" + RESET);
        System.out.printf("  %-18s %s\n", "-duration", "必填。故障总时长 (ms)");
        System.out.printf("  %-18s %s\n", "-table", "选填。目标表名，默认 bmsql_stock");
        System.out.printf("  %-18s %s\n", "-anchors", "选填。长事务锚点数 (默认 2)");
        System.out.printf("  %-18s %s\n", "-anchor-rows", "选填。每个锚点锁定的行数 (默认 20)");
        System.out.printf("  %-18s %s\n", "-waiters", "选填。交叉等待线程数 (默认 16)");
        System.out.printf("  %-18s %s\n", "-hot-rows", "选填。用于制造交叉冲突的热点行数 (默认 32)");
        System.out.printf("  %-18s %s\n", "-step-delay", "选填。两次取锁之间的延迟 ms (默认 50)");
        System.out.printf("  %-18s %s\n", "-statement-timeout", "选填。语句超时秒数 (默认 5)");
        System.out.println("\n" + BOLD + "示例:" + RESET);
        System.out.println(CYAN + "  java -jar DBChaos-0.0.1.jar --db opengauss txn deadlock_storm -duration 60000 -table bmsql_stock -anchors 2 -anchor-rows 20 -waiters 16 -hot-rows 32" + RESET);
    }

    private void openAnchorTransaction(String tableName, int anchorId, int rowCount) {
        try {
            Connection conn = getConnection();
            conn.setAutoCommit(false);
            synchronized (anchorConnections) {
                anchorConnections.add(conn);
            }

            int baseOffset = anchorId * rowCount;
            lockRows(conn, tableName, baseOffset, rowCount);
            System.out.println("[Anchor-" + anchorId + "] 已锁定 " + rowCount + " 行，事务保持中...");
        } catch (SQLException e) {
            System.err.println("[Anchor-" + anchorId + "] 锚点事务建立失败: " + e.getMessage());
        }
    }

    private void lockRows(Connection conn, String tableName, int offset, int rowCount) throws SQLException {
        String sql = "SELECT * FROM " + tableName + " ORDER BY 1 LIMIT ? OFFSET ? FOR UPDATE";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, rowCount);
            pstmt.setInt(2, offset);
            pstmt.executeQuery();
        }
    }

    private void runCrossLockWorker(String tableName, int workerId, int hotRows, int stepDelayMs, int statementTimeoutSec, long endTimeMs) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (System.currentTimeMillis() < endTimeMs && !Thread.currentThread().isInterrupted()) {
            int firstOffset = random.nextInt(hotRows);
            int secondOffset = (firstOffset + 1 + random.nextInt(Math.max(1, hotRows - 1))) % hotRows;

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                setStatementTimeout(conn, statementTimeoutSec);

                lockOneRow(conn, tableName, firstOffset);
                sleepQuietly(stepDelayMs);
                lockOneRow(conn, tableName, secondOffset);

                conn.rollback();
                successfulLoops.incrementAndGet();
            } catch (SQLException e) {
                classifyException(e);
            }
        }
    }

    private void setStatementTimeout(Connection conn, int timeoutSec) throws SQLException {
        if (timeoutSec <= 0 || setTimeoutSql.isEmpty()) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            String sql = "postgresql".equals(getStandardDbType())
                    ? String.format(setTimeoutSql, timeoutSec)
                    : String.format(setTimeoutSql, timeoutSec * 1000);
            stmt.execute(sql);
        }
    }

    private void lockOneRow(Connection conn, String tableName, int offset) throws SQLException {
        String sql = "SELECT * FROM " + tableName + " ORDER BY 1 LIMIT 1 OFFSET ? FOR UPDATE";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, offset);
            pstmt.executeQuery();
        }
    }

    private void classifyException(SQLException e) {
        String state = e.getSQLState();
        String msg = e.getMessage().toLowerCase();

        if ("40P01".equals(state) || msg.contains("deadlock")) {
            deadlockCount.incrementAndGet();
        } else if ("57014".equals(state) || msg.contains("timeout") || msg.contains("lock wait")) {
            timeoutCount.incrementAndGet();
        } else {
            otherErrorCount.incrementAndGet();
        }
    }

    private void printLiveStats() {
        System.out.println("[Live] success=" + successfulLoops.get()
                + " deadlock=" + deadlockCount.get()
                + " timeout=" + timeoutCount.get()
                + " other=" + otherErrorCount.get());
    }

    private void printSummary() {
        System.out.println("\n----------------- 交叉锁风暴 -----------------");
        System.out.println("成功完成轮次: " + successfulLoops.get());
        System.out.println("死锁次数    : " + deadlockCount.get());
        System.out.println("超时次数    : " + timeoutCount.get());
        System.out.println("其他异常    : " + otherErrorCount.get());
        System.out.println("----------------------------------------------");
    }

    private void releaseAnchors() {
        synchronized (anchorConnections) {
            for (Connection conn : anchorConnections) {
                try {
                    if (conn != null && !conn.isClosed()) {
                        conn.rollback();
                        conn.close();
                    }
                } catch (SQLException ignored) {}
            }
            anchorConnections.clear();
        }
    }

    private int parseIntArg(String[] args, String key, int defaultValue) {
        String raw = getArg(args, key);
        return raw == null ? defaultValue : Integer.parseInt(raw);
    }

    private void validateTableName(String tableName) {
        if (tableName == null || !tableName.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException("非法表名: " + tableName + "，请使用 schema.table 或 table 格式");
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
