package chaos.inject;

import java.sql.*;

/** Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md. */
public class TempCatalogBloatInject extends AdaptiveFaultInject {
    private int threads;
    private int paceMs;
    private int burstSize;
    private boolean enterprise;
    public TempCatalogBloatInject(String dbType) { super(dbType, "TEMP_CATALOG_BLOAT"); }
    protected void configure(String[] args) {
        enterprise = isEnterprise();
        int baseCap = enterprise ? Math.min(60, budget()) : Math.min(16, budget());
        threads = clamp(arg(args, "-threads", baseCap), 1, Math.min(enterprise ? 64 : 32, budget()));
        burstSize = enterprise ? 5 : 1;
        if (enterprise) {
            paceMs = 0; // Continuous DDL storm without artificial throttling
        } else {
            int target = 1000;
            int pace = (int)Math.round(1000.0 * threads * burstSize / target) - 12 * burstSize;
            paceMs = Math.max(1, pace);
        }
        logAdaptive("Native temporary catalog create/drop; threads=" + threads + "; burst=" + burstSize +
                "; paceMs=" + paceMs + "; connection budget=" + budget() +
                (enterprise ? " [Enterprise High-Concurrency Catalog DDL Storm Enabled]" : ""));
    }
    protected void startWork() {
        for (int i=0; i<threads; i++) worker(conn -> {
            String table = "tmp_chaos_catalog";
            for (int b=0; b<burstSize && running; b++) {
                if (enterprise) {
                    sql(conn, "CREATE TEMP TABLE " + table + " (id int PRIMARY KEY, c1 text, c2 text, c3 text)");
                    try {
                        sql(conn, "CREATE INDEX ON " + table + " (c1)");
                        sql(conn, "CREATE INDEX ON " + table + " (c2)");
                        sql(conn, "CREATE INDEX ON " + table + " (c3)");
                        try { sql(conn, "INSERT INTO " + table + " VALUES (1, 'demo', 'data', 'block')"); }
                        catch (SQLException e) { failure(e); }
                    } finally {
                        sql(conn, "DROP TABLE " + table);
                        ok.incrementAndGet();
                    }
                } else {
                    sql(conn, "CREATE TEMP TABLE " + table + " (id int, c1 varchar(64), c2 text)");
                    try {
                        try { sql(conn, "INSERT INTO " + table + " VALUES (1, 'demo', repeat('x',100))"); }
                        catch (SQLException e) { failure(e); }
                    } finally {
                        sql(conn, "DROP TABLE " + table);
                        ok.incrementAndGet();
                    }
                }
            }
            try { Thread.sleep(paceMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }

    @Override
    public void printHelp() {
        System.out.println("用例: temp_catalog_bloat (临时表高频生灭导致系统目录膨胀与元数据锁竞争)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   并发线程数 [配置自适应，显式值受安全上限约束]");
    }
}
