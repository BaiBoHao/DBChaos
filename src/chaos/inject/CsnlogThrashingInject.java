package chaos.inject;

import java.sql.*;

/** Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md. */
public class CsnlogThrashingInject extends AdaptiveFaultInject {
    private int threads, anchors, subxidsPerTxn;
    public CsnlogThrashingInject(String dbType) { super(dbType, "CSNLOG_THRASHING"); }
    protected void configure(String[] args) {
        anchors = clamp(arg(args, "-anchors", 2), 1, Math.max(1, Math.min(scaleByMemory(1,8), budget()/4)));
        threads = threads(args, 4, 96, anchors);
        // A 6GB/2-core host cannot trim the CSN log / pg_subtrans churn of hundreds of
        // thousands of subXIDs inside the recovery window, so scale subtransactions per
        // committed transaction with memory (10 on 6GB, 50 on 96GB).
        long units = Math.max(1L, memoryKB() / 2097152);
        subxidsPerTxn = (int)Math.max(8L, Math.min(50L, 2 * units + 4));
        logAdaptive("Native XID/subXID allocation via writes + JDBC savepoints; threads=" + threads + "; anchors=" + anchors +
                "; subxidsPerTxn=" + subxidsPerTxn);
    }
    protected void startWork() throws Exception {
        for (int i=0; i<anchors; i++) {
            Connection anchor = open();
            anchor.setAutoCommit(false);
            anchor.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            sql(anchor, "SELECT txid_current()");
        }
        for (int i=0; i<threads; i++) {
            final boolean[] initialized = {false};
            worker(conn -> {
                if (!initialized[0]) {
                    sql(conn, "CREATE TEMP TABLE chaos_xid_burn (id int)");
                    initialized[0] = true;
                }
                conn.setAutoCommit(false);
                try {
                    int writes = 0;
                    for (int n=0; n<subxidsPerTxn && running; n++) {
                        sql(conn, "SAVEPOINT chaos_sp");
                        sql(conn, "INSERT INTO chaos_xid_burn VALUES (1)");
                        writes++;
                        sql(conn, "RELEASE SAVEPOINT chaos_sp");
                    }
                    sql(conn, "DELETE FROM chaos_xid_burn");
                    sql(conn, "COMMIT");
                    if (writes > 0) ok.incrementAndGet(); // One committed write transaction, never 50 inferred successes.
                } catch (SQLException e) {
                    failure(e);
                    sql(conn, "ROLLBACK");
                }
            });
        }
    }

    @Override
    public void printHelp() {
        System.out.println("用例: csnlog_thrashing (CSNLog SLRU 页面淘汰争抢与提交护航震荡)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>   持续时间(毫秒) [必填]");
        System.out.println("  -threads <num>   XID 消耗线程数 [配置自适应，显式值受安全上限约束]");
        System.out.println("  -anchors <num>   快照锚点数 [配置自适应，显式值受安全上限约束]");
    }
}
