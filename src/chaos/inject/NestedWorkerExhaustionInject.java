package chaos.inject;

import java.sql.*;

/** Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md. */
public class NestedWorkerExhaustionInject extends AdaptiveFaultInject {
    private int threads, slots, workers, groups, seriesLength;
    private double holdSec;
    private boolean pool, innerCreated, parentCreated;
    private final String suffix = Long.toHexString(System.nanoTime());
    private final String inner = "chaos_auto_" + suffix;
    private final String parent = "chaos_parent_" + suffix;
    public NestedWorkerExhaustionInject(String dbType) { super(dbType, "NESTED_WORKER_EXHAUSTION"); }
    protected void configure(String[] args) {
        logAdaptive("Detected " + querySettings("enable_thread_pool", "thread_pool_attr", "max_concurrent_autonomous_transactions"));
        pool = settingBool("enable_thread_pool", false);
        boolean enterprise = isEnterprise();
        // thread_pool_attr is "<total worker threads>,<group count>,(<bind>)". The pool holds `total`
        // workers split across groups, but the kernel caps the pool at max_connections
        // (ThreadPoolControler::ConstrainThreadNum), so the real pool size is min(total, max_connections).
        String[] attr = settingStr("thread_pool_attr", "16,2,(nobind)").split(",");
        int attrThreads = 16;
        try { attrThreads = (int)Math.min(4096, Math.max(1, (long)Integer.parseInt(attr[0].trim()))); }
        catch (RuntimeException ignored) { logAdaptive("Unparseable thread_pool_attr; assuming 16 workers"); }
        workers = Math.min(attrThreads, Math.max(1, settingInt("max_connections", 100)));
        groups = 2;
        try { groups = (int)Math.min(64, Math.max(1, (long)Integer.parseInt(attr[1].trim()))); }
        catch (RuntimeException ignored) { }
        int cap = Math.max(0, settingInt("max_concurrent_autonomous_transactions", 10));
        slots = Math.min(Math.max(0, cap-2), Math.max(0,budget()/4));
        if (cap == 1) slots = 1;
        if (budget() <= slots) throw new IllegalStateException("Insufficient budget for parent and autonomous sessions");
        int upper = Math.max(1, Math.min(256, budget()-slots));
        int adaptiveMinimum = pool ? Math.max(workers,slots*2) : clamp(scaleByMemory(8,128), 8, enterprise ? 160 : 64);
        // An explicit -threads is honored verbatim (clamped only to the safety upper bound), so callers
        // can dial intensity up or down; the adaptive sizing below applies only when it is omitted.
        boolean userThreads = getArg(args, "-threads") != null;
        int requested = arg(args, "-threads", adaptiveMinimum);
        seriesLength = enterprise ? 50000 : 20000;
        holdSec = 1.0;
        if (pool) {
            // Pure worker-slot exhaustion, no CPU work. Each parent pg_sleeps inside its worker while its
            // autonomous child (a fresh session admitted through the same pool with no reserved capacity)
            // also sleeps. parents + concurrent children are sized to cover the whole pool, while staying
            // under max_connections; the closed loop escalates further if the pool auto-grows.
            int capSlots = Math.max(1, cap);
            int cover = Math.max(workers - capSlots, (workers + 1) / 2); // parents + min(cap,parents) >= workers
            int connCeiling = Math.max(2, settingInt("max_connections", 100) - capSlots - 6);
            threads = clamp(userThreads ? requested : cover, 1, Math.min(upper, connCeiling));
            logAdaptive("Pool-exhaustion (no CPU): pool=" + workers + " workers in " + groups + " group(s); parents=" +
                    threads + " + up to " + capSlots + " sleeping autonomous children; hold=" + holdSec + "s");
        } else {
            // Process-per-connection mode (no thread pool): saturate the run queue with active CPU work.
            int enterpriseParents = clamp(budget() - 15, 32, 160);
            int auto = enterprise ? Math.max(adaptiveMinimum, enterpriseParents) : adaptiveMinimum;
            threads = clamp(userThreads ? requested : auto, 1, upper);
        }
        if (!userThreads) logAdaptive("Auto parents=" + threads + " for " + memoryKB() / 1048576 + "GB instance");
        else if (requested != threads) logAdaptive("Clamped requested parents " + requested + " -> " + threads + " (safety upper bound)");
        slots = Math.min(slots,threads);
        logAdaptive((pool ? "Nested autonomous worker starvation" : "Active multi-process scheduler saturation + execution contention") +
                "; workers=" + workers + "; parents=" + threads + "; autonomous slots=" + slots +
                (pool ? "; hold=" + holdSec + "s (no CPU)" : "; CPU series=" + seriesLength) +
                (enterprise ? " [Enterprise Multi-Process Saturation Enabled]" : ""));
        if (pool && threads + slots < workers) logAdaptive("Connection budget below pool size; pool escalation relies on the closed loop");
        if (slots == 0) logAdaptive("Autonomous transactions unavailable: parents hold workers directly");
    }
    protected void startWork() throws Exception {
        if (pool) {
            String hold = "PERFORM pg_sleep(" + holdSec + "); ";
            try (Connection conn = open()) {
                if (slots > 0) {
                    try {
                        sql(conn, "CREATE PROCEDURE " + inner + "(v int) AS PRAGMA AUTONOMOUS_TRANSACTION; BEGIN " + hold + "END;");
                        innerCreated = true;
                    } catch (SQLException e) {
                        logAdaptive("Autonomous transaction registration unavailable (" + e.getMessage() + ")");
                        slots = 0;
                    }
                }
                // The autonomous call is wrapped so concurrent-call limit overflows are swallowed and the
                // parent CALL still succeeds (and still counted as effective work).
                String call = slots == 0 ? "" :
                        "BEGIN BEGIN " + inner + "(v); EXCEPTION WHEN OTHERS THEN NULL; END; EXCEPTION WHEN OTHERS THEN NULL; END; ";
                sql(conn, "CREATE PROCEDURE " + parent + "(v int) AS BEGIN " + hold + call + "END;");
                parentCreated = true;
            }
            for (int i=0; i<threads; i++) {
                final int slot = i;
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
        System.out.println("说明: 纯占满线程池——父子会话各自休眠占住 worker 槽位,不消耗 CPU,直击线程池无自主事务容量预留的设计。");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   并发嵌套调用线程数 [起始强度；开启闭环时为其下界，受连接预算安全上限约束]");
        System.out.println("  -loop <on|off>   闭环自适应升压 [默认 on；off 则固定为 -threads 指定的强度]");
        System.out.println("  -factor <n>      升压目标:浅探测延迟达到基线的 n 倍即停 [默认 4]");
    }
}
