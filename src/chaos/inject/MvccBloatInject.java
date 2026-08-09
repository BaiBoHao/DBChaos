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
 * MVCC 膨胀注入。
 * 通过长事务钉住快照地平线，使高频 UPDATE 生成的旧版本无法被及时清理。
 */
public class MvccBloatInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final List<Connection> anchorConnections = new ArrayList<Connection>();
    private final AtomicLong updateCount = new AtomicLong(0);
    private final AtomicLong updateErrors = new AtomicLong(0);
    private final String tableName = "chaos_mvcc_bloat";

    public MvccBloatInject(String dbType) {
        super(dbType, "MVCC_BLOAT");
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
        int anchors = parseIntArg(args, "-anchors", 1);
        int mutators = parseIntArg(args, "-mutators", 8);
        int rows = parseIntArg(args, "-rows", 20000);
        int payloadKb = parseIntArg(args, "-payload-kb", 2);

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动 MVCC 膨胀注入" + RESET);
        System.out.println("   表名: " + tableName + " | 锚点事务: " + anchors + " | 更新线程: " + mutators);
        System.out.println("   初始行数: " + rows + " | 载荷: " + payloadKb + " KB | 持续时间: " + durationMs + " ms");

        setupEnvironment(rows, payloadKb);

        ExecutorService anchorPool = null;
        ExecutorService mutatorPool = null;
        long endTimeMs = System.currentTimeMillis() + durationMs;

        try {
            anchorPool = Executors.newFixedThreadPool(Math.max(1, anchors));
            for (int i = 0; i < anchors; i++) {
                final int anchorId = i;
                anchorPool.execute(() -> openSnapshotAnchor(anchorId));
            }

            Thread.sleep(1000);

            mutatorPool = Executors.newFixedThreadPool(Math.max(1, mutators));
            for (int i = 0; i < mutators; i++) {
                mutatorPool.execute(() -> runMutator(rows, endTimeMs));
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
            if (mutatorPool != null) {
                mutatorPool.shutdownNow();
                mutatorPool.awaitTermination(5, TimeUnit.SECONDS);
            }
            cleanupEnvironment();
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n" + BOLD + "不利 Case 用法: " + YELLOW + "storage mvcc_bloat" + RESET);
        System.out.println("  通过快照锚点钉住 xmin 地平线，并对热点表高频更新，制造版本膨胀与清理受阻。");
        System.out.println("\n" + BOLD + "参数列表:" + RESET);
        System.out.printf("  %-15s %s\n", "-duration", "必填。故障总时长 (ms)");
        System.out.printf("  %-15s %s\n", "-anchors", "选填。快照锚点事务数 (默认 1)");
        System.out.printf("  %-15s %s\n", "-mutators", "选填。高频更新线程数 (默认 8)");
        System.out.printf("  %-15s %s\n", "-rows", "选填。初始化行数 (默认 20000)");
        System.out.printf("  %-15s %s\n", "-payload-kb", "选填。每行载荷 KB 数 (默认 2)");
        System.out.println("\n" + BOLD + "示例:" + RESET);
        System.out.println(CYAN + "  java -jar DBChaos-0.0.1.jar --db opengauss storage mvcc_bloat -duration 120000 -anchors 1 -mutators 8 -rows 20000" + RESET);
    }

    private void setupEnvironment(int rows, int payloadKb) throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, version BIGINT, group_id INT, payload TEXT)");
            stmt.execute("CREATE INDEX idx_" + tableName + "_group ON " + tableName + " (group_id)");

            if ("postgresql".equals(getStandardDbType())) {
                stmt.execute("ALTER TABLE " + tableName + " SET (autovacuum_enabled = true, autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 50)");
            }

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
                pstmt.setInt(2, i % 128);
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

    private void runMutator(int rows, long endTimeMs) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String sql = "UPDATE " + tableName + " SET version = version + 1, payload = payload WHERE id = ?";

        while (System.currentTimeMillis() < endTimeMs && !Thread.currentThread().isInterrupted()) {
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int rowId = 1 + random.nextInt(rows);
                pstmt.setInt(1, rowId);
                pstmt.executeUpdate();
                updateCount.incrementAndGet();
            } catch (SQLException e) {
                updateErrors.incrementAndGet();
            }
        }
    }

    private void printLiveStats() {
        System.out.print("[Live] updates=" + updateCount.get() + " errors=" + updateErrors.get());
        if ("postgresql".equals(getStandardDbType())) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT n_dead_tup, vacuum_count, autovacuum_count FROM pg_stat_user_tables WHERE relname = '" + tableName + "'")) {
                if (rs.next()) {
                    System.out.print(" dead_tuples=" + rs.getLong(1)
                            + " vacuum=" + rs.getLong(2)
                            + " autovacuum=" + rs.getLong(3));
                }
            } catch (SQLException ignored) {}
        }
        System.out.println();
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

    private String repeatPayload(int payloadKb) {
        int length = Math.max(1, payloadKb) * 1024;
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, 'X');
        return new String(chars);
    }
}
