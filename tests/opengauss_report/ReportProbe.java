import chaos.core.BaseFaultInject;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Integration probe: every SQLException comes from the connected server/driver. */
public class ReportProbe extends BaseFaultInject {
    private final String scenario;
    private static volatile byte[] allocation;
    ReportProbe(String scenario) { super("opengauss", "REPORT_" + scenario); this.scenario = scenario; }
    public void reportTest() { printTestReport(); }
    private void sql(Connection c, String text) throws SQLException {
        try (Statement s = c.createStatement()) { s.setQueryTimeout(8); s.execute(text); }
    }
    public void execute(String[] args) throws Exception {
        if (scenario.equals("client_oom")) { allocation = new byte[32 * 1024 * 1024]; return; }
        try (Connection c = getConnection()) {
            if (scenario.equals("baseline")) sql(c, "SELECT 1");
            else if (scenario.equals("worker_permission")) {
                Thread t = new Thread(() -> {
                    try (Connection worker = getConnection()) { sql(worker, "INSERT INTO public.protected_report VALUES (2)"); }
                    catch (SQLException e) { recordTestError(e); }
                });
                t.start(); t.join();
            } else if (scenario.equals("connection_limit")) {
                try (Connection second = getConnection()) { sql(second, "SELECT 1"); }
            } else if (scenario.equals("disk_full")) {
                sql(c, "CREATE TABLE report_disk (id int, val text) TABLESPACE report_small");
                sql(c, "ALTER TABLE report_disk ALTER COLUMN val SET STORAGE EXTERNAL");
                try {
                    for (int i = 0; i < 64; i++) sql(c, "INSERT INTO report_disk VALUES (" + i + ", repeat('x', 1048576))");
                    throw new AssertionError("Expected actual tablespace exhaustion");
                } catch (SQLException e) { recordTestError(e); }
                finally { sql(c, "DROP TABLE IF EXISTS report_disk"); }
            } else if (scenario.equals("missing_setting")) sql(c, "SET dbchaos_nonexistent_setting = 1");
            else if (scenario.equals("missing_table")) {
                try { sql(c, "INSERT INTO report_missing_table VALUES (1)"); }
                catch (SQLException e) { recordTestError(e); }
            } else if (scenario.equals("duplicate")) {
                try { sql(c, "INSERT INTO public.protected_report VALUES (1)"); }
                catch (SQLException e) { recordTestError(e); }
            } else if (scenario.equals("deadlock")) {
                try (Connection second = getConnection()) {
                    sql(c, "SET deadlock_timeout = '100ms'");
                    sql(second, "SET deadlock_timeout = '100ms'");
                    c.setAutoCommit(false); second.setAutoCommit(false);
                    sql(c, "UPDATE public.deadlock_report SET val=val+1 WHERE id=1");
                    sql(second, "UPDATE public.deadlock_report SET val=val+1 WHERE id=2");
                    Thread t = new Thread(() -> {
                        try { sql(second, "UPDATE public.deadlock_report SET val=val+1 WHERE id=1"); }
                        catch (SQLException e) { recordTestError(e); }
                        finally { try { second.rollback(); } catch (SQLException e) { recordTestError(e); } }
                    });
                    t.start();
                    try { sql(c, "UPDATE public.deadlock_report SET val=val+1 WHERE id=2"); }
                    catch (SQLException e) { recordTestError(e); }
                    finally { c.rollback(); }
                    t.join();
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(args[0])) { p.load(in); }
        overrideUrl = p.getProperty("url"); overrideUser = p.getProperty("user"); overridePassword = p.getProperty("password");
        try { new ReportProbe(args[1]).executeWithReport(new String[]{"-duration", "100"}); }
        catch (Exception | OutOfMemoryError e) { System.exit(1); }
    }
}
