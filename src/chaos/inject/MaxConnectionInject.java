package chaos.inject;

import chaos.core.BaseFaultInject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 数据库连接及线程池相关故障注入实现。
 * 支持模式：
 * 1. thread_saturation (线程池饱和)
 * 2. conn_exhaustion (连接数耗尽)
 * 3. conn_storm (连接风暴)
 */
public class MaxConnectionInject extends BaseFaultInject {

    private int dbMaxConnections = 0;
    private int dbMaxThreads = 0;

    // ************** SQL 语句配置 ***************
    private String sleepSql = "";
    private String configSql = "";
    private final String heartbeatSql = "SELECT 1"; // 通用查询


    public MaxConnectionInject(String dbType) {
        super(dbType, "MAX_CONNECTION");
        // 根据数据库类型初始化特定的 SQL 模板
        initSqlTemplates();
    }

    // 根据数据库类型初始化 SQL 模板，确保兼容 PostgreSQL 和 MySQL/OceanBase
    private void initSqlTemplates() {
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            // PostgreSQL 风格
            this.configSql = "SHOW max_connections";
            this.sleepSql = "SELECT pg_sleep(%d)"; 
        } else if ("mysql".equals(sqlType)) {
            // MySQL 风格
            this.configSql = "SHOW VARIABLES LIKE 'max_connections'";
            this.sleepSql = "SELECT SLEEP(%d)";
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        // 参数自检
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        // 获取参数
        String mode = getArg(args, "-mode");
        String durationStr = getArg(args, "-duration");
        String countStr = getArg(args, "-count");

        if (mode == null || durationStr == null) {
            System.err.println("\u001B[31m ✘ 错误：缺失必填参数 -mode 或 -duration\u001B[0m");
            printHelp();
            return;
        }

        // 参数解析
        long durationMs;
        int count;
        try {
            durationMs = Long.parseLong(durationStr);
            count = (countStr != null) ? Integer.parseInt(countStr) : 0;
        } catch (NumberFormatException e) {
            System.err.println("\u001B[31m ✘ 错误：-duration 或 -count 格式非法，请输入数字\u001B[0m");
            return;
        }

        // 环境探测：获取数据库最大连接数和线程池配置
        detectDatabaseConfig();
        System.out.println("【环境探测】 最大连接数: " + dbMaxConnections + " | 线程池上限: " + dbMaxThreads);

        // 根据模式路由
        switch (mode.toLowerCase()) {
            case "thread_saturation":
                int overflow = (count > 0) ? count : 32;
                System.out.println(">>> 执行模式: 线程饱和 | 溢出数: " + overflow+ " | 时长: " + durationMs + "ms");
                injectThreadPoolSaturation(overflow, durationMs);
                break;
            case "conn_exhaustion":
                int target = (count > 0) ? count : (dbMaxConnections > 0 ? dbMaxConnections : 200);
                System.out.println(">>> 执行模式: 连接耗尽 | 目标连接: " + target+ " | 时长: " + durationMs + "ms");
                injectConnectionExhaustion(target, durationMs);
                break;
            case "conn_storm":
                int stormThreads = (count > 0) ? count : (dbMaxConnections > 0 ? dbMaxConnections : 200);
                System.out.println(">>> 执行模式: 连接风暴 | 并发线程: " + stormThreads+ " | 时长: " + durationMs + "ms");
                injectConnectionStorm(stormThreads, durationMs);
                break;
            default:
                System.err.println("\u001B[31m ✘ 错误：未知的模式 '" + mode + "'\u001B[0m");
                System.out.println(" 可选模式: thread_saturation, conn_exhaustion, conn_storm");
                break;
        }
    }

    // 专门为该画像准备的精简说明书
    @Override
    public void printHelp() {
        System.out.println("\n\u001B[1m故障画像用法: \u001B[33mmax_connection\u001B[0m");
        System.out.println("  该故障用于模拟连接挤兑。");
        System.out.println("\n\u001B[1m参数列表:\u001B[0m");
        System.out.printf("  %-15s %s\n", "-mode", "必填。可选：thread_saturation, conn_exhaustion, conn_storm");
        System.out.printf("  %-15s %s\n", "-duration", "必填。故障持续时长 (ms)");
        System.out.printf("  %-15s %s\n", "-count", "选填。根据模式不同代表溢出线程数或并发数");
        System.out.println("\n\u001B[1m示例:\u001B[0m");
        System.out.println("  ... max_connection -mode conn_storm -count 200 -duration 60000");
    }

