package chaos.inject;

import java.sql.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ustore undo-lag. Two modes; see ADAPTIVE_NOTES.md.
 *
 * undo (native, requires enable_ustore=on): the fault is the undo-lag PRINCIPLE, not CPU contention.
 * Updaters overwrite a compact hot set in place; every overwrite pushes the prior version into undo.
 * REPEATABLE READ readers pin a snapshot taken before the churn -- that freezes OldestXmin so the undo
 * recycler (GetOldestXminForUndo) cannot advance -- and then re-read the overwritten rows. Under a frozen
 * snapshot each page version is too new, so the read path must rebuild the visible version from undo
 * (UHeapTupleFetch -> UHeapTupleSatisfies returns UVERSION_OLDER -> undo lookup), turning the pinned undo
 * chain into read-path I/O and buffer-pool pressure. Volume (hot rows x payload) is scaled with memory so
 * larger instances accumulate a larger unreclaimable undo working set -- the lever is data/IO volume, not
 * more spinning threads.
 *
 * fallback (enable_ustore=off): ASTORE concentrated hotspot version chain with a REPEATABLE READ anchor.
 */
public class UstoreUndoLagInject extends AdaptiveFaultInject {
    private int threads, scans, readers, seedRows, hotRows;
    private boolean ustore, created;
    private final String table = "chaos_undo_" + Long.toHexString(System.nanoTime());
    public UstoreUndoLagInject(String dbType) { super(dbType, "USTORE_UNDO_LAG"); }
    protected void configure(String[] args) {
        logAdaptive("Detected " + querySettings("enable_ustore", "enable_thread_pool", "thread_pool_attr",
                "max_connections", "max_process_memory"));
        ustore = settingBool("enable_ustore", false);
        boolean enterprise = isEnterprise();
        String mode = getArg(args, "-mode");
        if (mode != null && mode.equalsIgnoreCase("undo") && !ustore)
            throw new IllegalStateException("-mode undo requires enable_ustore=on (undo chains cannot exist otherwise)");
        if (mode != null && mode.equalsIgnoreCase("fallback")) ustore = false;

        // Environment probe. The thread pool is the operative concurrency limit: the kernel caps it at
        // max_connections (ThreadPoolControler::ConstrainThreadNum), and the pool grows with CPU cores
        // (thread_pool_attr token[0]). `capacity` is the concurrency the instance can absorb, so a larger
        // instance receives proportionally more undo pressure instead of the same fixed load.
        boolean pool = settingBool("enable_thread_pool", false);
        String[] attr = settingStr("thread_pool_attr", "16,2,(nobind)").split(",");
        int poolSize = 16;
        try { poolSize = (int)Math.min(4096, Math.max(1, (long)Integer.parseInt(attr[0].trim()))); }
        catch (RuntimeException ignored) { logAdaptive("Unparseable thread_pool_attr; assuming 16 workers"); }
        poolSize = Math.min(poolSize, Math.max(1, settingInt("max_connections", 100)));
        int capacity = pool ? poolSize : clamp(scaleByMemory(8, 128), 8, 128);
        logAdaptive("Environment probe: thread_pool=" + (pool ? poolSize : "off") + "; capacity=" + capacity +
                "; memoryUnits=" + memoryUnits() + "; budget=" + budget());

        // Undo volume (hotRows) scales with memory; updater count scales with concurrency capacity.
        int adaptiveThreads = clamp(capacity / 8, 8, 32);
        threads = clamp(Math.max(arg(args, "-threads", adaptiveThreads), adaptiveThreads), 2, Math.max(2, budget() - 3));

        if (ustore) {
            hotRows = clamp(scaleByMemory(512, 24576), 512, 200000);
            seedRows = hotRows;
            int readerCeiling = Math.max(1, budget() - threads - 2);
            readers = clamp(capacity - threads - 2, 1, readerCeiling);
            if (pool && readers < capacity - threads - 2)
                logAdaptive("Connection budget below pool size; readers capped at " + readers +
                        " (raise max_connections or expect the closed loop to escalate)");
            logAdaptive("Native Ustore undo-lag (adaptive undo reconstruction); updates=" + threads +
                    "; snapshot readers=" + readers + "; hot rows=" + hotRows + "; payload=1280 bytes");
        } else {
            int scanCap = enterprise ? 36 : 8;
            scans = clamp(scaleByMemory(2, scanCap), 2, Math.max(2, budget() / 4));
            hotRows = enterprise ? 100 : 200;
            seedRows = enterprise ? 20000 : 5000;
            if (budget() - scans - threads < 1)
                throw new IllegalStateException("Insufficient session budget for update/anchor/scan sessions");
            logAdaptive((enterprise ? "ASTORE concentrated hotspot version chain with REPEATABLE READ anchor"
                    : "Fallback: ASTORE concentrated hotspot version chain with REPEATABLE READ anchor") +
                    "; updates=" + threads + "; scans=" + scans + "; seed rows=" + seedRows +
                    "; hotspot rows=" + hotRows + "; payload=1280 bytes" +
                    (enterprise ? " [Enterprise Deep Version Chain & Visibility Traversal Enabled]" : ""));
        }
    }
    protected void startWork() throws Exception {
        try (Connection conn = open()) {
            sql(conn, "CREATE TABLE " + table + " (id int PRIMARY KEY, val bigint, pad text)" +
                    (ustore ? " WITH (storage_type=ustore)" : ""));
            created = true;
            sql(conn, "INSERT INTO " + table + " SELECT g, 0, repeat(md5(g::text),40) FROM generate_series(1," + seedRows + ") AS g");
            sql(conn, "ANALYZE " + table);
        }
        if (ustore) startNativeUndo(); else startFallback();
    }

