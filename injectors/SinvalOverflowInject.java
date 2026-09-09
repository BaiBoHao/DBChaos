package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享失效队列溢出与系统缓存击穿 (CatCache Invalidation Storm) 不利注入。
 * 机制原理：
 * 1. 启动 1 个慢会话（Victim）保持长事务不提交，故意不拉取 Shared Invalidation 消息；
 * 2. 启动并发 Mutator 线程高频执行非阻塞轻量元数据变更（如 COMMENT ON TABLE / COLUMN），
 *    向共享内存环形队列持续写入超过 4096 条失效消息；
 * 3. 迫使 openGauss 内核将滞后会话标记为 reset 状态，导致全局 CatCache/RelCache 被清空，
 *    引发常规业务点查的系统表物理磁盘回表与元数据锁争用；
 * 4. 计时结束后立即提交长事务并停止 Mutator，观察业务从缓存击穿状态下的自愈过程与恢复时间 (TTR)。
 */
public class SinvalOverflowInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final AtomicLong ddlCount = new AtomicLong(0);
    private final AtomicLong ddlErrors = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);

    public SinvalOverflowInject(String dbType) {
        super(dbType, "SINVAL_OVERFLOW");
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
        int mutators = parseIntArg(args, "-threads", 16);
        String targetTable = getArg(args, "-table");
        if (targetTable == null || targetTable.trim().isEmpty()) {
            targetTable = "bmsql_warehouse";
        }

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动元数据失效队列溢出 (Sinval Overflow) 不利注入" + RESET);
        System.out.println("   目标表: " + targetTable + " | 扰动线程数: " + mutators + " | 持续时间: " + durationMs + " ms");

        final String testTable = targetTable;
        ExecutorService executor = Executors.newFixedThreadPool(mutators + 1);

        // 1. 启动慢会话锚点：开启事务并维持快照，滞后消息消费
        executor.execute(() -> runSlowAnchor(durationMs));

        // 等待锚点会话成功建立事务
        Thread.sleep(500);

        // 2. 启动 Mutator 线程高频生成 Shared Invalidation 消息
        for (int i = 0; i < mutators; i++) {
            final int threadId = i;
            executor.execute(() -> runMutator(testTable, threadId));
        }

        // 持续指定时间
        long endTime = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(2000);
            System.out.println("   [Sinval Mutator] 累计生成元数据失效变更: " + ddlCount.get() + ", 错误: " + ddlErrors.get());
        }

        // 撤除不利
        System.out.println(YELLOW + " >>> 注入时长已到，正在撤除元数据不利并释放会话..." + RESET);
        running.set(false);
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        System.out.println(CYAN + " >>> 元数据失效注入完成，总计触发 DDL 失效消息: " + ddlCount.get() + RESET);
    }

    private void runSlowAnchor(long durationMs) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                // 获取当前快照并保持打开，阻止 SI 队列向前推进并拉大消息滞后量
                try (ResultSet rs = stmt.executeQuery("SELECT txid_current(), count(*) FROM bmsql_warehouse")) {
                    if (rs.next()) {
                        long txid = rs.getLong(1);
                        System.out.println("   [Slow Anchor] 慢会话已启动，固定快照 txid=" + txid);
                    }
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
            System.out.println("   [Slow Anchor] 慢会话已提交释放。");
        } catch (SQLException e) {
            System.err.println("   [Slow Anchor] 发生异常: " + e.getMessage());
        }
    }

    private void runMutator(String table, int threadId) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                long count = 0;
                while (running.get()) {
                    try {
                        String sql = String.format("COMMENT ON TABLE %s IS 'chaos_inval_%d_%d'", table, threadId, count++);
                        stmt.execute(sql);
                        ddlCount.incrementAndGet();
                    } catch (SQLException e) {
                        ddlErrors.incrementAndGet();
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("   [Mutator " + threadId + "] 连接异常: " + e.getMessage());
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
        System.out.println("用例: sinval_overflow (元数据失效队列溢出与系统缓存击穿)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   DDL 扰动线程数 [默认: 16]");
        System.out.println("  -table <name>    扰动目标表 [默认: bmsql_warehouse]");
    }
}
