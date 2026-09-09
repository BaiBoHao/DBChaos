package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自主事务与嵌套查询引发的线程池饥饿死锁不利注入。
 * 机制原理：
 * 1. 创建包含自主事务（PRAGMA AUTONOMOUS_TRANSACTION）的嵌套存储过程；
 * 2. 启动并发线程数超过 thread_pool_attr 中配置的 Worker 数（当前配置每组 16，2 组共 32 Worker）；
 * 3. 父任务持有 Worker 线程并同步等待自主事务完成，自主事务向线程池申请新的 Worker 槽位，诱发线程池饥饿自锁；
 * 4. 计时结束强制释放客户端连接，观察 openGauss 线程池能否自行解挂或恢复响应。
 */
public class NestedWorkerExhaustionInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final AtomicLong callCount = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);

    public NestedWorkerExhaustionInject(String dbType) {
        super(dbType, "NESTED_WORKER_EXHAUSTION");
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
        int threads = parseIntArg(args, "-threads", 36);

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动自主事务嵌套线程池饥饿不利注入" + RESET);
        setupStoredProcedures();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.execute(this::runNestedCaller);
        }

        long endTime = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(2000);
            System.out.println("   [Nested Worker] 并发调用自主事务计数: " + callCount.get() + ", 异常: " + errors.get());
        }

        System.out.println(YELLOW + " >>> 注入时长已到，正在撤除并发嵌套调用并断开会话..." + RESET);
        running.set(false);
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        System.out.println(CYAN + " >>> 嵌套自主事务注入结束，总计调用数: " + callCount.get() + RESET);
    }

    private void setupStoredProcedures() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE PROCEDURE proc_chaos_auto_inner(v int) AS " +
                         "PRAGMA AUTONOMOUS_TRANSACTION; " +
                         "BEGIN " +
                         "  PERFORM pg_sleep(0.3); " +
                         "END; /");
            stmt.execute("CREATE OR REPLACE PROCEDURE proc_chaos_parent(v int) AS " +
                         "BEGIN " +
                         "  proc_chaos_auto_inner(v); " +
                         "END; /");
            System.out.println("   [Procedure Setup] 自主事务与父调用存储过程创建成功。");
        } catch (SQLException e) {
            System.err.println("   [Procedure Setup] 存储过程创建失败: " + e.getMessage());
        }
    }

    private void runNestedCaller() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("CALL proc_chaos_parent(1);");
                callCount.incrementAndGet();
            } catch (SQLException e) {
                errors.incrementAndGet();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}
            }
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
        System.out.println("用例: nested_worker_exhaustion (自主事务与嵌套查询引发的线程池饥饿死锁)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   并发嵌套调用线程数 [默认: 36]");
    }
}
