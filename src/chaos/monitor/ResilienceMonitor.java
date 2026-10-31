package chaos.monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static chaos.monitor.ResilienceAnalysis.Sample;

/** Incremental BenchmarkSQL observer spanning baseline, fault and bounded recovery. */
public final class ResilienceMonitor {
    public static final class Config {
        public long recoveryTimeoutMs = 180000;
        public double recoveryPct = 95, isolationDropPct = 20;
        public Path reportDir;
        public void validate() {
            if (recoveryTimeoutMs <= 0 || !Double.isFinite(recoveryPct) || recoveryPct <= 0 || recoveryPct > 100
                    || !Double.isFinite(isolationDropPct) || isolationDropPct < 0 || isolationDropPct > 100)
                throw new IllegalArgumentException("恢复超时必须为正数；恢复比例须在 (0,100]，隔离下降阈值须在 [0,100]");
        }
    }

    private final PerfBaselineMonitor.Baseline baseline;
    private final Config config;
    private final Path output;
    private final RandomAccessFile input;
    private final Object fileKey;
    private final List<Sample> baselineSamples = new ArrayList<Sample>();
    private final Map<Long, long[]> fault = new TreeMap<Long, long[]>(), recovery = new TreeMap<Long, long[]>();
    private final Map<Long, Long> baselineErrors = new HashMap<Long, Long>();
    private final Map<String, Integer> columns = new HashMap<String, Integer>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "business-throughput-observer"); t.setDaemon(true); return t;
    });
    private long epoch, latestElapsed = -1;
    private Long started, stopped;
    private String runId, alignment, monitoringError;
    private boolean exactAlignment;
    private boolean headerSeen, finished;
    private ResilienceAnalysis.Result result;
    private List<Sample> finalSamples;
    private Callable<Connection> database;
    private final List<long[]> health = new ArrayList<long[]>(); // attempt epoch, success=1/failure=0/timeout=-1
    private final ScheduledExecutorService healthScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "database-availability-observer"); t.setDaemon(true); return t;
    });
    private final ExecutorService healthExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "database-availability-query"); t.setDaemon(true); return t;
    });

    public synchronized void observeDatabase(Callable<Connection> factory) { database = factory; }

    public ResilienceMonitor(PerfBaselineMonitor.Baseline baseline, Config config) throws IOException {
        config.validate(); this.baseline = baseline; this.config = config;
        output = config.reportDir != null ? config.reportDir.toAbsolutePath() : baseline.file.toAbsolutePath().getParent()
                .resolve("dbchaos-reports").resolve(System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(output);
        // Refuse to silently overwrite a previous experiment.
        if (Files.exists(output.resolve("resilience.json")) || Files.exists(output.resolve("throughput.csv")))
            throw new IOException("报告目录已有实验结果，请选择新目录: " + output);
        fileKey = Files.readAttributes(baseline.file, BasicFileAttributes.class).fileKey();
        input = new RandomAccessFile(baseline.file.toFile(), "r");
        try {
            readAvailable();
            epoch = workloadEpoch();
            for (long[] w : baseline.windows) baselineSamples.add(new Sample("baseline", epoch + w[0],
                    epoch + w[0] + baseline.windowMs, w[1], baselineErrors.getOrDefault(w[0], 0L), true, true));
        } catch (IOException | RuntimeException e) {
            input.close(); scheduler.shutdownNow(); throw e;
        }
        System.out.println("[Resilience] 全程业务吞吐量及报告保存到: " + output);
    }

    public synchronized void faultStarted() {
        if (started != null || finished) return;
        started = System.currentTimeMillis();
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, Math.min(1000, baseline.windowMs), TimeUnit.MILLISECONDS);
        if (database != null) healthScheduler.scheduleWithFixedDelay(this::probeDatabase, 0, baseline.windowMs, TimeUnit.MILLISECONDS);
        System.out.println("[Resilience] t0=" + Instant.ofEpochMilli(started) + "（正式注入开始，含建表/准备负载）");
    }

    private void probeDatabase() {
        long time = System.currentTimeMillis();
        Future<?> attempt = healthExecutor.submit(() -> {
            try (Connection c = database.call(); Statement s = c.createStatement()) {
                s.setQueryTimeout(2);
                try (java.sql.ResultSet rows = s.executeQuery("SELECT 1")) {
                    if (!rows.next() || rows.getInt(1) != 1) throw new SQLException("invalid health response");
                }
                return null;
            }
        });
        long status = 0;
        try { attempt.get(3, TimeUnit.SECONDS); status = 1; }
        catch (TimeoutException e) { status = -1; attempt.cancel(true); }
        catch (InterruptedException e) { attempt.cancel(true); Thread.currentThread().interrupt(); return; }
        catch (ExecutionException e) { /* Refusal is evidence of probe failure, not of a database process crash. */ }
        synchronized (this) { health.add(new long[]{time, status}); }
    }

    private synchronized void pollSafely() {
        if (monitoringError != null) return;
        try { readAvailable(); persistSeries(samples(System.currentTimeMillis())); }
        catch (Exception e) { monitoringError = e.getClass().getSimpleName() + ": " + e.getMessage(); }
    }

    public void finish(boolean failed) {
        synchronized (this) { if (finished) return; stopped = started == null ? null : System.currentTimeMillis(); }
        scheduler.shutdownNow();
        healthScheduler.shutdownNow(); healthExecutor.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) monitoringError = "业务观测线程未停止";
            if (started != null && !failed && stopped - started >= baseline.windowMs) {
                System.out.println("[Resilience] 故障执行及清理已返回，开始观察恢复（最长 " + config.recoveryTimeoutMs / 1000 + "s）");
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.recoveryTimeoutMs);
                while (true) {
                    pollSafely();
                    ResilienceAnalysis.Result progress = ResilienceAnalysis.analyze(baseline.p0, started, stopped,
                            samples(System.currentTimeMillis()), baseline.stableWindows, baseline.cvLimit,
                            config.recoveryPct, config.isolationDropPct, failed, analysisProblem());
                    // One extra window allows buffered/late CSV writes to arrive before confirming recovery.
                    if (progress.recovered && System.currentTimeMillis() >= progress.tr + baseline.windowMs) break;
                    if (monitoringError != null || System.nanoTime() >= deadline) break;
                    Thread.sleep(Math.min(1000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); monitoringError = "恢复观测被中断";
        } finally {
            synchronized (this) {
                finished = true; finalSamples = samples(System.currentTimeMillis());
                result = ResilienceAnalysis.analyze(baseline.p0, started, stopped, finalSamples,
                        baseline.stableWindows, baseline.cvLimit, config.recoveryPct, config.isolationDropPct, failed, analysisProblem());
                if (!failed && Boolean.TRUE.equals(healthyFaultWindows()) && (result.pd == null || result.pd == 0))
                    result.continuity = "故障期间每个完整窗口至少一次探针成功响应 SELECT 1；业务零吞吐或遥测不足不等同于数据库进程宕机";
                try { input.close(); persistSeries(finalSamples); persistHealth(); persistJson(failed); }
                catch (IOException e) { monitoringError = "报告保存失败: " + e.getMessage(); }
            }
        }
    }

    public synchronized void report() {
        if (result == null) { System.out.println("[Resilience] 观测未完成，无法报告韧性指标"); return; }
        StringBuilder text = new StringBuilder();
        text.append("[Resilience] P0=").append(number(result.p0)).append(" TPS, t0=").append(time(result.t0))
                .append(", td=").append(time(result.td)).append(", tr=").append(time(result.tr)).append('\n');
        text.append("[Resilience] Pd=").append(number(result.pd)).append(" TPS, Pr=").append(number(result.pr)).append(" TPS\n");
        text.append("[Resilience] 最大吞吐下降=").append(percent(result.dropPct)).append("，平均下降=")
                .append(percent(result.meanDropPct)).append("，恢复至基线的=").append(percent(result.restoredPct))
                .append("，已弥补吞吐损失=").append(percent(result.regainedLossPct)).append('\n');
        if (result.tr != null) text.append("[Resilience] 撤销后恢复耗时=").append(result.tr - result.withdrawalMs).append("ms\n");
        text.append("[Resilience] 隔离影响: ").append(result.isolation).append('\n');
        text.append("[Resilience] 持续服务: ").append(result.continuity).append('\n');
        text.append("[Resilience] 故障阶段完整窗口内业务错误率: ").append(percent(result.faultErrorPct)).append('\n');
        text.append("[Resilience] 性能恢复: ").append(result.recovery).append('\n');
        text.append("[Resilience] 判据: 窗口=").append(baseline.windowMs / 1000.0).append("s，连续=")
                .append(baseline.stableWindows).append("，CV<=").append(baseline.cvLimit)
                .append("%，恢复>=P0的").append(config.recoveryPct).append("%，隔离最大下降<=")
                .append(config.isolationDropPct).append("%；td/tr 为窗口结束时间。\n");
        text.append("[Resilience] 时间对齐: ").append(alignment).append("；未知项为 N/A；仅完整窗口参与判定。\n");
        if (started == null) text.append("[Resilience] 故障未开始（可能预检失败），不计算故障与恢复指标。\n");
        if (started != null && stopped - started < baseline.windowMs)
            text.append("[Resilience] 故障不足一个统计窗口，无法分析极值和恢复；请缩小 --baseline-window 或延长故障时长。\n");
        if (!result.evidenceComplete) text.append("[Resilience] 注入或遥测证据不足，不能作出隔离、持续服务及恢复的肯定结论。\n");
        if (monitoringError != null) text.append("[Resilience] 观测问题: ").append(monitoringError).append('\n');
        text.append("[Resilience] 撤销时间是注入器执行及清理返回时刻；未独立验证残留对象/锁是否清除。\n");
        text.append("[Resilience] 数据库探针完整故障窗口均响应: ").append(healthyFaultWindows()).append("（探针失败也可能是账号/连接限额，不等同于进程崩溃）\n");
        text.append("[Resilience] 数据文件: ").append(output).append("/{throughput.csv,database-health.csv,resilience.json,report.txt}\n");
        System.out.print(text);
        try { Files.write(output.resolve("report.txt"), text.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { System.err.println("[Resilience] 无法保存文本报告: " + e.getMessage()); }
    }

    private synchronized List<Sample> samples(long now) {
        List<Sample> all = new ArrayList<Sample>(baselineSamples);
        if (started == null) return all;
        addSamples(all, "fault", started, stopped == null ? now : stopped, fault);
        if (stopped != null) addSamples(all, "recovery", stopped, now, recovery);
        return all;
    }
    private void addSamples(List<Sample> all, String phase, long start, long end, Map<Long, long[]> buckets) {
        for (long time = start; time < end; time += baseline.windowMs) {
            long finish = Math.min(time + baseline.windowMs, end);
            long[] counts = buckets.get(time);
            all.add(new Sample(phase, time, finish, counts == null ? 0 : counts[0], counts == null ? 0 : counts[1],
                    finish - time == baseline.windowMs, epoch + latestElapsed >= finish));
        }
    }
    private void readAvailable() throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(baseline.file, BasicFileAttributes.class);
        if (!Objects.equals(fileKey, attrs.fileKey()) || attrs.size() < input.getFilePointer())
            throw new IOException("业务结果文件被替换或截断，不能继续拼接不同实验");
        long limit = attrs.size();
        while (input.getFilePointer() < limit) {
            long offset = input.getFilePointer();
            String raw = input.readLine();
            long after = input.getFilePointer();
            if (raw == null) break;
            // Never count a line before its writer has completed it.
            input.seek(after - 1); boolean complete = input.read() == '\n';
            if (!complete) { input.seek(offset); break; }
            if (after - offset > 1048576) throw new IOException("业务结果单行超过 1 MiB");
            String line = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            if (line.trim().isEmpty()) continue;
            List<String> fields = csv(line);
            if (!headerSeen) {
                for (int i = 0; i < fields.size(); i++) columns.put(fields.get(i).trim().toLowerCase(Locale.ROOT), i);
                if (!columns.containsKey("elapsed") || !columns.containsKey("latency")
                        || !columns.containsKey("ttype") || !columns.containsKey("error"))
                    throw new IOException("业务结果缺少 elapsed/latency/ttype/error 表头");
                headerSeen = true; continue;
            }
            try {
                long elapsed = Long.parseLong(field(fields, "elapsed"));
                double latency = Double.parseDouble(field(fields, "latency"));
                if (elapsed < 0 || latency < 0 || !Double.isFinite(latency)) throw new IllegalArgumentException("非法时间");
                if (columns.containsKey("run")) {
                    String id = field(fields, "run");
                    if (runId == null) runId = id;
                    else if (!runId.equals(id)) throw new IOException("结果文件混入不同 run 的数据");
                }
                latestElapsed = Math.max(latestElapsed, elapsed);
                String type = field(fields, "ttype").toUpperCase(Locale.ROOT);
                if (baseline.excluded.contains(type)) continue;
                String error = field(fields, "error");
                boolean success = error.isEmpty() || error.equals("0");
                if (started == null) {
                    if (!success) for (long[] w : baseline.windows) if (elapsed >= w[0] && elapsed < w[0] + baseline.windowMs)
                        baselineErrors.merge(w[0], 1L, Long::sum);
                    continue;
                }
                long eventTime = Math.addExact(epoch, elapsed);
                if (eventTime > System.currentTimeMillis() + baseline.windowMs)
                    throw new IOException("业务时间戳超前，检查 runInfo.sessionStart/时区及系统时钟");
                if (eventTime < started) continue;
                long origin = stopped == null || eventTime < stopped ? started : stopped;
                Map<Long, long[]> target = stopped == null || eventTime < stopped ? fault : recovery;
                long bucket = origin + (eventTime - origin) / baseline.windowMs * baseline.windowMs;
                long[] counts = target.computeIfAbsent(bucket, key -> new long[2]);
                counts[success ? 0 : 1]++;
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new IOException("业务结果存在非法完整记录，不能将其当作零吞吐", e);
            }
        }
    }
    private String field(List<String> fields, String name) {
        int index = columns.get(name);
        if (index >= fields.size()) throw new IllegalArgumentException("CSV 缺列");
        return fields.get(index).trim();
    }
    static List<String> csv(String line) {
        List<String> fields = new ArrayList<String>(); StringBuilder current = new StringBuilder(); boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quote && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                else quote = !quote;
            } else if (c == ',' && !quote) { fields.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        if (quote) throw new IllegalArgumentException("CSV 引号未闭合");
        fields.add(current.toString()); return fields;
    }
    private long workloadEpoch() throws IOException {
        Path info = baseline.file.resolveSibling("runInfo.csv");
        if (Files.isRegularFile(info)) {
            List<String> lines = Files.readAllLines(info, StandardCharsets.UTF_8);
            if (lines.size() >= 2) {
                List<String> header = csv(lines.get(0)); int start = header.indexOf("sessionStart"), run = header.indexOf("run");
                if (start >= 0) for (int i = 1; i < lines.size(); i++) {
                    List<String> row = csv(lines.get(i));
                    if (row.size() > start && (run < 0 || runId == null || row.get(run).equals(runId))) {
                        alignment = "runInfo.sessionStart（BenchmarkSQL 与 DBChaos 须使用相同时区/时钟）";
                        exactAlignment = true;
                        return Timestamp.valueOf(row.get(start)).getTime();
                    }
                }
            }
            throw new IOException("runInfo.csv 没有与当前 run 匹配的 sessionStart");
        }
        alignment = "按基线末次业务事件与本机观测时刻估计，可能受日志缓冲影响";
        return baseline.capturedMs - baseline.latestElapsedMs;
    }

    private void persistSeries(List<Sample> samples) throws IOException {
        StringBuilder csv = new StringBuilder("phase,start_epoch_ms,end_epoch_ms,relative_start_ms,successes,errors,successful_tps,complete,source_covered\n");
        for (Sample s : samples) csv.append(s.phase).append(',').append(s.startMs).append(',').append(s.endMs).append(',')
                .append(started == null ? "" : s.startMs - started).append(',').append(s.successes).append(',').append(s.errors)
                .append(',').append(number(s.tps())).append(',').append(s.complete).append(',').append(s.covered).append('\n');
        atomicWrite(output.resolve("throughput.csv"), csv.toString());
    }
    private void persistJson(boolean failed) throws IOException {
        ResilienceAnalysis.Result r = result;
        String json = "{\n  \"P0\": " + number(r.p0) + ", \"Pd\": " + number(r.pd) + ", \"Pr\": " + number(r.pr)
                + ",\n  \"t0_epoch_ms\": " + r.t0 + ", \"td_epoch_ms\": " + r.td + ", \"tr_epoch_ms\": " + r.tr
                + ",\n  \"fault_withdrawal_epoch_ms\": " + r.withdrawalMs + ",\n  \"max_drop_pct\": " + number(r.dropPct)
                + ", \"mean_drop_pct\": " + number(r.meanDropPct) + ", \"restored_baseline_pct\": " + number(r.restoredPct)
                + ", \"regained_loss_pct\": " + number(r.regainedLossPct) + ",\n  \"recovered\": " + r.recovered
                + ", \"fault_error_pct\": " + number(r.faultErrorPct)
                + ", \"recovery_stable\": " + r.recoveryStable + ", \"evidence_complete\": " + r.evidenceComplete
                + ", \"execution_failed\": " + failed + ",\n  \"window_ms\": " + baseline.windowMs
                + ", \"database_probe_fault_windows_all_responsive\": " + healthyFaultWindows()
                + ", \"stable_windows\": " + baseline.stableWindows + ", \"cv_limit_pct\": " + baseline.cvLimit
                + ", \"recovery_target_pct\": " + config.recoveryPct + ", \"isolation_max_drop_pct\": " + config.isolationDropPct
                + ",\n  \"isolation\": " + quote(r.isolation) + ",\n  \"continuity\": " + quote(r.continuity)
                + ",\n  \"recovery\": " + quote(r.recovery) + ",\n  \"time_alignment\": " + quote(alignment)
                + ", \"time_alignment_exact\": " + exactAlignment
                + ",\n  \"monitoring_error\": " + quote(monitoringError) + "\n}\n";
        atomicWrite(output.resolve("resilience.json"), json.replace(": N/A", ": null"));
    }
    private String analysisProblem() {
        return monitoringError != null ? monitoringError : exactAlignment ? null : "缺少 runInfo.sessionStart，业务与故障时间只能估计对齐";
    }
    private Boolean healthyFaultWindows() {
        if (started == null || stopped == null || database == null) return null;
        long count = (stopped - started) / baseline.windowMs;
        if (count == 0) return null;
        for (long i = 0; i < count; i++) {
            long start = started + i * baseline.windowMs;
            boolean passed = false;
            for (long[] h : health) if (h[0] >= start && h[0] < start + baseline.windowMs && h[1] == 1) passed = true;
            if (!passed) return false;
        }
        return true;
    }
    private void persistHealth() throws IOException {
        StringBuilder text = new StringBuilder("attempt_epoch_ms,status\n");
        for (long[] h : health) text.append(h[0]).append(',').append(h[1] == 1 ? "responsive" : h[1] == -1 ? "timeout" : "failed").append('\n');
        atomicWrite(output.resolve("database-health.csv"), text.toString());
    }
    private static void atomicWrite(Path path, String content) throws IOException {
        Path tmp = Files.createTempFile(path.getParent(), ".dbchaos-", ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    private static String number(Double n) { return n == null || !Double.isFinite(n) ? "N/A" : String.format(Locale.ROOT, "%.6f", n); }
    private static String percent(Double n) { return n == null ? "N/A" : String.format(Locale.ROOT, "%.2f%%", n); }
    private static String time(Long n) { return n == null ? "N/A" : Instant.ofEpochMilli(n).toString(); }
    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
