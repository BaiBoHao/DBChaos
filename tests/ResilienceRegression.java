import chaos.core.BaseFaultInject;
import chaos.monitor.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static chaos.monitor.ResilienceAnalysis.*;

/** Numeric analysis and a live append-only business feed; never contacts a database. */
public class ResilienceRegression {
    static int checks;
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); checks++; }
    static void near(Double actual, double expected) { check(actual != null && Math.abs(actual - expected) < .00001, actual + " != " + expected); }
    static Sample sample(String phase, long start, long successes) { return new Sample(phase, start, start+1000, successes, 0, true, true); }
    static Result analyze(List<Sample> samples, boolean failed, String error) {
        return ResilienceAnalysis.analyze(100, 1000L, 4000L, samples, 3, 5, 95, 20, failed, error);
    }
    static void numeric() {
        List<Sample> samples = new ArrayList<Sample>(Arrays.asList(sample("fault",1000,80), sample("fault",2000,40), sample("fault",3000,60),
                sample("recovery",4000,97), sample("recovery",5000,97), sample("recovery",6000,97)));
        Result r = analyze(samples,false,null);
        near(r.pd,40); near(r.pr,97); near(r.dropPct,60); near(r.meanDropPct,40); near(r.restoredPct,97); near(r.regainedLossPct,95);
        check(r.td==3000 && r.tr==7000 && r.recovered && r.evidenceComplete,"wrong trough/recovery timestamps");
        check(r.isolation.contains("较大") && r.continuity.contains("每个完整"),"wrong impact/continuity");
        for(int i=3;i<6;i++) samples.set(i,sample("recovery",1000+i*1000,50));
        r=analyze(samples,false,null);
        check(r.tr==null && !r.recovered && r.recoveryStable && r.recovery.contains("未恢复"),"stable degraded plateau passed"); near(r.pr,50);
        samples.set(1,sample("fault",2000,0)); r=analyze(samples,false,null);
        near(r.pd,0); check(r.continuity.contains("无法仅据此"),"zero TPS was called database crash");
        check(!analyze(samples,true,null).evidenceComplete && analyze(samples,true,null).isolation.contains("注入执行失败"),"failed injection passed");
        check(!analyze(samples,true,null).recovered && analyze(samples,true,null).tr==null,"failed experiment claimed recovery");
        check(!analyze(samples,false,"lost feed").evidenceComplete,"bad telemetry passed");
        samples.set(1,new Sample("fault",2000,3000,0,0,true,false)); r=analyze(samples,false,null);
        check(r.pd==null && r.td==null && !r.evidenceComplete,"uncovered bucket became measured zero");
        r=analyze(Collections.singletonList(new Sample("fault",1000,1500,50,0,false,true)),false,null);
        check(r.pd==null,"partial interval became full TPS window");
        List<Sample> high=Arrays.asList(sample("fault",1000,100),sample("recovery",4000,100),sample("recovery",5000,100),sample("recovery",6000,100));
        check(analyze(high,false,null).regainedLossPct==null,"zero loss denominator was used");
        check(!stable(Arrays.asList(sample("recovery",0,100),sample("recovery",2000,100),sample("recovery",3000,100)),3,5),"non-consecutive windows passed");
        check(!ResilienceAnalysis.analyze(0,1000L,4000L,high,3,5,95,20,false,null).evidenceComplete,"zero baseline passed");
        List<Sample> errors=Arrays.asList(new Sample("fault",1000,2000,100,100,true,true));
        r=analyze(errors,false,null); near(r.faultErrorPct,50); check(r.isolation.contains("业务错误"),"high business error rate hidden");
        System.out.println("Numeric resilience checks passed: "+checks);
    }
    static void live(String mode, Path output) throws Exception {
        Files.createDirectories(output);
        Path feed=output.resolve("result.csv");
        long epoch=System.currentTimeMillis()-4000;
        StringBuilder rows=new StringBuilder("run,elapsed,latency,dblatency,ttype,rbk,dskipped,error\n");
        for(int i=0;i<400;i++) rows.append("1,").append(i*10).append(",1,1,NEW_ORDER,0,0,0\n");
        Files.write(feed,rows.toString().getBytes(StandardCharsets.UTF_8));
        if(!mode.equals("estimated")) Files.write(output.resolve("runInfo.csv"),("run,sessionStart\n1,"+new Timestamp(epoch)+"\n").getBytes(StandardCharsets.UTF_8));
        AtomicInteger phase=new AtomicInteger();
        Thread writer=new Thread(()->{
            int tick=0;
            try {
                while(!Thread.currentThread().isInterrupted()) {
                    int value=phase.get();
                    if(value!=3 && (value!=1 && value!=2 || tick%2==0)) {
                        long elapsed=System.currentTimeMillis()-epoch;
                        // Excluded background rows and actual failed records must not inflate successful TPS.
                        String line="1,"+elapsed+",1,1,NEW_ORDER,0,0,0\n"
                                +"1,"+elapsed+",1,1,DELIVERY_BG,0,0,0\n";
                        Files.write(feed,line.getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                    }
                    tick++; Thread.sleep(10);
                }
            } catch(InterruptedException stop) { Thread.currentThread().interrupt(); }
            catch(Exception e) { throw new RuntimeException(e); }
        });
        writer.start();
        try {
            final PerfBaselineMonitor.Baseline[] baseline=new PerfBaselineMonitor.Baseline[1];
            int status=PerfBaselineMonitor.run(new String[]{"-result-dir",output.toString(),"-window","1","-stable-windows","2",
                    "-min-wait","0","-max-wait","3","-poll","20"}, b->baseline[0]=b);
            check(status==0,"baseline failed");
            ResilienceMonitor.Config config=new ResilienceMonitor.Config();
            config.reportDir=output.resolve("report"); config.recoveryTimeoutMs=4500;
            if(mode.equals("partial_line")) config.recoveryTimeoutMs=500;
            BaseFaultInject fault=new BaseFaultInject("postgresql","LIVE_TEST") {
                protected java.sql.Connection getConnection() throws java.sql.SQLException {
                    throw new java.sql.SQLException("No database is used in this synthetic business-feed test");
                }
                public void reportTest(){printTestReport();}
                protected void preflight(String[] args) {
                    if(mode.equals("preflight_failure")) throw new IllegalArgumentException("controlled prerequisite failure");
                }
                public void execute(String[] args) throws Exception {
                    if(Arrays.asList("partial_line", "malformed", "mixed_runs").contains(mode)) {
                        writer.interrupt(); writer.join();
                    }
                    runPreflight(args);
                    if(mode.equals("execution_failure")) throw new IllegalStateException("controlled execution failure");
                    if(mode.equals("partial_line")) {
                        String line="1,"+(System.currentTimeMillis()-epoch)+",1,1,UPDATE,0,0,";
                        Files.write(feed,line.getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                        Thread.sleep(1100);
                        Files.write(feed,"0\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                        return;
                    }
                    if(mode.equals("malformed") || mode.equals("mixed_runs")) {
                        String line=mode.equals("malformed")?"1,NaN,1,1,UPDATE,0,0,0\n"
                                :"2,"+(System.currentTimeMillis()-epoch)+",1,1,UPDATE,0,0,0\n";
                        Files.write(feed,line.getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                        return;
                    }
                    if(mode.equals("truncated")) {
                        Files.write(feed,"run,elapsed,latency,dblatency,ttype,rbk,dskipped,error\n".getBytes(StandardCharsets.UTF_8));
                        phase.set(3); Thread.sleep(100); return;
                    }
                    phase.set(1); Thread.sleep(2200);
                    phase.set(mode.equals("stale")?3:mode.equals("degraded")?2:0);
                }
            };
            fault.observeBusiness(new ResilienceMonitor(baseline[0],config));
            try { fault.executeWithReport(new String[]{"-duration","2200"}); }
            catch(IllegalStateException e) { if(!mode.equals("preflight_failure") && !mode.equals("execution_failure")) throw e; }
            check(Files.isRegularFile(config.reportDir.resolve("throughput.csv")),"no series");
            check(Files.isRegularFile(config.reportDir.resolve("resilience.json")),"no JSON");
            check(Files.isRegularFile(config.reportDir.resolve("report.txt")),"no report");
        } finally {writer.interrupt();writer.join();}
    }
    public static void main(String[] args)throws Exception {
        if(args.length==0) numeric(); else live(args[0],Paths.get(args[1]));
    }
}
