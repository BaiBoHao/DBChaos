package chaos.inject;

import chaos.core.BaseFaultInject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MemoryPressureFault extends BaseFaultInject {

    public MemoryPressureFault(String dbType) {
        super(dbType, "memory");
    }

    @Override
    public void execute(String[] args) throws Exception {
        int targetMemMB = 1024; // 默认 1GB 目标 (仅作为参考，Java 端较难监控 DB 进程 RSS)
        int batchSizeMB = 100;
        int concurrency = 4;

        if (args.length > 0) targetMemMB = Integer.parseInt(args[0]);
        if (args.length > 1) batchSizeMB = Integer.parseInt(args[1]);
        if (args.length > 2) concurrency = Integer.parseInt(args[2]);

        final int finalBatchSizeMB = batchSizeMB;
        final int finalConcurrency = concurrency;

        System.out.println(">>> 开始数据库内存占用模拟: 目标=" + targetMemMB + "MB, 批大小=" + batchSizeMB + "MB, 并发=" + concurrency);

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            String sqlType = getStandardDbType();
            if ("postgresql".equals(sqlType)) {
                stmt.execute("DROP TABLE IF EXISTS memory_test;");
                stmt.execute("CREATE TABLE memory_test(id SERIAL, val BYTEA);");
            } else if ("mysql".equals(sqlType)) {
                stmt.execute("DROP TABLE IF EXISTS memory_test;");
                stmt.execute("CREATE TABLE memory_test(id INT AUTO_INCREMENT PRIMARY KEY, val LONGBLOB);");
            } else {
                stmt.execute("DROP TABLE IF EXISTS memory_test;");
                stmt.execute("CREATE TABLE memory_test(id INT PRIMARY KEY, val BLOB);");
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(finalConcurrency);
        byte[] data = new byte[finalBatchSizeMB * 1024 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) 'A';

        for (int w = 0; w < finalConcurrency; w++) {
            final int workerId = w;
            executor.submit(() -> {
                int loop = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    loop++;
                    try (Connection conn = getConnection()) {
                        String sql = "INSERT INTO memory_test(val) VALUES(?)";
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            pstmt.setBytes(1, data);
                            pstmt.executeUpdate();
                        }
                        System.out.println(">>> [worker " + workerId + "] 循环 " + loop + " 完成 (" + finalBatchSizeMB + " MB)");
                    } catch (SQLException e) {
                        System.err.println(">>> [worker " + workerId + "] 插入出错: " + e.getMessage());
                        try { Thread.sleep(1000); } catch (InterruptedException ex) { break; }
                    }
                }
            });
        }

        // 运行一段时间后停止，或者让用户手动停止
        System.out.println(">>> 压力注入中... 按 Ctrl+C 停止 (或在 Main 中根据需要调整持续时间)");
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.MINUTES);
    }
}
