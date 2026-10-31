package chaos.inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import chaos.core.BaseFaultInject;
import chaos.core.PreflightChecks;

public class MemoryPressureFault extends BaseFaultInject {
    @Override
    public void reportTest() {
        printTestReport();
    }

    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final String tableName = "chaos_memory_stress";

    public MemoryPressureFault(String dbType) {
        super(dbType, "memory");
    }

    @Override
    protected void preflight(String[] args) throws Exception {
        preflightLong(args, "-duration", 0);
        int batch = preflightInt(args, "-batch", 100);
        int threads = preflightInt(args, "-threads", 4);
        long bytes = batch * 1024L * 1024L;
        PreflightChecks.require(bytes <= Integer.MAX_VALUE - 8, "-batch 超出 Java 数组上限");
        Runtime runtime = Runtime.getRuntime();
        long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        PreflightChecks.require(bytes < available / (2L + threads),
                "JVM 剩余堆不足以容纳载荷及各并发驱动缓冲，请增大 -Xmx 或降低 -batch/-threads");
        try (PreflightChecks checks = new PreflightChecks(getConnection(), getStandardDbType())) {
            checks.connections(threads);
            checks.packet(bytes);
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        runPreflight(args);

        String durationStr = getArg(args, "-duration");
        String batchStr = getArg(args, "-batch");
        String threadsStr = getArg(args, "-threads");

        if (durationStr == null) {
            System.err.println(RED + " ✘ 错误：缺失必填参数 -duration (ms)" + RESET);
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);
        int batchSizeMB = (batchStr != null) ? Integer.parseInt(batchStr) : 100;
        int threads = (threadsStr != null) ? Integer.parseInt(threadsStr) : 4;

        System.out.println(CYAN + " >>> " + RESET + BOLD + "开始内存压力注入: " + RESET + YELLOW + "Buffer Pool 挤兑" + RESET);
        System.out.println("   批大小: " + batchSizeMB + " MB | 并发线程: " + threads + " | 时长: " + durationMs + " ms");
    
    
        try {
            runMemoryStress(durationMs, batchSizeMB, threads);
        } finally {
            cleanup();
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n" + BOLD + "不利 Case 用法: " + YELLOW + "buffer memory_pressure" + RESET);
        System.out.println("  通过高频插入超大 BLOB 数据抢占 Buffer Pool 资源，观察业务查询的延迟抖动。");
        System.out.println("\n" + BOLD + "参数列表:" + RESET);
        System.out.printf("  %-15s %s\n", "-duration", "必填。故障持续时长 (ms)");
        System.out.printf("  %-15s %s\n", "-batch", "选填。单次插入数据大小 (MB, 默认 100)");
        System.out.printf("  %-15s %s\n", "-threads", "选填。注入并发线程数 (默认 4)");
        System.out.println("\n" + BOLD + "示例:" + RESET);
        System.out.println(CYAN + "  java -jar DBChaos-0.0.1.jar --db opengauss buffer memory_pressure -duration 60000 -batch 50 -threads 8" + RESET);
    }

    private void runMemoryStress(long durationMs, int batchSizeMB, int threads) throws SQLException {
        // 环境准备：创建表
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            String sqlType = getStandardDbType();
            
            stmt.execute("DROP TABLE IF EXISTS " + tableName);

            if ("postgresql".equals(sqlType)) {
                stmt.execute("CREATE TABLE " + tableName + "(id SERIAL, val BYTEA)");
            } else if ("mysql".equals(sqlType)) {
                stmt.execute("CREATE TABLE " + tableName + "(id INT AUTO_INCREMENT PRIMARY KEY, val LONGBLOB)");
            } else {
                stmt.execute("CREATE TABLE " + tableName + "(id INT PRIMARY KEY, val BLOB)");
            }
        } catch (SQLException e) {
            recordTestError(e);
            System.err.println(RED + "✘ 环境初始化失败: " + e.getMessage() + RESET);
            throw e;
        }

        // 构造压力数据
        byte[] payload = new byte[batchSizeMB * 1024 * 1024];
        java.util.Arrays.fill(payload, (byte) 'X');

        long endTimeMs = System.currentTimeMillis() + durationMs;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final int tid = i;
            executor.execute(() -> {
                String sql = "INSERT INTO " + tableName + "(val) VALUES(?)";
                while (System.currentTimeMillis() < endTimeMs) {
                    try (Connection conn = getConnection(); 
                         PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setBytes(1, payload);
                        pstmt.executeUpdate();
                        // 插入成功后不输出太多日志，避免控制台 I/O 干扰注入性能
                    } catch (SQLException e) {
                        recordTestError(e);
                        // 异常已记录到测试报告，继续观察压力期间的行为。
                    }
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        System.out.println(BOLD + " >>> 内存压力注入阶段结束。" + RESET);
    }

    private void cleanup() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            System.out.println(CYAN + " ➤ 正在清理测试数据..." + RESET);
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
        } catch (SQLException e) {
            recordTestError(e);
            System.err.println(RED + "✘ 清理失败: " + e.getMessage() + RESET);
        }
    }
}
