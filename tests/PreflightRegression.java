import chaos.core.BaseFaultInject;
import chaos.core.PreflightChecks;
import chaos.inject.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** No database is contacted; proxies only allow isolated preflight objects. */
public class PreflightRegression {
    static int checks;
    static int mutations;
    static boolean brokenSnapshot, rejectOptions, rejectPrepare, losePrepared, rejectDrop, rejectScan;
    static boolean probePrepared;
    static String xaGid;
    static int fixtureVersion;
    static Set<String> fixtures = new HashSet<String>();
    static Map<String, String> settings = new LinkedHashMap<String, String>();
    static int isolation = Connection.TRANSACTION_REPEATABLE_READ;
    static long used = 4, rows = 1000, prepared = 0;

    static void reset() {
        if (!fixtures.isEmpty() || probePrepared) throw new AssertionError("Leaked preflight objects");
        brokenSnapshot = rejectOptions = rejectPrepare = losePrepared = rejectDrop = rejectScan = false;
        settings.clear();
        settings.put("max_connections", "100");
        settings.put("superuser_reserved_connections", "3");
        settings.put("idle_in_transaction_session_timeout", "0");
        settings.put("transaction_timeout", "0");
        settings.put("deadlock_timeout", "1s");
        settings.put("autovacuum", "on");
        settings.put("track_counts", "on");
        settings.put("max_prepared_transactions", "10");
        isolation = Connection.TRANSACTION_REPEATABLE_READ;
        used = 4; rows = 1000; prepared = 0;
    }

    static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    static ResultSet result(List<String[]> data) {
        final int[] index = {-1};
        return (ResultSet) proxy(ResultSet.class, (p, m, a) -> {
            if (m.getName().equals("next")) return ++index[0] < data.size();
            if (m.getName().equals("getString")) return data.get(index[0])[(Integer)a[0] - 1];
            if (m.getName().equals("getBytes")) return data.get(index[0])[(Integer)a[0] - 1].getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (m.getName().equals("close")) return null;
            throw new AssertionError("Unexpected ResultSet call " + m.getName());
        });
    }

    static ResultSet query(String sql, int[] snapshot) throws SQLException {
        if (rejectScan && sql.startsWith("SELECT group_id, count(*) FROM chaos_preflight_")) {
            throw new SQLException("Unsupported range grouping SQL");
        }
        if (sql.equals("XA RECOVER")) {
            return result(probePrepared && !losePrepared
                    ? Collections.singletonList(new String[]{"1", "1", "0", xaGid}) : Collections.<String[]>emptyList());
        }
        if (sql.equals("SELECT name, setting, unit FROM pg_settings")) {
            List<String[]> data = new ArrayList<String[]>();
            for (Map.Entry<String, String> entry : settings.entrySet()) data.add(new String[]{entry.getKey(), entry.getValue(), null});
            return result(data);
        }
        String value;
        if (sql.startsWith("SELECT version FROM chaos_preflight_")) {
            if (snapshot[0] < 0) snapshot[0] = fixtureVersion;
            value = String.valueOf(brokenSnapshot ? fixtureVersion : snapshot[0]);
        } else if (sql.contains("FROM chaos_preflight_")) value = "1";
        else if (sql.equals("SHOW statement_timeout")) value = "5s";
        else if (sql.contains("pg_prepared_xacts WHERE gid")) value = probePrepared && !losePrepared ? "1" : "0";
        else if (sql.contains("rolconnlimit")) value = "-1";
        else if (sql.contains("pg_stat_activity")) value = String.valueOf(used);
        else if (sql.contains("pg_prepared_xacts")) value = String.valueOf(prepared);
        else if (sql.contains("preflight_rows")) {
            String limit = sql.substring(sql.indexOf(" LIMIT ") + 7).split("\\)")[0];
            value = String.valueOf(Math.min(rows, Long.parseLong(limit)));
        } else if (sql.startsWith("SELECT @@")) value = settings.get(sql.substring(9));
        else throw new AssertionError("Unexpected query: " + sql);
        return result(Collections.singletonList(new String[]{value}));
    }