    private void detectDatabaseConfig() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(configSql);
            if (rs.next()) {
                // PostgreSQL 返回单列，MySQL 返回两列 (Variable_name, Value)
                dbMaxConnections = "mysql".equals(getStandardDbType()) ? rs.getInt(2) : rs.getInt(1);
            }

            if ("postgresql".equals(getStandardDbType())) {
                try {
                    rs = stmt.executeQuery("SHOW thread_pool_attr");
                    if (rs.next()) {
                        parseOpenGaussThreadPool(rs.getString(1));
                    }
                } catch (SQLException e) {
                    // 部分PG库没有线程池，忽略此错误
                }
            } 
        } catch (SQLException e) {
            System.err.println("数据库配置探测失败: " + e.getMessage());
        }
    }

    /*
     * 解析 OpenGauss 的 thread_pool_attr 配置字符串，计算线程池上限
     * "group=4, per_group=8" 表示 4 个线程组，每组 8 个线程，总上限为 32
     */
    private void parseOpenGaussThreadPool(String attr) {
        if (attr == null) return;
        String cleanAttr = attr.replace("\"", "").trim();
        int group = 0, perGroup = 0;
        String[] parts = cleanAttr.split(",");
        for (String part : parts) {
            if (part.trim().contains("group=")) {
                group = Integer.parseInt(part.split("=")[1].trim());
            } else if (part.trim().contains("per_group=")) {
                perGroup = Integer.parseInt(part.split("=")[1].trim());
            }
        }
        dbMaxThreads = group * perGroup;
    }

    private void injectThreadPoolSaturation(int overflowAmount, long durationMs) {
        int safeMaxThreads = (dbMaxThreads > 0) ? dbMaxThreads : 64;
        int targetThreads = Math.min(safeMaxThreads + overflowAmount, (dbMaxConnections > 2 ? dbMaxConnections - 2 : 10));
        int sleepSec = (int) (durationMs / 1000);

        // sleepSql 根据数据库类型自动适配，PostgreSQL 使用 pg_sleep，MySQL/OceanBase 使用 SLEEP
        final String currentSleepSql = String.format(sleepSql, sleepSec);

        ExecutorService executor = Executors.newFixedThreadPool(targetThreads);
        for (int i = 0; i < targetThreads; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                        stmt.execute(currentSleepSql);
                    } catch (Exception ignored) {}
                }
            });
        }
        executor.shutdown();
        try { executor.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
    }

    private void injectConnectionExhaustion(int targetConnections, long durationMs) {
        final List<Connection> connectionPool = Collections.synchronizedList(new ArrayList<Connection>());
        int connectorThreads = 100;
        ExecutorService connectorExecutor = Executors.newFixedThreadPool(connectorThreads);

        for (int i = 0; i < targetConnections; i++) {
            connectorExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Connection conn = getConnection();
                        conn.setAutoCommit(true);
                        connectionPool.add(conn);
                    } catch (Exception e) {}
                }
            });
        }
        connectorExecutor.shutdown();
        try { connectorExecutor.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        System.out.println("连接建立完毕，实际持有连接: " + connectionPool.size());
        final long endTime = System.currentTimeMillis() + durationMs;
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, connectionPool.size()));

        for (int i = 0; i < connectionPool.size(); i++) {
            final Connection conn = connectionPool.get(i);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (System.currentTimeMillis() < endTime) {
                            try (Statement stmt = conn.createStatement()) {
                                stmt.execute(heartbeatSql);
                            } catch (SQLException ignored) {
                                Thread.sleep(100);
                            }
                        }
                    } catch (InterruptedException ignored) {}
                }
            });
        }
        executor.shutdown();
        try { executor.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

        for (Connection conn : connectionPool) {
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    private void injectConnectionStorm(int threadNum, final long durationMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadNum);
        final long endTime = System.currentTimeMillis() + durationMs;

        for (int i = 0; i < threadNum; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        latch.await();
                        while (System.currentTimeMillis() < endTime) {
                            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                                stmt.execute(heartbeatSql);
                            } catch (SQLException ignored) {
                                Thread.sleep(10);
                            }
                        }
                    } catch (InterruptedException ignored) {}
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        try { executor.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
    }
}