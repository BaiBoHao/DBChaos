package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 读取放大陷阱。
 * 通过快照锚点与预热膨胀制造大量不可见版本，再用扫描线程放大执行器的读放大与可见性检查开销。
 */
public class ReadAmpTrapInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final List<Connection> anchorConnections = new ArrayList<Connection>();
    private final AtomicLong updateCount = new AtomicLong(0);
    private final AtomicLong scanCount = new AtomicLong(0);
    private final AtomicLong totalScanNanos = new AtomicLong(0);
    private final AtomicLong scanErrors = new AtomicLong(0);
    private final String tableName = "chaos_read_amp";

    public ReadAmpTrapInject(String dbType) {
        super(dbType, "READ_AMP_TRAP");
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        String durationStr = getArg(args, "-duration");
        if (durationStr == null) {
            System.err.println(RED + " 参数错误: 缺失必填参数 -duration (ms)" + RESET);
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);
        long warmupMs = parseLongArg(args, "-warmup", 20000L);
        int anchors = parseIntArg(args, "-anchors", 1);
        int mutators = parseIntArg(args, "-mutators", 8);
        int scanners = parseIntArg(args, "-scanners", 4);
        int rows = parseIntArg(args, "-rows", 30000);
        int payloadKb = parseIntArg(args, "-payload-kb", 2);
        String scanMode = parseStringArg(args, "-scan-mode", "mixed").toLowerCase();

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动读取放大陷阱注入" + RESET);
        System.out.println("   表名: " + tableName + " | 锚点事务: " + anchors + " | 预热更新线程: " + mutators + " | 扫描线程: " + scanners);
        System.out.println("   初始行数: " + rows + " | 载荷: " + payloadKb + " KB | 预热: " + warmupMs + " ms | 总时长: " + durationMs + " ms");

        setupEnvironment(rows, payloadKb);

        ExecutorService anchorPool = null;
        ExecutorService mutatorPool = null;
        ExecutorService scannerPool = null;

        long start = System.currentTimeMillis();
        long warmupEnd = start + warmupMs;
        long endTime = start + durationMs;

        try {
            anchorPool = Executors.newFixedThreadPool(Math.max(1, anchors));
            for (int i = 0; i < anchors; i++) {
                final int anchorId = i;
                anchorPool.execute(() -> openSnapshotAnchor(anchorId));
            }

            Thread.sleep(1000);

            mutatorPool = Executors.newFixedThreadPool(Math.max(1, mutators));
            for (int i = 0; i < mutators; i++) {
                mutatorPool.execute(() -> runMutator(rows, warmupEnd));
            }

            scannerPool = Executors.newFixedThreadPool(Math.max(1, scanners));
            for (int i = 0; i < scanners; i++) {
                scannerPool.execute(() -> runScanner(scanMode, endTime));
            }

            while (System.currentTimeMillis() < endTime) {
                Thread.sleep(5000);
                printLiveStats();
            }
        } finally {
            releaseAnchors();
            if (anchorPool != null) {
                anchorPool.shutdownNow();
                anchorPool.awaitTermination(5, TimeUnit.SECONDS);
            }
            if (mutatorPool != null) {
                mutatorPool.shutdownNow();
                mutatorPool.awaitTermination(5, TimeUnit.SECONDS);
            }
            if (scannerPool != null) {
                scannerPool.shutdownNow();
                scannerPool.awaitTermination(5, TimeUnit.SECONDS);
            }
            cleanupEnvironment();
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n" + BOLD + "不利 Case 用法: " + YELLOW + "exec read_amp_trap" + RESET);
        System.out.println("  通过快照锚点和预热膨胀制造大量不可见版本，再触发扫描型查询放大执行器负载。");
        System.out.println("\n" + BOLD + "参数列表:" + RESET);
        System.out.printf("  %-15s %s\n", "-duration", "必填。故障总时长 (ms)");
        System.out.printf("  %-15s %s\n", "-warmup", "选填。膨胀预热时长 (ms，默认 20000)");
        System.out.printf("  %-15s %s\n", "-anchors", "选填。快照锚点事务数 (默认 1)");
        System.out.printf("  %-15s %s\n", "-mutators", "选填。预热更新线程数 (默认 8)");
        System.out.printf("  %-15s %s\n", "-scanners", "选填。扫描线程数 (默认 4)");
        System.out.printf("  %-15s %s\n", "-rows", "选填。初始化行数 (默认 30000)");
        System.out.printf("  %-15s %s\n", "-payload-kb", "选填。每行载荷 KB 数 (默认 2)");
        System.out.printf("  %-15s %s\n", "-scan-mode", "选填。seq | index | mixed (默认 mixed)");
        System.out.println("\n" + BOLD + "示例:" + RESET);
        System.out.println(CYAN + "  java -jar DBChaos-0.0.1.jar --db opengauss exec read_amp_trap -duration 120000 -warmup 30000 -mutators 8 -scanners 4 -scan-mode mixed" + RESET);
    }

    private void setupEnvironment(int rows, int payloadKb) throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, version BIGINT, group_id INT, payload TEXT)");
            stmt.execute("CREATE INDEX idx_" + tableName + "_group ON " + tableName + " (group_id)");
            insertSeedRows(conn, rows, payloadKb);
        }
    }

    private void insertSeedRows(Connection conn, int rows, int payloadKb) throws SQLException {
        String payload = repeatPayload(payloadKb);
        conn.setAutoCommit(false);
        String sql = "INSERT INTO " + tableName + " (id, version, group_id, payload) VALUES (?, 0, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 1; i <= rows; i++) {
                pstmt.setInt(1, i);
                pstmt.setInt(2, i % 256);
                pstmt.setString(3, payload);
                pstmt.addBatch();
                if (i % 1000 == 0) {
                    pstmt.executeBatch();
                }
            }
            pstmt.executeBatch();
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void openSnapshotAnchor(int anchorId) {
        try {
            Connection conn = getConnection();
            conn.setAutoCommit(false);
            synchronized (anchorConnections) {
                anchorConnections.add(conn);
            }

            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + tableName)) {
                    if (rs.next()) {
                        rs.getInt(1);
                    }
                }
            }
            System.out.println("[Anchor-" + anchorId + "] 已固定快照，事务保持中...");
        } catch (SQLException e) {
            System.err.println("[Anchor-" + anchorId + "] 快照锚点建立失败: " + e.getMessage());
        }
    }

    private void runMutator(int rows, long warmupEnd) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String sql = "UPDATE " + tableName + " SET version = version + 1, payload = payload WHERE id = ?";
        while (System.currentTimeMillis() < warmupEnd && !Thread.currentThread().isInterrupted()) {
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, 1 + random.nextInt(rows));
                pstmt.executeUpdate();
                updateCount.incrementAndGet();
            } catch (SQLException ignored) {}
        }
    }

    private void runScanner(String scanMode, long endTime) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (System.currentTimeMillis() < endTime && !Thread.currentThread().isInterrupted()) {
            String sql = chooseScanSql(scanMode, random);
            long start = System.nanoTime();
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    rs.getObject(1);
                }
                scanCount.incrementAndGet();
                totalScanNanos.addAndGet(System.nanoTime() - start);
            } catch (SQLException e) {
                scanErrors.incrementAndGet();
            }
        }
    }

    private String chooseScanSql(String scanMode, ThreadLocalRandom random) {
        if ("seq".equals(scanMode)) {
            return "SELECT count(*) FROM " + tableName + " WHERE version >= 0";
        }
        if ("index".equals(scanMode)) {
            int lower = random.nextInt(256);
            int upper = Math.min(255, lower + 32);
            return "SELECT count(*) FROM " + tableName + " WHERE group_id BETWEEN " + lower + " AND " + upper + " ORDER BY group_id";
        }
        if (random.nextBoolean()) {
            return "SELECT count(*) FROM " + tableName + " WHERE version >= 0";
        }
        int lower = random.nextInt(256);
        int upper = Math.min(255, lower + 32);
        return "SELECT group_id, count(*) FROM " + tableName + " WHERE group_id BETWEEN " + lower + " AND " + upper + " GROUP BY group_id ORDER BY group_id";
    }

    private void printLiveStats() {
        long scans = scanCount.get();
        long avgScanMicros = scans == 0 ? 0 : totalScanNanos.get() / scans / 1000;
        System.out.println("[Live] updates=" + updateCount.get()
                + " scans=" + scans
                + " avg_scan_us=" + avgScanMicros
                + " scan_errors=" + scanErrors.get());
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

    private void cleanupEnvironment() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
        } catch (SQLException ignored) {}
    }

    private int parseIntArg(String[] args, String key, int defaultValue) {
        String raw = getArg(args, key);
        return raw == null ? defaultValue : Integer.parseInt(raw);
    }

    private long parseLongArg(String[] args, String key, long defaultValue) {
        String raw = getArg(args, key);
        return raw == null ? defaultValue : Long.parseLong(raw);
    }

    private String parseStringArg(String[] args, String key, String defaultValue) {
        String raw = getArg(args, key);
        return raw == null ? defaultValue : raw;
    }

    private String repeatPayload(int payloadKb) {
        int length = Math.max(1, payloadKb) * 1024;
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, 'R');
        return new String(chars);
    }
}
