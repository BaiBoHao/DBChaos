package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CSNLog SLRU 页面淘汰争抢与提交护航震荡不利注入。
 * 机制原理：
 * 1. 启动可重复读（Repeatable Read）锚点事务，固定早期 XID 地平线；
 * 2. 启动并发 Mutator 线程通过密集 Savepoint 循环与批量短事务，以极高速率消耗海量 XID/SubXID（远超 32 个 SLRU 页容量，即 > 32,768 个事务）；
 * 3. 迫使 CSNLog 32 个共享内存页频繁换入换出，全实例事务提交争抢全局 CSNLogControlLock 互斥锁；
 * 4. 倒计时结束后释放快照锚点并终止 Mutator，度量业务 TPS 在护航排队消解后的自愈时延 (TTR)。
 */
public class CsnlogThrashingInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final AtomicLong xidBurnCount = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Connection> anchorConns = new ArrayList<>();

    public CsnlogThrashingInject(String dbType) {
        super(dbType, "CSNLOG_THRASHING");
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
        int threads = parseIntArg(args, "-threads", 16);
        int anchors = parseIntArg(args, "-anchors", 2);

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动 CSNLog SLRU 页面淘汰与提交护航震荡不利注入" + RESET);
        System.out.println("   快照锚点数: " + anchors + " | XID 消耗线程数: " + threads + " | 持续时间: " + durationMs + " ms");

        // 1. 建立早期可重复读快照锚点
        for (int i = 0; i < anchors; i++) {
            Connection conn = getConnection();
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT txid_current()")) {
                if (rs.next()) {
                    System.out.println("   [Anchor " + i + "] 锁定基准 XID: " + rs.getLong(1));
                }
            }
            anchorConns.add(conn);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // 2. 并发快速消耗 XID/SubXID 跨越 SLRU 页面
        for (int i = 0; i < threads; i++) {
            final int tid = i;
            executor.execute(() -> runXidBurner(tid));
        }

        long endTime = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(2000);
            System.out.println("   [CSNLog Burner] 累计快速推进事务/子事务: " + xidBurnCount.get() +
                               ", 异常: " + errors.get());
        }

        // 3. 撤除不利
        System.out.println(YELLOW + " >>> 注入时长已到，正在提交快照锚点并撤除不利..." + RESET);
        running.set(false);
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        for (Connection conn : anchorConns) {
            try {
                conn.commit();
                conn.close();
            } catch (Exception ignored) {}
        }
        anchorConns.clear();

        System.out.println(CYAN + " >>> CSNLog 注入完成，总计推进事务/子事务: " + xidBurnCount.get() + RESET);
    }

    private void runXidBurner(int tid) {
        // 利用 PL/pgSQL 循环批量创建子事务与提交，迅速拉大 XID 跨度
        String burnSql = "DO $$ BEGIN FOR i IN 1..50 LOOP SAVEPOINT sp; RELEASE SAVEPOINT sp; END LOOP; END $$;";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                while (running.get()) {
                    try {
                        stmt.execute(burnSql);
                        xidBurnCount.addAndGet(50);
                    } catch (SQLException e) {
                        errors.incrementAndGet();
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("   [Burner " + tid + "] 异常: " + e.getMessage());
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
        System.out.println("用例: csnlog_thrashing (CSNLog SLRU 页面淘汰争抢与提交护航震荡)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   XID 消耗线程数 [默认: 16]");
        System.out.println("  -anchors <num>   快照锚点数 [默认: 2]");
    }
}
