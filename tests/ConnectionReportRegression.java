import chaos.inject.MaxConnectionInject;
import chaos.monitor.OperationMetrics;
import java.io.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Concurrent counters, real injector lifecycles and SQL failures through fake JDBC. */
public class ConnectionReportRegression {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    interface Handler { Object call(Method method, Object[] args) throws Throwable; }
    static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (p,m,a) -> {
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            return handler.call(m, a);
        }));
    }
    static class Fault extends MaxConnectionInject {
        final AtomicInteger live = new AtomicInteger(), opens = new AtomicInteger();
        volatile boolean deny, block, poolOn = true, statementCloseFailure, ignoreCancellation, connectionCloseFailure, hidePool, monitorAvailable;
        final AtomicBoolean unblock = new AtomicBoolean();
        volatile boolean abortWorks, closeWaitsForAbort;
        final AtomicInteger abortCalls = new AtomicInteger();
        Fault() { super("opengauss"); }
        protected synchronized Connection getConnection() throws SQLException {
            // First connection of each execution is the configuration reader.
            int n = opens.incrementAndGet();
            if (n > 1 && deny) throw new SQLException("bad credentials", "28P01");
            if (n > 1 && live.get() >= 3) throw new SQLException("capacity", "53300");
            live.incrementAndGet();
            AtomicBoolean closed = new AtomicBoolean();
            return proxy(Connection.class, (m,a) -> {
                if (m.getName().equals("close")) {
                    if (closeWaitsForAbort && n > 1) {
                        while (!unblock.get()) {
                            try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                        }
                    }
                    if (connectionCloseFailure && opens.get() > 1) throw new SQLException("connection close failed", "08006");
                    if ((!ignoreCancellation || n == 1) && closed.compareAndSet(false, true)) live.decrementAndGet();
                    return null;
                }
                if (m.getName().equals("abort") && abortWorks) {
                    abortCalls.incrementAndGet();
                    if (closed.compareAndSet(false, true)) live.decrementAndGet();
                    unblock.set(true);
                    return null;
                }
                if (m.getName().equals("isClosed")) return closed.get();
                if (m.getName().equals("createStatement")) return statement(closed);
                return zero(m.getReturnType());
            });
        }
        Statement statement(AtomicBoolean closed) {
            AtomicBoolean cancelled = new AtomicBoolean();
            return proxy(Statement.class, (m,a) -> {
                if (m.getName().equals("executeQuery")) {
                    String sql = (String)a[0];
                    if (hidePool && sql.startsWith("SHOW thread_pool_attr")) throw new SQLException("must be superuser", "42501");
                    if (sql.startsWith("SELECT dbchaos_monitor.") && !monitorAvailable) throw new SQLException("function missing", "42883");
                    String value = sql.contains("max_connections") ? "8" : sql.contains("enable_thread_pool")
                            ? poolOn ? "on" : "off" : "2,1,(nobind)";
                    AtomicBoolean next = new AtomicBoolean(true);
                    return proxy(ResultSet.class, (rm,ra) -> {
                        if (rm.getName().equals("next")) return next.getAndSet(false);
                        if (rm.getName().equals("getString")) return value;
                        if (rm.getName().equals("getInt")) return Integer.parseInt(value);
                        return zero(rm.getReturnType());
                    });
                }
                if (m.getName().equals("cancel")) { cancelled.set(true); return null; }
                if (m.getName().equals("close") && statementCloseFailure && opens.get() > 1)
                    throw new SQLException("close failed", "08006");
                if (m.getName().equals("execute")) {
                    if (block) {
                        while (!unblock.get() && (ignoreCancellation || (!closed.get() && !cancelled.get()))) {
                            try { Thread.sleep(5); }
                            catch (InterruptedException e) { if (!ignoreCancellation) throw new SQLException("cancelled", "57014"); }
                        }
                        throw new SQLException("cancelled", "57014");
                    }
                    try { Thread.sleep(2); } catch (InterruptedException e) { throw new SQLException("cancelled", "57014"); }
                    return true;
                }
                return zero(m.getReturnType());
            });
        }
    }
    static String run(Fault fault, String mode, String duration, String count) throws Exception {
        return run(fault, mode, duration, count, false);
    }
    static String run(Fault fault, String mode, String duration, String count, boolean expectFailure) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(bytes, true, "UTF-8"));
            boolean failed = false;
            try { fault.executeWithReport(new String[]{"-mode",mode,"-duration",duration,"-count",count}); }
            catch (IllegalStateException error) { if (!expectFailure) throw error; failed = true; }
            check(failed == expectFailure, "wrong failure status");
        } finally { System.setOut(previous); }
        return bytes.toString("UTF-8");
    }
    static void counters() throws Exception {
        OperationMetrics empty = new OperationMetrics();
        check(Double.isNaN(empty.snapshot().successPct()), "empty is not 0% or 100%");
        check(Double.isNaN(empty.snapshot().p95UpperMs), "empty latency");
        OperationMetrics m = new OperationMetrics();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int t=0;t<8;t++) pool.execute(() -> {
            for (int i=0;i<1000;i++) {
                m.begin();
                if (i%4==0) m.failure(3000000, new SQLException("full", "08004", 1040));
                else m.success(1000000);
            }
        });
        pool.shutdown(); check(pool.awaitTermination(5, TimeUnit.SECONDS), "counter timeout");
        OperationMetrics.Snapshot s = m.snapshot();
        check(s.attempts==8000 && s.successes==6000 && s.failures==2000 && s.unfinished()==0, "lost concurrent counters");
        check(s.successPct()==75 && s.capacityFailures==2000 && s.sqlStates.get("08004")==2000, "wrong percentages/classification");
        check(s.meanMs==1.5 && s.maxMs==3 && s.p95UpperMs>=3 && s.p95UpperMs<6, "latency histogram");
        m.begin(); check(m.snapshot().unfinished()==1, "pending operation missing");
        check(s.completedPerSecond(2)==4000, "rate denominator");
    }
    public static void main(String[] args) throws Exception {
        counters();
        check(MaxConnectionInject.parseThreadPoolSize("64,2,(nobind)",100)==64,"numeric threads must not multiply groups");
        check(MaxConnectionInject.parseThreadPoolSize("192,4,(nobind)",100)==100,"pool must respect connections");
        check(MaxConnectionInject.parseThreadPoolSize("group=4, per_group=8",100)==32,"legacy parsing");
        check(MaxConnectionInject.parseThreadPoolSize(null,100)==0,"unknown pool");
        Fault f = new Fault();
        String storm = run(f,"conn_storm","100","5");
        check(storm.contains("建连风暴") && storm.contains("容量拒绝=") && storm.contains("SQLState="),storm);
        check(storm.contains("清理完成=true") && f.live.get()==0 && !storm.contains("线程池配置来源"),storm);
        f.opens.set(0);
        String exhaustion = run(f,"conn_exhaustion","100","5");
        check(exhaustion.contains("本地持有峰值=3") && exhaustion.contains("目标达成率=60.000%"),exhaustion);
        check(exhaustion.contains("占配置max_connections比例=37.500%") && f.live.get()==0,exhaustion);
        f.opens.set(0); f.deny=true;
        String denied = run(f,"conn_storm","50","2",true);
        check(denied.contains("没有成功建连") && !denied.contains("已产生连接周转"),denied);
        check(denied.contains("容量拒绝=0") && denied.contains("28P01"),denied);
        f.opens.set(0); f.deny=false; f.poolOn=false;
        String off = run(f,"thread_saturation","1000","1",true);
        check(off.contains("enable_thread_pool未开启") && off.contains("未启动故障注入"),off);
        f.opens.set(0); f.poolOn=true;
        String sat = run(f,"thread_saturation","1000","1");
        check(sat.contains("配置线程池=2") && sat.contains("目标并发/连接=3"),sat);
        check(sat.contains("在途包含服务器排队") && sat.contains("不等于业务新连接成功率"),sat);
        Fault hidden = new Fault(); hidden.hidePool=true;
        String unavailable = run(hidden,"thread_saturation","1000","1",true);
        check(unavailable.contains("只读监控函数不可用") && unavailable.contains("未启动故障注入") && hidden.opens.get()==1,unavailable);
        hidden.opens.set(0); hidden.monitorAvailable=true;
        String delegated = run(hidden,"thread_saturation","1000","1");
        check(delegated.contains("配置来源=只读监控函数") && delegated.contains("配置线程池=2")
                && !delegated.contains("42501"),delegated);
        Fault close = new Fault(); close.statementCloseFailure=true;
        String closeReport = run(close,"conn_storm","40","1",true);
        check(closeReport.contains("SQL 尝试=") && closeReport.contains("SQLState={08006="),closeReport);
        java.util.regex.Matcher counts = java.util.regex.Pattern.compile("SQL 尝试=(\\d+) 成功=(\\d+) 失败=(\\d+) 未完成=(\\d+)").matcher(closeReport);
        check(counts.find(), closeReport);
        check(Long.parseLong(counts.group(1)) == Long.parseLong(counts.group(2)) + Long.parseLong(counts.group(3))
                + Long.parseLong(counts.group(4)), "statement close double-counted");
        // Bounded cleanup when an in-flight SQL blocks until cancellation.
        Fault blocking = new Fault(); blocking.block=true;
        long start = System.nanoTime();
        String cancelled = run(blocking,"thread_saturation","1000","1");
        check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<10,"cleanup did not bound the run");
        check(cancelled.contains("清理完成=true") && blocking.live.get()==0,cancelled);
        // Real drivers can serialize close behind an outstanding query. Abort
        // must break the transport even when both cancel and close cannot help.
        Fault aborting = new Fault(); aborting.block=true; aborting.ignoreCancellation=true;
        aborting.abortWorks=true; aborting.closeWaitsForAbort=true;
        String aborted = run(aborting,"thread_saturation","1000","1");
        check(aborted.contains("清理完成=true") && aborting.abortCalls.get()>0
                && aborting.live.get()==0,aborted);
        Fault failedClose = new Fault(); failedClose.connectionCloseFailure = true;
        String partial = run(failedClose,"conn_storm","50","1",true);
        check(partial.contains("清理完成=false") && partial.contains("故障撤销未确认") && partial.contains("完成速率=N/A"),partial);
        Fault stuck = new Fault(); stuck.block=true; stuck.ignoreCancellation=true;
        try {
            String unfinished = run(stuck,"thread_saturation","1000","1",true);
            check(unfinished.contains("当前统计为部分结果") && unfinished.contains("清理完成=false"),unfinished);
        } finally { stuck.ignoreCancellation=false; stuck.unblock.set(true); }
        // Empty / invalid runs must not connect or imply 100% availability.
        Fault invalid = new Fault();
        try { run(invalid,"conn_storm","0","1"); throw new AssertionError("invalid duration accepted"); }
        catch(IllegalArgumentException expected) { check(invalid.opens.get()==0,"invalid connected"); }
        System.out.println("Connection report regression checks passed");
    }
}