    static Connection connection() {
        final int[] snapshot = {-1};
        return (Connection) proxy(Connection.class, (p, m, a) -> {
            if (m.getName().equals("close") || m.getName().equals("setAutoCommit") || m.getName().equals("rollback")) return null;
            if (m.getName().equals("getTransactionIsolation")) return isolation;
            if (m.getName().equals("createStatement")) return proxy(Statement.class, (sp, sm, sa) -> {
                if (sm.getName().equals("close") || sm.getName().equals("setQueryTimeout")) return null;
                if (sm.getName().equals("executeQuery")) return query((String)sa[0], snapshot);
                if (sm.getName().equals("execute")) {
                    String sql = (String)sa[0];
                    if (sql.startsWith("XA START 'chaos_preflight_")) {
                        xaGid = sql.substring(sql.indexOf("'") + 1, sql.lastIndexOf("'"));
                    } else if (sql.startsWith("XA END 'chaos_preflight_")) { }
                    else if (sql.startsWith("XA PREPARE 'chaos_preflight_")) {
                        if (rejectPrepare) throw new SQLException("XA permission denied");
                        probePrepared = true;
                    } else if (sql.startsWith("XA ROLLBACK 'chaos_preflight_")) probePrepared = false;
                    else if (sql.equals("SELECT 1")) { }
                    else if (sql.startsWith("CREATE TABLE chaos_preflight_")) {
                        fixtures.add(sql.split(" ")[2]); fixtureVersion = 1;
                    } else if (sql.startsWith("DROP TABLE chaos_preflight_")) {
                        if (rejectDrop) throw new SQLException("DROP permission denied");
                        fixtures.remove(sql.split(" ")[2]);
                    }
                    else if (sql.startsWith("CREATE INDEX idx_chaos_preflight_")) { }
                    else if (sql.startsWith("INSERT INTO chaos_preflight_")) fixtureVersion = 1;
                    else if (sql.startsWith("UPDATE chaos_preflight_")) fixtureVersion = 2;
                    else if (sql.startsWith("ALTER TABLE chaos_preflight_")) {
                        if (rejectOptions) throw new SQLException("Unsupported table vacuum options");
                    } else if (sql.startsWith("BEGIN; SELECT txid_current(); PREPARE TRANSACTION 'chaos_preflight_")) {
                        if (rejectPrepare) throw new SQLException("PREPARE permission denied");
                        probePrepared = true;
                    } else if (sql.startsWith("ROLLBACK PREPARED 'chaos_preflight_")) probePrepared = false;
                    else if (sql.equals("ROLLBACK") || sql.equals("SET statement_timeout = '5s'")) { }
                    else { mutations++; throw new AssertionError("Unexpected mutation: " + sql); }
                    return false;
                }
                mutations++;
                throw new AssertionError("Unexpected statement call: " + sm.getName());
            });
            mutations++;
            throw new AssertionError("Unexpected connection call: " + m.getName());
        });
    }

    static void fails(BaseFaultInject fault, String expected, String... args) throws Exception {
        try { fault.execute(args); throw new AssertionError("Expected rejection: " + expected); }
        catch (IllegalStateException e) {
            if (!e.getMessage().contains("[Preflight]") || !e.getMessage().contains(expected)) throw e;
        }
        if (mutations != 0) throw new AssertionError("Unexpected setup/cleanup");
        checks++;
    }

    static void passes(BaseFaultInject fault, String... args) throws Exception {
        Method preflight = fault.getClass().getSuperclass().getDeclaredMethod("preflight", String[].class);
        preflight.setAccessible(true);
        try { preflight.invoke(fault, (Object)args); }
        catch (InvocationTargetException e) { throw new AssertionError(e.getCause()); }
        checks++;
    }

