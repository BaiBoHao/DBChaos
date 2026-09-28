package chaos.inject;

import java.sql.*;
import java.util.concurrent.ThreadLocalRandom;

/** Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md. */
public class UstoreUndoLagInject extends AdaptiveFaultInject {
    private int threads, scans, seedRows, hotspotRows;
    private boolean ustore, created;
    private final String table = "chaos_undo_" + Long.toHexString(System.nanoTime());
    public UstoreUndoLagInject(String dbType) { super(dbType, "USTORE_UNDO_LAG"); }
    protected void configure(String[] args) {
        logAdaptive("Detected " + querySettings("enable_ustore"));
        ustore = settingBool("enable_ustore", false);
        boolean enterprise = isEnterprise();
        int scanCap = ustore ? 8 : (enterprise ? 36 : 8);
        scans = clamp(scaleByMemory(2, scanCap), 2, Math.max(2, budget() / 4));
        int adaptiveThreads = ustore ? 8 : (enterprise ? 56 : 16);
        threads = clamp(Math.max(arg(args, "-threads", adaptiveThreads), adaptiveThreads), 2, budget() - scans - 2);
        // By concentrating updates on a compact set of hotspot rows under a REPEATABLE READ snapshot,
        // each data page accumulates hundreds of dead tuple versions (deep version chains), forcing
        // concurrent full-table scans to repeatedly perform heavy HeapTupleSatisfiesVisibility chain walks.
        hotspotRows = ustore ? 1 : (enterprise ? 100 : 200);
        seedRows = ustore ? 1 : (enterprise ? 20000 : 5000);
        logAdaptive((ustore ? "Native Ustore undo chain" : "Fallback: ASTORE concentrated hotspot version chain with REPEATABLE READ anchor") +
                "; updates=" + threads + "; scans=" + scans + "; seed rows=" + seedRows + "; hotspot rows=" + hotspotRows + "; payload=1280 bytes" +
                (enterprise ? " [Enterprise Deep Version Chain & Visibility Traversal Enabled]" : ""));
    }
    protected void startWork() throws Exception {
        try (Connection conn = open()) {
            sql(conn, "CREATE TABLE " + table + " (id int PRIMARY KEY, val bigint, pad text)" +
                    (ustore ? " WITH (storage_type=ustore)" : ""));
            created = true;
            sql(conn, "INSERT INTO " + table + " SELECT g, 0, repeat(md5(g::text),40) FROM generate_series(1," + seedRows + ") AS g");
            sql(conn, "ANALYZE " + table);
        }
        Connection anchor = open();
        anchor.setAutoCommit(false);
        anchor.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        if (sql(anchor, "SELECT val FROM " + table + " WHERE id=1") == 0)
            throw new SQLException("Snapshot anchor has no seed row");
        for (int i=0; i<threads; i++) worker(conn -> {
            int id = ThreadLocalRandom.current().nextInt(1, hotspotRows + 1);
            if (sql(conn, "UPDATE " + table + " SET val=val+1, pad=repeat(md5(random()::text),40) WHERE id=" + id) > 0)
                ok.incrementAndGet();
        });
        for (int i=0; i<scans; i++) worker(conn -> {
            sql(conn, "SELECT sum(val), sum(length(md5(pad))) FROM " + table);
            // Scans amplify visibility work; only updates count as effective undo/bloat work.
        });
    }
    protected void cleanup() throws Exception {
        if (created) try (Connection conn = open()) { sql(conn, "DROP TABLE " + table); }
    }

    @Override
    public void printHelp() {
        System.out.println("用例: ustore_undo_lag (Ustore 超长 Undo 链刺穿与后台回收滞后)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   高频原地更新线程数 [配置自适应，显式值受安全上限约束]");
    }
}
