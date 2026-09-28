package chaos.inject;

import java.sql.*;
import java.util.concurrent.ThreadLocalRandom;

/** Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md. */
public class NestedWorkerExhaustionInject extends AdaptiveFaultInject {
    private int threads, slots, workers, seriesLength, holdMs;
    private boolean pool, innerCreated, parentCreated;
    private final String suffix = Long.toHexString(System.nanoTime());
    private final String inner = "chaos_auto_" + suffix;
    private final String parent = "chaos_parent_" + suffix;
    private final int lockNamespace = ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE);
    public NestedWorkerExhaustionInject(String dbType) { super(dbType, "NESTED_WORKER_EXHAUSTION"); }
    protected void configure(String[] args) {
        logAdaptive("Detected " + querySettings("enable_thread_pool", "thread_pool_attr", "max_concurrent_autonomous_transactions"));
        pool = settingBool("enable_thread_pool", false);
        boolean enterprise = isEnterprise();
        String[] attr = settingStr("thread_pool_attr", "16,1,(nobind)").split(",");
        workers = 16;
        try { workers = (int)Math.min(4096, Math.max(1, (long)Integer.parseInt(attr[0].trim()) * Integer.parseInt(attr[1].trim()))); }
        catch (RuntimeException ignored) { logAdaptive("Unparseable thread_pool_attr; assuming 16 workers"); }
        int cap = Math.max(0, settingInt("max_concurrent_autonomous_transactions", 10));
        slots = Math.min(Math.max(0, cap-2), Math.max(0,budget()/4));
        if (cap == 1) slots = 1;
        if (budget() <= slots) throw new IllegalStateException("Insufficient budget for parent and autonomous sessions");
        int upper = Math.max(1, Math.min(256, budget()-slots));
        int adaptiveMinimum = pool ? Math.max(workers,slots*2) : clamp(scaleByMemory(8,128), 8, enterprise ? 160 : 64);
        int requested = arg(args, "-threads", adaptiveMinimum);
        holdMs = 2500;
        if (pool) {
            long units = units();
            int poolCap = (int)Math.max(10L, Math.min(64L, 2 * units + 8));
            threads = clamp(Math.min(requested, poolCap), 1, upper);
            holdMs = (int)Math.max(1000L, Math.min(2500L, 600 + 400 * units));
            logAdaptive("Applied poolCap=" + poolCap + "; holdMs=" + holdMs);
        } else {
            // In process-per-connection mode (no thread pool), scale parents to saturate connection pool
            int enterpriseParents = clamp(budget() - 15, 32, 160);
            threads = clamp(enterprise ? Math.max(requested, enterpriseParents) : Math.max(requested, adaptiveMinimum), 1, upper);
            holdMs = 200;
        }
        if (threads > requested) logAdaptive("Raised parents " + requested + " -> " + threads +
                " for " + memoryKB() / 1048576 + "GB instance");
        seriesLength = enterprise ? 50000 : 20000;
        slots = Math.min(slots,threads);
        logAdaptive((pool ? "Native pool-worker holding" : "Active multi-process scheduler saturation + execution contention") +
                "; workers=" + workers + "; parents=" + threads + "; autonomous slots=" + slots + "; inner sleep=" + holdMs / 1000.0 + "s" +
                (pool ? "" : "; CPU series=" + seriesLength) +
                (enterprise ? " [Enterprise Multi-Process Saturation Enabled]" : ""));
        if (pool && threads < workers) logAdaptive("Safe parent limit or connection budget limits parents below pool workers");
        if (slots == 0) logAdaptive("Autonomous transactions unavailable: successful parent CALLs use CPU/sleep pressure only");
    }
    protected void startWork() throws Exception {
        if (pool) {
            try (Connection conn = open()) {
                if (slots > 0) {
                    try {
                        sql(conn, "CREATE PROCEDURE " + inner + "(v int) AS PRAGMA AUTONOMOUS_TRANSACTION; " +
                                "BEGIN PERFORM pg_sleep(" + holdMs / 1000.0 + "); END;");
                        innerCreated = true;
                    } catch (SQLException e) {
                        logAdaptive("Autonomous transaction registration unavailable (" + e.getMessage() + ")");
                        slots = 0;
                    }
                }
                String pressure = "PERFORM pg_sleep(" + holdMs / 1000.0 + "); ";
                String call = slots == 0 ? "" :
                        "BEGIN PERFORM pg_advisory_lock(" + lockNamespace + ",v); " +
                        "BEGIN " + inner + "(v); " +
                        "EXCEPTION WHEN OTHERS THEN NULL; END; " +
                        "PERFORM pg_advisory_unlock(" + lockNamespace + ",v); " +
                        "EXCEPTION WHEN OTHERS THEN NULL; END; ";
                sql(conn, "CREATE PROCEDURE " + parent + "(v int) AS BEGIN " + pressure + call + "END;");
                parentCreated = true;
            }
            for (int i=0; i<threads; i++) {
                final int slot = slots == 0 ? 0 : i%slots;
                worker(conn -> {
                    sql(conn, "CALL " + parent + "(" + slot + ")");
                    ok.incrementAndGet();
                });
            }
        } else {
            // Standalone process mode (no thread pool):
            // All backend processes actively run procedural DO blocks, directly saturating the CPU
            // and avoiding procedural LWLock exhaustion.
            final String doBlock = "DO $$ BEGIN " +
                    "PERFORM sum(length(md5(g::text))) FROM generate_series(1," + seriesLength + ") AS g; " +
                    "END; $$;";
            for (int i=0; i<threads; i++) {
                worker(conn -> {
                    sql(conn, doBlock);
                    ok.incrementAndGet();
                });
            }
        }
    }
    protected void cleanup() throws Exception {
        try (Connection conn = open()) {
            if (parentCreated) {
                try { sql(conn, "DROP PROCEDURE " + parent + "(int)"); } catch (SQLException e) { failure(e); }
            }
            if (innerCreated) {
                try { sql(conn, "DROP PROCEDURE " + inner + "(int)"); } catch (SQLException e) { failure(e); }
            }
        }
    }

    @Override
    public void printHelp() {
        System.out.println("用例: nested_worker_exhaustion (自主事务与嵌套查询引发的线程池饥饿死锁)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   并发嵌套调用线程数 [线程池开启时受内存上限约束；关闭时显式值为下限，受安全上限约束]");
    }
}
