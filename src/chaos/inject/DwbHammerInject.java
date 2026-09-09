package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 双写缓冲区 (DWB) 写入争用与 Checkpoint 刷盘雪崩不利注入。
 * 机制原理：
 * 1. 启动高并发 Mutator 线程对海量离散数据页（bmsql_stock 全表 100,000 行跨越数千个 Page）进行随机单行更新，
 *    最大化打散局部性并瞬间制造巨量离散脏页；
 * 2. 独立 Checkpoint 线程以极短周期（如每 2 秒）触发显式 CHECKPOINT，迫使 openGauss 将脏页全量排队刷写 DWB；
 * 3. 逼迫 DWB 全局槽位互斥锁与双倍磁盘 I/O 饱和，诱发 BufferAlloc 阻塞与前台工作线程被迫同步刷盘；
 * 4. 注入时长到达后立即停止更新与 Checkpoint，观测系统在积压数 GB 脏页的“I/O 债务”偿还期间的自愈时延 (TTR) 与吞吐回升弹性。
 */
public class DwbHammerInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final AtomicLong updateCount = new AtomicLong(0);
    private final AtomicLong updateErrors = new AtomicLong(0);
    private final AtomicLong checkpointCount = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);

    public DwbHammerInject(String dbType) {
        super(dbType, "DWB_HAMMER");
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
        int threads = parseIntArg(args, "-threads", 24);
        int checkpointIntervalMs = parseIntArg(args, "-interval", 2000);

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动双写缓冲区 (DWB) 写入争用与 Checkpoint 刷盘雪崩不利注入" + RESET);
        System.out.println("   扰动线程数: " + threads + " | Checkpoint 周期: " + checkpointIntervalMs + " ms | 持续时间: " + durationMs + " ms");

        ExecutorService executor = Executors.newFixedThreadPool(threads + 1);

        // 1. 启动定频 Checkpoint 触发线程
        executor.execute(() -> runCheckpointLoop(checkpointIntervalMs));

        // 2. 启动并发离散更新线程，制造散列脏页
        for (int i = 0; i < threads; i++) {
            executor.execute(this::runDiscreteDirtyWorker);
        }

        long endTime = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(2000);
            System.out.println("   [DWB Hammer] 累计产生随机离散更新: " + updateCount.get() +
                               ", 触发显式 Checkpoint: " + checkpointCount.get() +
                               ", 异常: " + updateErrors.get());
        }

        // 3. 撤除不利
        System.out.println(YELLOW + " >>> 注入时长已到，正在撤除离散更新与 Checkpoint 压力..." + RESET);
        running.set(false);
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        System.out.println(CYAN + " >>> DWB 刷脏注入结束，总计生成离散脏页更新: " + updateCount.get() +
                           ", 触发 Checkpoint: " + checkpointCount.get() + RESET);
    }

    private void runCheckpointLoop(int intervalMs) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                while (running.get()) {
                    try {
                        long t0 = System.currentTimeMillis();
                        stmt.execute("CHECKPOINT;");
                        checkpointCount.incrementAndGet();
                        long cost = System.currentTimeMillis() - t0;
                        long sleepTime = Math.max(100, intervalMs - cost);
                        Thread.sleep(sleepTime);
                    } catch (SQLException e) {
                        try {
                            Thread.sleep(intervalMs);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("   [Checkpoint Thread] 发生异常: " + e.getMessage());
        }
    }

    private void runDiscreteDirtyWorker() {
        String sql = "UPDATE bmsql_stock SET s_quantity = s_quantity + 1, s_ytd = s_ytd + 1 WHERE s_w_id = 1 AND s_i_id = ?";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                while (running.get()) {
                    try {
                        // 在 100,000 个商品行中完全随机选取，强力打散数据块局部性
                        int randomItemId = ThreadLocalRandom.current().nextInt(1, 100001);
                        pstmt.setInt(1, randomItemId);
                        pstmt.executeUpdate();
                        updateCount.incrementAndGet();
                    } catch (SQLException e) {
                        updateErrors.incrementAndGet();
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("   [Dirty Worker] 连接异常: " + e.getMessage());
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
        System.out.println("用例: dwb_hammer (双写缓冲区 DWB 写入争用与 Checkpoint 刷盘雪崩)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   离散刷脏更新线程数 [默认: 24]");
        System.out.println("  -interval <ms>   Checkpoint 触发周期(毫秒) [默认: 2000]");
    }
}