    /** Native ustore undo-lag: deep undo chains that cannot be recycled, read back through a frozen snapshot. */
    private void startNativeUndo() {
        for (int i=0; i<threads; i++) worker(conn -> {
            int id = ThreadLocalRandom.current().nextInt(1, hotRows + 1);
            if (sql(conn, "UPDATE " + table + " SET val=val+1, pad=repeat(md5(random()::text),40) WHERE id=" + id) > 0)
                ok.incrementAndGet();
        });
        // One snapshot per reader, fixed before any overwrite lands and never committed (so OldestXmin
        // stays pinned and the undo recycler cannot advance across the whole run).
        final ThreadLocal<Boolean> primed = new ThreadLocal<>();
        for (int i=0; i<readers; i++) worker(conn -> {
            if (primed.get() == null) {
                conn.setAutoCommit(false);
                conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                sql(conn, "SELECT val FROM " + table + " WHERE id=1"); // fix the read snapshot pre-churn
                primed.set(Boolean.TRUE);
            }
            // Each pass must rebuild every overwritten row from undo; this is the injected cost.
            sql(conn, "SELECT sum(val), count(*) FROM " + table);
        });
    }

    /** ASTORE fallback: same shape as the historical case (no undo path exists without enable_ustore). */
    private void startFallback() throws Exception {
        Connection anchor = open();
        anchor.setAutoCommit(false);
        anchor.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        if (sql(anchor, "SELECT val FROM " + table + " WHERE id=1") == 0)
            throw new SQLException("Snapshot anchor has no seed row");
        for (int i=0; i<threads; i++) worker(conn -> {
            int id = ThreadLocalRandom.current().nextInt(1, hotRows + 1);
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
        System.out.println("  -mode undo|fallback  注入模式；默认按 enable_ustore 自动选择");
        System.out.println("                   undo: 原生 ustore undo 链 + 钉快照读者(需 enable_ustore=on)");
        System.out.println("                   fallback: ASTORE 热点版本链回退");
        System.out.println("  -threads <num>   高频原地更新线程数 [配置自适应，显式值受安全上限约束]");
    }
}
