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
 * 临时表高频生灭导致系统目录膨胀与元数据锁竞争不利注入。
 * 机制原理：
 * 1. 启动多并发短连接会话，循环执行 CREATE TEMP TABLE、写入单行、DROP TABLE；
 * 2. 模拟微服务及 ORM 框架滥用临时表的场景，在 pg_class、pg_attribute 等系统目录堆积数万 Dead Tuples；
 * 3. 诱发系统表物理文件膨胀与排他锁争用，压迫正常业务查询的解析与权限校验阶段；
 * 4. 注入结束后断开所有临时表线程，测试系统在缺乏 VACUUM FULL 下的元数据访问自愈耗时。
 */
public class TempCatalogBloatInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    private final AtomicLong tempTableCount = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);

    public TempCatalogBloatInject(String dbType) {
        super(dbType, "TEMP_CATALOG_BLOAT");
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

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动临时表高频生灭与系统表膨胀不利注入" + RESET);
        System.out.println("   并发临时表线程数: " + threads + " | 持续时间: " + durationMs + " ms");

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int tid = i;
            executor.execute(() -> runTempTableLoop(tid));
        }

        long endTime = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(2000);
            System.out.println("   [Temp Catalog Bloat] 累计创建/销毁临时表: " + tempTableCount.get() + ", 异常: " + errors.get());
        }

        System.out.println(YELLOW + " >>> 注入时长已到，正在撤除临时表创建压力..." + RESET);
        running.set(false);
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        System.out.println(CYAN + " >>> 临时表注入结束，总计生灭临时表: " + tempTableCount.get() + RESET);
    }

    private void runTempTableLoop(int tid) {
        String tableName = "tmp_chaos_" + tid;
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TEMP TABLE " + tableName + " (id int, c1 varchar(64), c2 text)");
                stmt.execute("INSERT INTO " + tableName + " VALUES (1, demo, repeat(x, 100))");
                stmt.execute("DROP TABLE " + tableName);
                tempTableCount.incrementAndGet();
            } catch (SQLException e) {
                errors.incrementAndGet();
                try {
                    Thread.sleep(20);
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
        System.out.println("用例: temp_catalog_bloat (临时表高频生灭导致系统目录膨胀与元数据锁竞争)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   并发线程数 [默认: 16]");
    }
}
