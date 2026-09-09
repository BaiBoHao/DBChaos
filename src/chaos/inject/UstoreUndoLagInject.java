package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ustore 超长 Undo 链刺穿与后台回收滞后不利注入。
 * 机制原理：
 * 1. 建立独立的 Ustore 表并插入热点记录；
 * 2. 启动快照长事务锚定 xmin，同时 Mutator 线程对热点记录进行高频原地更新（UPDATE），
 *    在数据页内构造超长 Undo 版本链并阻塞 Undo 回收；
 * 3. 伴随测试对该热点行及 TPC-C 业务进行可见性多版本反解（PCR），压迫 CPU 与 Undo 缓冲区；
 * 4. 计时结束提交长事务，观测后台 Undo 回收线程的真实物理回收时延与查询延迟自愈回落时间 (TTR)。
 */
public class UstoreUndoLagInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final AtomicLong updateCount = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String tableName = "chaos_ustore_bench";

    public UstoreUndoLagInject(String dbType) {
        super(dbType, "USTORE_UNDO_LAG");
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
        int threads = parseIntArg(args, "-threads", 8);

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动 Ustore 超长 Undo 链刺穿不利注入" + RESET);
        setupUstoreTable();

        ExecutorService executor = Executors.newFixedThreadPool(threads + 1);

        // 1. 快照长事务锚点
        executor.execute(() -> runSnapshotAnchor(durationMs));
        Thread.sleep(500);

        // 2. 高频原地更新制造超长 Undo 链
        for (int i = 0; i < threads; i++) {
            executor.execute(this::runUstoreMutator);
        }

        long endTime = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(2000);
            System.out.println("   [Ustore Undo Mutator] 累计原地更新版本: " + updateCount.get() + ", 异常: " + errors.get());
        }

        System.out.println(YELLOW + " >>> 注入时长已到，正在释放快照锚点并撤除 Undo 链压力..." + RESET);
        running.set(false);
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        System.out.println(CYAN + " >>> Ustore 不利注入结束，累计更新版本数: " + updateCount.get() + RESET);
    }

    private void setupUstoreTable() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute("CREATE TABLE " + tableName + " (id int PRIMARY KEY, val int, pad text) WITH (storage_type=ustore)");
            stmt.execute("INSERT INTO " + tableName + " VALUES (1, 0, repeat(x, 500))");
            System.out.println("   [Ustore Setup] 初始化 Ustore 表 " + tableName + " 成功。");
        } catch (SQLException e) {
            System.err.println("   [Ustore Setup] 表初始化失败: " + e.getMessage());
        }
    }

    private void runSnapshotAnchor(long durationMs) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT val FROM " + tableName + " WHERE id = 1")) {
                if (rs.next()) {
                    System.out.println("   [Snapshot Anchor] 快照锚点已建立，锁定低位 xmin。");
                }
            }
            long deadline = System.currentTimeMillis() + durationMs;
            while (running.get() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
            conn.commit();
            System.out.println("   [Snapshot Anchor] 快照锚点已提交。");
        } catch (SQLException e) {
            System.err.println("   [Snapshot Anchor] 异常: " + e.getMessage());
        }
    }

    private void runUstoreMutator() {
        String sql = "UPDATE " + tableName + " SET val = val + 1, pad = repeat(y, 500) WHERE id = 1";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                while (running.get()) {
                    try {
                        pstmt.executeUpdate();
                        updateCount.incrementAndGet();
                    } catch (SQLException e) {
                        errors.incrementAndGet();
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("   [Ustore Mutator] 连接异常: " + e.getMessage());
        }
    }

    private int parseIntArg(String[] args, String target, int defaultValue) {
        String val = getArg(args, target);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public void printHelp() {
        System.out.println("用例: ustore_undo_lag (Ustore 超长 Undo 链刺穿与后台回收滞后)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   高频原地更新线程数 [默认: 8]");
    }
}
