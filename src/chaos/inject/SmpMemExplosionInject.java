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
 * SMP 算子内存乘积爆炸与向量化执行器内存压迫不利注入。
 * 机制原理：
 * 1. 会话级强制开启多线程 SMP 并行（query_dop=8）；
 * 2. 执行由多层 HashJoin、大基数 HashAgg 与 Sort 构成的复杂重型查询，
 *    诱发内存按 算子数 * DOP * work_mem 几何级数乘积放大；
 * 3. 并发多线程执行，压迫 openGauss 动态内存防护与进程内存上限；
 * 4. 计时结束终止并发查询，测试执行器能否快速释放内存上下文（MemoryContext）并避免残余泄漏。
 */
public class SmpMemExplosionInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final AtomicLong queryCount = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);

    public SmpMemExplosionInject(String dbType) {
        super(dbType, "SMP_MEM_EXPLOSION");
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
        int threads = parseIntArg(args, "-threads", 6);
        int dop = parseIntArg(args, "-dop", 4);

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动 SMP 算子内存乘积放大不利注入" + RESET);
        System.out.println("   并发查询线程数: " + threads + " | 单查询 DOP: " + dop + " | 持续时间: " + durationMs + " ms");

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.execute(() -> runHeavySmpQuery(dop));
        }

        long endTime = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(2000);
            System.out.println("   [SMP Memory] 累计完成大型并行查询: " + queryCount.get() + ", 异常: " + errors.get());
        }

        System.out.println(YELLOW + " >>> 注入时长已到，正在撤除 SMP 内存查询..." + RESET);
        running.set(false);
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        System.out.println(CYAN + " >>> SMP 内存注入结束，总计执行大型算子查询: " + queryCount.get() + RESET);
    }

    private void runHeavySmpQuery(int dop) {
        String sql = "WITH t1 AS (SELECT s_i_id, s_quantity, repeat(a, 64) AS p1 FROM bmsql_stock WHERE s_w_id = 1), " +
                     "     t2 AS (SELECT s_i_id, s_ytd, repeat(b, 64) AS p2 FROM bmsql_stock WHERE s_w_id = 1) " +
                     "SELECT t1.p1, t2.p2, count(*), array_agg(t1.s_i_id ORDER BY t1.s_i_id) " +
                     "FROM t1 JOIN t2 ON t1.s_i_id = t2.s_i_id " +
                     "GROUP BY t1.p1, t2.p2 ORDER BY 1, 2 LIMIT 100;";

        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("SET query_dop = " + dop);
                stmt.execute("SET work_mem = 32MB");
                stmt.execute("SET enable_hashjoin = ON");
                stmt.execute("SET enable_sort = ON");
                stmt.executeQuery(sql);
                queryCount.incrementAndGet();
            } catch (SQLException e) {
                errors.incrementAndGet();
                try {
                    Thread.sleep(100);
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
        System.out.println("用例: smp_mem_explosion (SMP 算子内存乘积爆炸与向量化执行器 OOM)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   并发重型查询线程数 [默认: 6]");
        System.out.println("  -dop <num>       并行度 DOP [默认: 4]");
    }
}
