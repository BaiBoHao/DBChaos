import chaos.core.BaseFaultInject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.sql.Connection;
import chaos.inject.MemoryPressureFault;
import chaos.inject.MassiveRollbackInject;

/** Exercises the report lifecycle without a database or external checker. */
public class TestReportRegression {
    static final class Fault extends BaseFaultInject {
        String mode;
        int reports;
        Fault(String mode) { super("postgresql", "REPORT_TEST"); this.mode = mode; }
        public void reportTest() { reports++; printTestReport(); }
        public void execute(String[] args) throws Exception {
            if (mode.equals("permission")) throw new SQLException("permission denied", "42501");
            if (mode.equals("memory")) throw new OutOfMemoryError("Java heap space");
            if (mode.equals("zero")) throw new TestExecutionException("EFFECTIVE_WORK_ZERO", 3);
            if (mode.equals("worker")) {
                Thread worker = new Thread(() -> recordTestError(new SQLException("no space left on device", "53100")));
                worker.start(); worker.join();
            }
            if (mode.equals("expected")) recordTestError(new SQLException("duplicate key", "23505"));
            if (mode.equals("missing_table")) recordTestError(new SQLException("relation does not exist", "42P01"));
            if (mode.equals("secret")) recordTestError(new SQLException(url + " " + password, "08006"));
        }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static String run(Fault fault, String... args) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));
            try { fault.executeWithReport(args); }
            catch (SQLException e) { check(fault.mode.equals("permission"), "unexpected SQL error"); }
            catch (OutOfMemoryError e) { check(fault.mode.equals("memory"), "unexpected memory error"); }
            catch (BaseFaultInject.TestExecutionException e) { check(e.getExitCode() == 3, "exit status lost"); }
        } finally { System.setOut(previous); }
        return output.toString("UTF-8");
    }
    static void setupFailure(BaseFaultInject fault) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));
            try {
                fault.executeWithReport(new String[]{"-duration", "100", "-batch", "1"});
                throw new AssertionError("setup failure was swallowed");
            } catch (SQLException expected) {
                check("42501".equals(expected.getSQLState()), "wrong setup failure");
            }
        } finally { System.setOut(previous); }
        String report = output.toString("UTF-8");
        check(report.contains("执行状态=执行失败") && report.contains("检查建议") && report.contains("权限"), report);
    }
    public static void main(String[] args) throws Exception {
        String[] options = {"-duration", "100"};
        Fault fault = new Fault("normal");
        check(run(fault, options).contains("执行状态=执行结束"), "missing normal report");
        check(fault.reports == 1, "report must run once");
        check(!run(new Fault("normal"), "--help").contains("[TestReport]"), "help produced report");
        String permission = run(new Fault("permission"), options);
        check(permission.contains("执行状态=执行失败") && permission.contains("42501") && permission.contains("权限"), permission);
        String worker = run(new Fault("worker"), options);
        check(worker.contains("异常记录数=1") && worker.contains("数据盘") && worker.contains("存在错误记录"), worker);
        String expected = run(new Fault("expected"), options);
        check(expected.contains("23505") && !expected.contains("检查建议") && !expected.contains("执行状态=执行失败"), expected);
        String missing = run(new Fault("missing_table"), options);
        check(missing.contains("42P01") && missing.contains("目标表") && missing.contains("search_path"), missing);
        check(run(new Fault("memory"), options).contains("JVM -Xmx"), "missing memory hint");
        check(run(new Fault("zero"), options).contains("EFFECTIVE_WORK_ZERO"), "missing zero-work report");
        fault.mode = "worker";
        run(fault, options);
        fault.mode = "normal";
        check(run(fault, options).contains("异常记录数=0"), "previous run leaked into report");
        setupFailure(new MemoryPressureFault("postgresql") {
            protected void preflight(String[] args) {}
            protected Connection getConnection() throws SQLException {
                throw new SQLException("permission denied", "42501");
            }
        });
        setupFailure(new MassiveRollbackInject("postgresql") {
            protected void preflight(String[] args) {}
            protected Connection getConnection() throws SQLException {
                throw new SQLException("permission denied", "42501");
            }
        });
        BaseFaultInject.overrideUrl = "jdbc:private:report-test";
        BaseFaultInject.overridePassword = "report-secret";
        String secret = run(new Fault("secret"), options);
        check(!secret.contains("jdbc:private:report-test") && !secret.contains("report-secret"), "credentials leaked");
        System.out.println("Test report regression checks passed");
    }
}