    public static void main(String[] args) throws Exception {
        reset();
        ReadAmpTrapInject read = new ReadAmpTrapInject("postgresql") { protected Connection getConnection() { return connection(); } };
        MvccBloatInject bloat = new MvccBloatInject("postgresql") { protected Connection getConnection() { return connection(); } };
        UncommittedTxnInject lock = new UncommittedTxnInject("postgresql") { protected Connection getConnection() { return connection(); } };
        DeadlockStormInject deadlock = new DeadlockStormInject("postgresql") { protected Connection getConnection() { return connection(); } };
        DuplicateTxnInject duplicate = new DuplicateTxnInject("postgresql") { protected Connection getConnection() { return connection(); } };
        MaxPreparedInject prep = new MaxPreparedInject("postgresql") { protected Connection getConnection() { return connection(); } };
        MemoryPressureFault memory = new MemoryPressureFault("postgresql") { protected Connection getConnection() { return connection(); } };
        MassiveRollbackInject rollback = new MassiveRollbackInject("postgresql") { protected Connection getConnection() { return connection(); } };

        for (String mode : new String[]{"seq", "index", "mixed"}) passes(read, "-duration", "30000", "-scan-mode", mode);
        for (String mode : new String[]{"UPDATE", "INSERT"}) passes(duplicate, "-duration", "30000", "-mode", mode);
        passes(bloat, "-duration", "30000");
        passes(lock, "-duration", "30000", "-table", "public.test");
        passes(deadlock, "-duration", "30000");
        passes(prep, "-duration", "30000");
        passes(memory, "-duration", "30000", "-batch", "1");
        passes(rollback, "-duration", "30000");
        fails(read, "-warmup", "-duration", "100");
        fails(read, "-scan-mode", "-duration", "30000", "-scan-mode", "invalid");
        isolation = Connection.TRANSACTION_READ_COMMITTED;
        fails(read, "隔离级别", "-duration", "30000");
        fails(bloat, "隔离级别", "-duration", "30000");
        reset(); settings.put("idle_in_transaction_session_timeout", "20s");
        fails(lock, "idle_in_transaction_session_timeout", "-duration", "30000", "-table", "test");
        reset(); rows = 999;
        fails(lock, "行数不足", "-duration", "30000", "-table", "test");
        reset();
        fails(deadlock, "-hot-rows", "-duration", "30000", "-hot-rows", "32");
        settings.put("deadlock_timeout", "6s");
        fails(deadlock, "deadlock_timeout", "-duration", "30000");
        reset(); used = 98;
        fails(duplicate, "连接余量", "-duration", "30000");
        reset(); settings.put("max_prepared_transactions", "0");
        fails(prep, "Prepared", "-duration", "30000");
        reset(); prepared = 10;
        fails(prep, "Prepared", "-duration", "30000");
        reset(); settings.remove("autovacuum");
        fails(bloat, "autovacuum", "-duration", "30000");
        reset(); settings.put("autovacuum", "off");
        fails(bloat, "autovacuum", "-duration", "30000");
        fails(memory, "-batch", "-duration", "30000", "-batch", "2048");
        fails(memory, "-threads", "-duration", "30000", "-threads", "0");
        for (String rate : new String[]{"NaN", "Infinity", "-0.1", "1.1"}) fails(rollback, "-rate", "-duration", "30000", "-rate", rate);
        reset(); brokenSnapshot = true;
        fails(read, "未能保留", "-duration", "30000");
        reset(); rejectOptions = true;
        fails(bloat, "vacuum options", "-duration", "30000");
        reset(); rejectScan = true;
        fails(read, "range grouping SQL", "-duration", "30000");
        reset(); rejectDrop = true;
        fails(read, "探测表清理失败", "-duration", "30000");
        if (fixtures.isEmpty()) throw new AssertionError("Expected the failed-cleanup fixture to remain");
        fixtures.clear(); // simulated operator cleanup, no database is involved
        reset(); rejectPrepare = true;
        fails(prep, "permission denied", "-duration", "30000");
        reset(); losePrepared = true;
        fails(prep, "实际 Prepared 记录", "-duration", "30000");
        reset();
        new chaos.core.PreflightProbes(PreflightRegression::connection, "mysql").prepared(); checks++;
        losePrepared = true;
        try {
            new chaos.core.PreflightProbes(PreflightRegression::connection, "mysql").prepared();
            throw new AssertionError("Empty XA was accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        reset();
        settings.put("default_storage_engine", "MyISAM");
        try (PreflightChecks c = new PreflightChecks(connection(), "mysql")) {
            try { c.transactionalEngine(); throw new AssertionError("engine accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        settings.put("max_allowed_packet", "1024");
        try (PreflightChecks c = new PreflightChecks(connection(), "mysql")) {
            try { c.packet(1024); throw new AssertionError("packet accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        if (PreflightChecks.milliseconds("2min") != 120000 || PreflightChecks.milliseconds("5ms") != 5) throw new AssertionError("units");
        BaseFaultInject noop = new BaseFaultInject("postgresql", "NOOP") {
            public void execute(String[] a) throws Exception { runPreflight(a); }
            public void reportTest() { printTestReport(); }
            protected Connection getConnection() { throw new AssertionError("Default hook accessed DB"); }
        };
        noop.execute(new String[0]); checks++;
        System.out.println("Preflight regression checks passed: " + checks);
    }
}
