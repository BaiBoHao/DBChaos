package chaos.inject;

import java.sql.*;

/** Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md. */
public class SinvalOverflowInject extends AdaptiveFaultInject {
    private int threads;
    private String table;
    public SinvalOverflowInject(String dbType) { super(dbType, "SINVAL_OVERFLOW"); }
    protected void configure(String[] args) {
        threads = threads(args, 4, 96, 1);
        table = getArg(args, "-table");
        // Strict non-interference: never touch or target business tables (bmsql_*)
        if (table == null || table.trim().isEmpty() || table.startsWith("bmsql_")) {
            table = "chaos_sinval_dummy";
        }
        if (!table.matches("[a-zA-Z_][a-zA-Z_0-9]*(\\.[a-zA-Z_][a-zA-Z_0-9]*)?"))
            throw new IllegalArgumentException("-table must be a simple or schema-qualified identifier");
        logAdaptive("Native non-business sinval overflow & syscache thrashing; threads=" + threads + "; table_prefix=" + table);
    }
    protected void startWork() throws Exception {
        // Initialize independent chaos tables (one per worker to eliminate table lock serialization)
        try (Connection setup = open()) {
            for (int i = 0; i < threads; i++) {
                sql(setup, "CREATE TABLE IF NOT EXISTS " + table + "_" + i + " (id int, val text)");
            }
        }

        // Worker 0: Shared namespace invalidation & Global SysCache flush
        worker(conn -> {
            sql(conn, "COMMENT ON SCHEMA public IS 'chaos_inval_" + System.nanoTime() + "'");
            try {
                sql(conn, "SELECT gs_gsc_clean((SELECT oid FROM pg_database WHERE datname=current_database()))");
            } catch (Exception ignored) { recordTestError(ignored); }
            ok.incrementAndGet();
        });

        // Workers 1..threads-1: High-frequency burst invalidations on distinct non-business tables
        for (int i = 1; i < threads; i++) {
            final int workerId = i;
            final String myTable = table + "_" + workerId;
            worker(conn -> {
                String batchSql = "DO $$ BEGIN " +
                    "FOR j IN 1..400 LOOP " +
                    "EXECUTE format('COMMENT ON TABLE " + myTable + " IS %L', 'v_' || j); " +
                    "END LOOP; " +
                    "END $$;";
                sql(conn, batchSql);
                ok.addAndGet(400);
            });
        }
    }

    @Override
    protected void cleanup() throws Exception {
        try (Connection clean = open()) {
            for (int i = 0; i < threads; i++) {
                sql(clean, "DROP TABLE IF EXISTS " + table + "_" + i);
            }
            sql(clean, "COMMENT ON SCHEMA public IS NULL");
        } catch (Exception ignored) { recordTestError(ignored); }
    }

    @Override
    public void printHelp() {
        System.out.println("用例: sinval_overflow (元数据失效队列溢出与系统缓存击穿)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   DDL 扰动线程数 [配置自适应，显式值受安全上限约束]");
        System.out.println("  -table <name>    扰动目标表前缀 [默认: chaos_sinval_dummy，严禁指定业务表]");
    }
}
