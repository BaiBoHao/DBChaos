package chaos.inject;

import java.sql.Connection;
import java.util.concurrent.ThreadLocalRandom;

/** Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md. */
public class DwbHammerInject extends AdaptiveFaultInject {
    private int threads, interval, updatePaceMs, seedRows;
    private long writeBudget, targetPerSec;
    private String table;
    private boolean created;

    public DwbHammerInject(String dbType) { super(dbType, "DWB_HAMMER"); }

    private static boolean isTxnTable(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.startsWith("bmsql_") || lower.contains("warehouse")
                || lower.contains("district") || lower.contains("customer")
                || lower.contains("history") || lower.contains("new_order")
                || lower.contains("orders") || lower.contains("order_line")
                || lower.contains("item") || lower.contains("stock");
    }

    protected void configure(String[] args) {
        logAdaptive("Detected " + querySettings("enable_double_write", "enable_incremental_checkpoint"));
        String customTable = getArg(args, "-table");
        if (customTable != null && !customTable.trim().isEmpty()) {
            if (isTxnTable(customTable.trim())) {
                throw new IllegalArgumentException("禁止读取或修改业务事务所涉及的表: " + customTable);
            }
            table = customTable.trim();
        } else {
            table = "chaos_dwb_" + Long.toHexString(System.nanoTime());
        }

        threads = threads(args, 4, 96, 1);
        long units = Math.max(1L, memoryKB() / 2097152);
        threads = Math.min(threads, (int)Math.max(8L, Math.min(96L, 2 * units)));
        int minimum = (int)Math.max(250L, 12000 / units);
        int adaptiveInterval = (int)Math.max(500L, Math.min(2000L, 2000L - 30L * units));
        interval = clamp(arg(args, "-interval", adaptiveInterval), minimum, 60000);
        seedRows = (int) Math.min(100000L, Math.max(20000L, 20000L * units));

        // Bound the total dirty-row writes: a 6GB/2-core host cannot reclaim hundreds of
        // thousands of dead tuples inside the recovery window, so scale the write
        // budget with memory and pace the update workers to spend it over the injection window.
        long durationMs = Math.max(1L, Long.parseLong(getArg(args, "-duration")));
        writeBudget = 15000L * units;
        targetPerSec = Math.max(1L, writeBudget * 1000L / durationMs);
        updatePaceMs = Math.max(0, (int)(1000L * threads / targetPerSec) - 4);
        logAdaptive("Dedicated isolated table (" + table + ") random dirty-page updates + CHECKPOINT; threads=" + threads +
                "; interval=" + interval + "ms; seedRows=" + seedRows + "; writeBudget=" + writeBudget +
                "; targetUpdatesPerSec=" + targetPerSec + "; updatePace=" + updatePaceMs +
                "ms; double-write disabled, if detected, means checkpoint/I/O pressure only");
    }

    protected void startWork() throws Exception {
        try (Connection conn = open()) {
            sql(conn, "DROP TABLE IF EXISTS " + table);
            sql(conn, "CREATE TABLE " + table + " (id int PRIMARY KEY, val1 int, val2 int, pad text)");
            created = true;
            sql(conn, "INSERT INTO " + table + " SELECT g, 0, 0, repeat('x', 128) FROM generate_series(1," + seedRows + ") AS g");
            sql(conn, "ANALYZE " + table);
        }
        worker(conn -> {
            sql(conn, "CHECKPOINT");
            // Checkpoints alone do not establish effective dirty-page pressure.
            Thread.sleep(interval);
        });
        for (int i=0; i<threads; i++) worker(conn -> {
            int id = ThreadLocalRandom.current().nextInt(1, seedRows + 1);
            if (sql(conn, "UPDATE " + table + " SET val1=val1+1, val2=val2+1 WHERE id=" + id) > 0)
                ok.incrementAndGet();
            if (updatePaceMs > 0) Thread.sleep(updatePaceMs);
        });
    }

    @Override
    protected void cleanup() throws Exception {
        if (created && table != null) {
            try (Connection drop = open()) {
                sql(drop, "DROP TABLE IF EXISTS " + table);
            } catch (Exception ignored) { recordTestError(ignored); }
        }
    }

    @Override
    public void printHelp() {
        System.out.println("用例: dwb_hammer (双写缓冲区 DWB 写入争用与 Checkpoint 刷盘雪崩)");
        System.out.println("说明: 使用独立的测试表制造离散脏页与刷盘压力，完全隔离且不触碰业务事务涉及的表。");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   离散刷脏更新线程数 [配置自适应，显式值受安全上限约束]");
        System.out.println("  -interval <ms>   Checkpoint 触发周期(毫秒) [默认: 2000]");
        System.out.println("  -table <name>    自定义测试表名 [选填，严禁指定业务事务表，默认自动创建独立隔离表]");
    }
}
