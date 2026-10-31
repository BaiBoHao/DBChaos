package chaos.inject;

/** Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md. */
public class SmpMemExplosionInject extends AdaptiveFaultInject {
    private int threads, dop, workMB, expansion;
    private boolean enterprise;
    private String query;
    public SmpMemExplosionInject(String dbType) { super(dbType, "SMP_MEM_EXPLOSION"); }
    protected void configure(String[] args) {
        logAdaptive("Detected " + querySettings("enable_thread_pool", "query_dop", "work_mem"));
        boolean pool = settingBool("enable_thread_pool", false);
        enterprise = isEnterprise();
        // Native SMP in openGauss uses StreamWorker procs allocated from the global proc pool (max_connections).
        // A query with nested redistribute/gather streams uses up to 2*dop workers.
        // To prevent exhausting PGPROCs while saturating multi-core CPUs, size dop and threads proportionally.
        if (enterprise) {
            dop = clamp(arg(args, "-dop", 2), 1, 4);
            int maxSafeThreads = Math.max(2, (budget() - 10) / (dop * 2 + 1));
            threads = clamp(arg(args, "-threads", maxSafeThreads), 2, maxSafeThreads);
            workMB = 64;
            expansion = 6;
            query = "SELECT count(*) FROM (" +
                    "WITH a AS (SELECT s_i_id, g, repeat('a',64) || s_data || s_i_id::text || g::text AS pad FROM bmsql_stock CROSS JOIN generate_series(1," + expansion + ") AS g WHERE s_w_id=1), " +
                    "     b AS (SELECT s_i_id, g, repeat('b',64) || s_data || s_i_id::text || g::text AS pad FROM bmsql_stock CROSS JOIN generate_series(1," + expansion + ") AS g WHERE s_w_id=1) " +
                    "SELECT a.pad, b.pad, count(*) FROM a JOIN b ON a.s_i_id=b.s_i_id AND a.g=b.g " +
                    "GROUP BY a.pad, b.pad ORDER BY a.pad, b.pad) AS pressure";
        } else {
            dop = clamp(arg(args, "-dop", pool ? 4 : 2), 1, 4);
            int maxSafeThreads = Math.max(2, budget() / (dop * 3));
            int adaptiveThreads = Math.min(6, maxSafeThreads);
            int requested = arg(args, "-threads", adaptiveThreads);
            threads = clamp(requested, 2, maxSafeThreads);
            workMB = clamp((int)(memoryKB() / (1024L * threads * dop * 2)), 32, 256);
            expansion = 2;
            query = "SELECT count(*) FROM (" +
                    "SELECT a.s_i_id, a.pad, b.pad FROM " +
                    "(SELECT s_i_id, g, repeat('a',64) || s_data || (s_i_id % 100)::text || g::text AS pad " +
                    " FROM bmsql_stock CROSS JOIN generate_series(1," + expansion + ") AS g WHERE s_w_id=1) a " +
                    "JOIN " +
                    "(SELECT s_i_id, g, repeat('b',64) || s_data || (s_i_id % 100)::text || g::text AS pad " +
                    " FROM bmsql_stock CROSS JOIN generate_series(1," + expansion + ") AS g WHERE s_w_id=1) b " +
                    "ON a.s_i_id=b.s_i_id AND a.g=b.g " +
                    "GROUP BY a.s_i_id, a.pad, b.pad" +
                    ") AS pressure";
        }
        logAdaptive("Native SMP parallel execution; threads=" + threads + "; query_dop=" + dop +
                " (effective parallel workers=" + (threads * dop * 2) + "); work_mem=" + workMB + "MB; expansion=" + expansion +
                (enterprise ? " [Enterprise Multi-Core Stream Pipeline & Disk Spill Enabled]" : ""));
    }

    protected void startWork() {
        for (int i=0; i<threads; i++) worker(conn -> {
            sql(conn, "SET query_dop = " + dop);
            sql(conn, "SET work_mem = '" + workMB + "MB'");
            sql(conn, "SET enable_hashjoin = on");
            sql(conn, "SET enable_mergejoin = off");
            sql(conn, "SET enable_nestloop = off");
            sql(conn, "SET enable_hashagg = on");
            sql(conn, "SET enable_sort = on");
            if (enterprise) {
                sql(conn, "SET enable_bitmapscan = off");
                sql(conn, "SET enable_indexscan = off");
            }
            if (scalar(conn, query) > 0) ok.incrementAndGet();
        });
    }

    @Override
    public void printHelp() {
        System.out.println("用例: smp_mem_explosion (SMP 算子内存乘积爆炸与向量化执行器 OOM)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   并发重型查询线程数 [受安全 PGPROC 上限约束]");
        System.out.println("  -dop <num>       并行度 DOP [默认: 4]");
    }
}
