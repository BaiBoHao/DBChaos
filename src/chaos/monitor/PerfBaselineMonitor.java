package chaos.monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 测试前置监控：实时读取 BenchmarkSQL 的 result.csv，按窗口统计 TPC-C 成功 TPS 与 p95 延迟，
 * 等性能连续多个窗口稳定后输出基准值（stdout + 可选 JSON）。
 *
 * 用法: java -jar DBChaos.jar monitor baseline -result-dir <dir> [options]
 */
public final class PerfBaselineMonitor {

    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 1;
    private static final int EXIT_ARGS = 2;
    private static final int EXIT_UNSTABLE = 3;

    private PerfBaselineMonitor() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            printHelp();
            System.exit(EXIT_ARGS);
        } catch (Exception e) {
            System.err.println("基准监控失败: " + e.getMessage());
            System.exit(EXIT_ERROR);
        }
    }

    public static int run(String[] args) throws Exception {
        return run(args, null);
    }

    /** Pass the measured baseline directly to the full-lifecycle observer, without JSON round trips. */
    public static int run(String[] args, java.util.function.Consumer<Baseline> measured) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return EXIT_OK;
        }

        String resultArg = firstNonNull(opt(args, "-result-dir", null), opt(args, "-result", null));
        if (resultArg == null) {
            throw new IllegalArgumentException("缺失必填参数 -result-dir（BenchmarkSQL 结果目录或 result.csv 路径）");
        }
        Path resultPath = Paths.get(resultArg);

        int windowSec = positiveInt(args, "-window", 10);
        int stableWindows = positiveInt(args, "-stable-windows", 3);
        int minWaitSec = nonNegativeInt(args, "-min-wait", 30);
        int maxWaitSec = positiveInt(args, "-max-wait", 180);
        int pollMs = positiveInt(args, "-poll", 1000);
        int ignoreFirstSec = nonNegativeInt(args, "-ignore-first", 0);
        double tpsCvLimit = positiveDouble(args, "-tps-cv", 5.0);
        double p95SwingLimit = positiveDouble(args, "-p95-swing", 10.0);
        boolean failOnTimeout = !"continue".equalsIgnoreCase(opt(args, "-on-timeout", "fail"));
        String excludeArg = opt(args, "-exclude-ttype", "DELIVERY_BG");
        String jsonArg = opt(args, "-json", null);

        Set<String> excluded = new HashSet<String>();
        for (String t : excludeArg.split(",")) {
            if (!t.trim().isEmpty()) excluded.add(t.trim().toUpperCase());
        }

        System.out.printf("[Monitor] 等待数据库服务性能稳定: 窗口=%ds, 连续=%d, TPS CV<=%.1f%%, "
                        + "p95 波动<=%.1f%%, 最短=%ds, 最长=%ds, 超时=%s%n",
                windowSec, stableWindows, tpsCvLimit, p95SwingLimit,
                minWaitSec, maxWaitSec, failOnTimeout ? "失败" : "继续");

        long monitorStartMs = System.currentTimeMillis();
        long windowMs = windowSec * 1000L;
        long originMs = ignoreFirstSec * 1000L;
        Path file = null;
        long lastNewestElapsed = -1;
        long lastGrowthWallMs = monitorStartMs;
        long lastCompleteStart = -1;

        while (true) {
            long nowMs = System.currentTimeMillis();
            if (file == null) {
                file = resolveResultFile(resultPath);
                if (file == null) {
                    if (timedOut(monitorStartMs, nowMs, maxWaitSec)) {
                        return onTimeout(failOnTimeout, jsonArg, resultPath, monitorStartMs, nowMs,
                                null, "未找到 result.csv: " + resultPath, windowSec, stableWindows);
                    }
                    Thread.sleep(pollMs);
                    continue;
                }
                System.out.println("[Monitor] 读取结果文件: " + file);
            }

            Snapshot snap = readSnapshot(file, windowMs, originMs, excluded);
            if (snap.newestElapsedMs >= 0) {
                if (snap.newestElapsedMs > lastNewestElapsed) {
                    lastNewestElapsed = snap.newestElapsedMs;
                    // A frozen historical file must not look like a live, stable workload on first read.
                    lastGrowthWallMs = Math.min(nowMs, Files.getLastModifiedTime(file).toMillis());
                }
                // 服务停摆时 result.csv 不再增长；用墙钟外推保证“空窗口”也参与稳定性判定。
                long liveElapsedMs = snap.newestElapsedMs + (nowMs - lastGrowthWallMs);
                List<Window> complete = completeWindows(snap, liveElapsedMs, originMs, windowMs, stableWindows);
                if (!complete.isEmpty() && complete.get(complete.size() - 1).startMs != lastCompleteStart) {
                    lastCompleteStart = complete.get(complete.size() - 1).startMs;
                    Window w = complete.get(complete.size() - 1);
                    System.out.printf("[Monitor] t=%.0fs 最新窗口: TPS=%.2f p95=%.2fms%n",
                            (nowMs - monitorStartMs) / 1000.0, w.tps(windowMs), w.p95Ms());
                }

                double waitedSec = (nowMs - monitorStartMs) / 1000.0;
                if (waitedSec >= minWaitSec && complete.size() >= stableWindows) {
                    List<Window> recent = last(complete, stableWindows);
                    Verdict verdict = evaluate(recent, windowMs, tpsCvLimit, p95SwingLimit);
                    if (verdict.stable) {
                        int status = emitBaseline(jsonArg, file, monitorStartMs, nowMs,
                                recent, windowMs, waitedSec, tpsCvLimit, p95SwingLimit, excluded);
                        if (measured != null) measured.accept(new Baseline(file, nowMs, snap.newestElapsedMs,
                                windowMs, stableWindows, tpsCvLimit, excluded, recent));
                        return status;
                    }
                    if (timedOut(monitorStartMs, nowMs, maxWaitSec)) {
                        return onTimeout(failOnTimeout, jsonArg, file, monitorStartMs, nowMs,
                                recent, verdict.describe(tpsCvLimit, p95SwingLimit),
                                windowSec, stableWindows);
                    }
                } else if (timedOut(monitorStartMs, nowMs, maxWaitSec)) {
                    return onTimeout(failOnTimeout, jsonArg, file, monitorStartMs, nowMs,
                            complete.isEmpty() ? null : last(complete, Math.min(stableWindows, complete.size())),
                            "数据窗口不足（完整窗口 " + complete.size() + "/" + stableWindows + "）",
                            windowSec, stableWindows);
                }
            } else if (timedOut(monitorStartMs, nowMs, maxWaitSec)) {
                return onTimeout(failOnTimeout, jsonArg, file, monitorStartMs, nowMs,
                        null, "result.csv 中尚无有效事务记录", windowSec, stableWindows);
            }

            Thread.sleep(pollMs);
        }
    }

    public static final class Baseline {
        public final Path file;
        public final long capturedMs, latestElapsedMs, windowMs;
        public final int stableWindows;
        public final double p0, cvLimit;
        public final Set<String> excluded;
        public final List<long[]> windows = new ArrayList<long[]>();

        private Baseline(Path file, long capturedMs, long latestElapsedMs, long windowMs,
                         int stableWindows, double cvLimit, Set<String> excluded, List<Window> recent) {
            this.file = file; this.capturedMs = capturedMs; this.latestElapsedMs = latestElapsedMs;
            this.windowMs = windowMs; this.stableWindows = stableWindows; this.cvLimit = cvLimit;
            this.excluded = new HashSet<String>(excluded);
            long count = 0;
            for (Window w : recent) { count += w.count; windows.add(new long[]{w.startMs, w.count}); }
            this.p0 = count / (recent.size() * windowMs / 1000.0);
        }
    }

    public static void printHelp() {
        System.out.println("\n[1m测试前置监控: [33mmonitor baseline[0m");
        System.out.println("  实时读取 BenchmarkSQL result.csv，统计窗口内成功 TPC-C TPS 与 p95 延迟；");
        System.out.println("  连续多个窗口稳定后，将该窗口组的统计值作为测试基准值输出。");
        System.out.println("\n[1m参数列表:[0m");
        System.out.printf("  %-20s %s\n", "-result-dir <path>", "必填。BenchmarkSQL 结果目录（或 result.csv 路径）");
        System.out.printf("  %-20s %s\n", "-window <sec>", "窗口长度 (默认 10)");
        System.out.printf("  %-20s %s\n", "-stable-windows <n>", "连续稳定窗口数 (默认 3)");
        System.out.printf("  %-20s %s\n", "-tps-cv <pct>", "TPS 变异系数上限，单位 %% (默认 5)");
        System.out.printf("  %-20s %s\n", "-p95-swing <pct>", "p95 波动幅度上限，单位 %% (默认 10)");
        System.out.printf("  %-20s %s\n", "-min-wait <sec>", "最短等待时间 (默认 30)");
        System.out.printf("  %-20s %s\n", "-max-wait <sec>", "最长等待时间 (默认 180)");
        System.out.printf("  %-20s %s\n", "-poll <ms>", "采样间隔 (默认 1000)");
        System.out.printf("  %-20s %s\n", "-ignore-first <sec>", "忽略运行开始阶段的数据 (默认 0)");
        System.out.printf("  %-20s %s\n", "-exclude-ttype <list>", "不计入的 TPC-C 事务类型，逗号分隔 (默认 DELIVERY_BG)");
        System.out.printf("  %-20s %s\n", "-on-timeout <fail|continue>", "超时后失败或带警告继续 (默认 fail)");
        System.out.printf("  %-20s %s\n", "-json <path>", "将基准值写入 JSON 文件");
        System.out.println("\n[1m示例:[0m");
        System.out.println("[36m  java -jar DBChaos-0.0.1.jar monitor baseline -result-dir /path/to/results -json baseline.json[0m");
        System.out.println();
    }

    private static int emitBaseline(String jsonArg, Path resultPath, long startMs, long nowMs,
                                    List<Window> recent, long windowMs, double waitedSec,
                                    double tpsCvLimit, double p95SwingLimit, Set<String> excluded)
            throws IOException {
        Verdict v = evaluate(recent, windowMs, tpsCvLimit, p95SwingLimit);
        long txns = 0;
        List<Double> latencies = new ArrayList<Double>();
        for (Window w : recent) {
            txns += w.count;
            latencies.addAll(w.latencies);
        }
        double tps = txns / ((recent.size() * windowMs) / 1000.0);
        double p95 = percentile(latencies, 0.95);

        System.out.printf("[Baseline] 性能已稳定 | 等待=%.1fs | 成功TPS=%.2f | p95=%.2fms | "
                        + "窗口=%dx%ds | tps_cv=%.2f%% | p95_swing=%.2f%%%n",
                waitedSec, tps, p95, recent.size(), windowMs / 1000,
                v.tpsCvPct, v.p95SwingPct);
        System.out.println("[Baseline] 该组窗口统计值即本轮测试基准。");

        String json = buildJson(true, null, resultPath, startMs, nowMs, recent, windowMs,
                tps, p95, txns, v, tpsCvLimit, p95SwingLimit, excluded);
        writeJson(jsonArg, json);
        return EXIT_OK;
    }

    private static int onTimeout(boolean failOnTimeout, String jsonArg, Path resultPath,
                                 long startMs, long nowMs, List<Window> recent, String reason,
                                 int windowSec, int stableWindows) throws IOException {
        String msg = "在 " + Math.round((nowMs - startMs) / 1000.0) + "s 内未达到稳定条件: " + reason;
        long windowMs = windowSec * 1000L;
        if (recent == null) recent = Collections.emptyList();
        double tps = 0;
        double p95 = 0;
        long txns = 0;
        Verdict v = new Verdict(false, 0, 0);
        if (recent != null && !recent.isEmpty()) {
            List<Double> latencies = new ArrayList<Double>();
            for (Window w : recent) {
                txns += w.count;
                latencies.addAll(w.latencies);
            }
            tps = txns / ((recent.size() * windowMs) / 1000.0);
            p95 = percentile(latencies, 0.95);
            v = evaluate(recent, windowMs, Double.MAX_VALUE, Double.MAX_VALUE);
        }
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"stable\": false,\n");
        json.append("  \"reason\": ").append(quote(reason)).append(",\n");
        appendCommon(json, resultPath, startMs, nowMs, recent, windowMs, tps, p95, txns, v,
                null, null, Collections.<String>emptySet());
        json.append("}\n");
        writeJson(jsonArg, json.toString());

        if (failOnTimeout) {
            System.err.println("[Baseline] ✘ " + msg);
            return EXIT_UNSTABLE;
        }
        System.out.println("[Baseline] ⚠ " + msg + "（按要求继续，基准标记为 unstable）");
        return EXIT_OK;
    }

    private static String buildJson(boolean stable, String reason, Path resultPath, long startMs,
                                    long nowMs, List<Window> recent, long windowMs, double tps,
                                    double p95, long txns, Verdict v, double tpsCvLimit,
                                    double p95SwingLimit, Set<String> excluded) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"stable\": ").append(stable).append(",\n");
        appendCommon(json, resultPath, startMs, nowMs, recent, windowMs, tps, p95, txns, v,
                tpsCvLimit, p95SwingLimit, excluded);
        json.append("}\n");
        return json.toString();
    }

    private static void appendCommon(StringBuilder json, Path resultPath, long startMs, long nowMs,
                                     List<Window> recent, long windowMs, double tps, double p95,
                                     long txns, Verdict v, Double tpsCvLimit,
                                     Double p95SwingLimit, Set<String> excluded) {
        json.append("  \"result_file\": ").append(quote(resultPath.toString())).append(",\n");
        json.append("  \"waited_sec\": ").append(round((nowMs - startMs) / 1000.0)).append(",\n");
        json.append("  \"baseline_start_epoch\": ").append(round((nowMs - recent.size() * windowMs) / 1000.0)).append(",\n");
        json.append("  \"baseline_end_epoch\": ").append(round(nowMs / 1000.0)).append(",\n");
        json.append("  \"metrics\": {\n");
        json.append("    \"successful_tps\": ").append(round(tps)).append(",\n");
        json.append("    \"p95_ms\": ").append(round(p95)).append(",\n");
        json.append("    \"transactions\": ").append(txns).append(",\n");
        json.append("    \"window_sec\": ").append(windowMs / 1000).append(",\n");
        json.append("    \"windows\": ").append(recent.size()).append("\n");
        json.append("  },\n");
        json.append("  \"criteria\": {\n");
        json.append("    \"tps_cv_pct\": ").append(roundOrNull(v.tpsCvPct)).append(",\n");
        json.append("    \"p95_swing_pct\": ").append(roundOrNull(v.p95SwingPct)).append(",\n");
        if (tpsCvLimit != null) json.append("    \"tps_cv_limit_pct\": ").append(round(tpsCvLimit)).append(",\n");
        if (p95SwingLimit != null) json.append("    \"p95_swing_limit_pct\": ").append(round(p95SwingLimit)).append(",\n");
        json.append("    \"exclude_ttype\": [");
        int i = 0;
        for (String t : excluded) {
            if (i++ > 0) json.append(", ");
            json.append(quote(t));
        }
        json.append("]\n  },\n");
        json.append("  \"window_series\": [");
        for (int k = 0; k < recent.size(); k++) {
            Window w = recent.get(k);
            if (k > 0) json.append(",");
            json.append("\n    {\"start_sec\": ").append(round(w.startMs / 1000.0))
                .append(", \"tps\": ").append(round(w.tps(windowMs)))
                .append(", \"p95_ms\": ").append(round(w.p95Ms()))
                .append(", \"transactions\": ").append(w.count).append("}");
        }
        json.append("\n  ]\n");
    }

    private static void writeJson(String jsonArg, String json) throws IOException {
        if (jsonArg == null || jsonArg.trim().isEmpty()) return;
        Path out = Paths.get(jsonArg);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(out, json.getBytes(StandardCharsets.UTF_8));
        System.out.println("[Baseline] 基准值已写入: " + out);
    }

    private static Verdict evaluate(List<Window> windows, long windowMs,
                                    double tpsCvLimit, double p95SwingLimit) {
        double[] tps = new double[windows.size()];
        double[] p95 = new double[windows.size()];
        for (int i = 0; i < windows.size(); i++) {
            tps[i] = windows.get(i).tps(windowMs);
            p95[i] = windows.get(i).p95Ms();
        }
        double tpsMean = mean(tps);
        double p95Mean = mean(p95);
        double tpsCv = tpsMean <= 0 ? Double.NaN : stdev(tps) / tpsMean * 100.0;
        double p95Swing = p95Mean <= 0 ? Double.NaN
                : (max(p95) - min(p95)) / p95Mean * 100.0;
        boolean stable = tpsMean > 0 && p95Mean > 0
                && tpsCv <= tpsCvLimit && p95Swing <= p95SwingLimit;
        return new Verdict(stable, tpsCv, p95Swing);
    }

    private static List<Window> completeWindows(Snapshot snap, long liveElapsedMs,
                                                long originMs, long windowMs, int needed) {
        List<Window> result = new ArrayList<Window>();
        long lastCompleteIdx = (liveElapsedMs - originMs) / windowMs - 1;
        if (lastCompleteIdx < 0) return result;
        int maxIdx = (int) Math.min(Integer.MAX_VALUE, lastCompleteIdx);
        for (long i = Math.max(0L, (long) maxIdx - needed + 1); i <= maxIdx; i++) {
            Window w = snap.windows.get((int) i);
            // 服务停摆时该窗口没有记录，也要作为空窗口参与稳定性判定。
            result.add(w != null ? w : new Window(originMs + i * windowMs));
        }
        return result;
    }

    private static Snapshot readSnapshot(Path file, long windowMs, long originMs,
                                         Set<String> excluded) throws IOException {
        Snapshot snap = new Snapshot();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return snap;
        }
        if (lines.isEmpty()) return snap;

        int idxElapsed = 1, idxLatency = 2, idxTtype = -1, idxError = -1;
        String first = lines.get(0).trim();
        if (!first.isEmpty() && first.toLowerCase().contains("elapsed")) {
            String[] h = first.split(",", -1);
            for (int i = 0; i < h.length; i++) {
                String name = h[i].trim().toLowerCase();
                if (name.equals("elapsed")) idxElapsed = i;
                else if (name.equals("latency")) idxLatency = i;
                else if (name.equals("ttype")) idxTtype = i;
                else if (name.equals("error")) idxError = i;
            }
        } else {
            idxTtype = 4;
            idxError = 7;
        }

        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            String[] f = line.split(",", -1);
            if (f.length <= Math.max(idxElapsed, idxLatency)) continue;
            Long elapsed = parseLong(f[idxElapsed]);
            Double latency = parseDouble(f[idxLatency]);
            if (elapsed == null || latency == null) continue;
            if (idxError >= 0 && idxError < f.length && f[idxError].trim().length() > 0
                    && !"0".equals(f[idxError].trim())) continue;
            if (idxTtype >= 0 && idxTtype < f.length
                    && excluded.contains(f[idxTtype].trim().toUpperCase())) continue;
            if (elapsed < originMs) continue;

            int windowIdx = (int) ((elapsed - originMs) / windowMs);
            Window w = snap.windows.get(windowIdx);
            if (w == null) {
                w = new Window(originMs + windowIdx * windowMs);
                snap.windows.put(windowIdx, w);
            }
            w.count++;
            w.latencies.add(latency);
            if (elapsed > snap.newestElapsedMs) snap.newestElapsedMs = elapsed;
        }
        return snap;
    }

    private static Path resolveResultFile(Path path) {
        if (path == null) return null;
        if (Files.isRegularFile(path)) return path;
        if (Files.isDirectory(path)) {
            Path[] candidates = {
                    path.resolve("result.csv"),
                    path.resolve("data").resolve("result.csv"),
                    path.resolve("benchmark_data").resolve("data").resolve("result.csv"),
                    path.resolve("run").resolve("result.csv")
            };
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }

    private static boolean timedOut(long startMs, long nowMs, int maxWaitSec) {
        return nowMs - startMs >= maxWaitSec * 1000L;
    }

    private static List<Window> last(List<Window> windows, int n) {
        return new ArrayList<Window>(windows.subList(windows.size() - n, windows.size()));
    }

    private static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(sorted.size() * q) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    private static double mean(double[] v) {
        if (v.length == 0) return 0;
        double sum = 0;
        for (double x : v) sum += x;
        return sum / v.length;
    }

    private static double stdev(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v);
        double sum = 0;
        for (double x : v) sum += (x - m) * (x - m);
        return Math.sqrt(sum / v.length);
    }

    private static double max(double[] v) {
        double m = -Double.MAX_VALUE;
        for (double x : v) m = Math.max(m, x);
        return m;
    }

    private static double min(double[] v) {
        double m = Double.MAX_VALUE;
        for (double x : v) m = Math.min(m, x);
        return m;
    }

    private static Long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return null; }
    }

    private static Double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (RuntimeException e) { return null; }
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String round(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String roundOrNull(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "null" : round(v);
    }

    private static String pct(double v) {
        return Double.isNaN(v) || Double.isInfinite(v)
                ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f%%", v);
    }

    private static boolean hasArg(String[] args, String target) {
        for (String a : args) if (target.equalsIgnoreCase(a)) return true;
        return false;
    }

    private static String opt(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equalsIgnoreCase(args[i])) return args[i + 1];
        }
        return def;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static int positiveInt(String[] args, String name, int def) {
        int v = Integer.parseInt(opt(args, name, String.valueOf(def)));
        if (v <= 0) throw new IllegalArgumentException(name + " 必须为正整数");
        return v;
    }

    private static int nonNegativeInt(String[] args, String name, int def) {
        int v = Integer.parseInt(opt(args, name, String.valueOf(def)));
        if (v < 0) throw new IllegalArgumentException(name + " 必须为非负整数");
        return v;
    }

    private static double positiveDouble(String[] args, String name, double def) {
        double v = Double.parseDouble(opt(args, name, String.valueOf(def)));
        if (!Double.isFinite(v) || v <= 0) throw new IllegalArgumentException(name + " 必须为有限正数");
        return v;
    }

    private static final class Snapshot {
        long newestElapsedMs = -1;
        final TreeMap<Integer, Window> windows = new TreeMap<Integer, Window>();
    }

    private static final class Window {
        final long startMs;
        final List<Double> latencies = new ArrayList<Double>();
        long count;

        Window(long startMs) { this.startMs = startMs; }

        double tps(long windowMs) { return count / (windowMs / 1000.0); }

        double p95Ms() { return percentile(latencies, 0.95); }
    }

    private static final class Verdict {
        final boolean stable;
        final double tpsCvPct;
        final double p95SwingPct;

        Verdict(boolean stable, double tpsCvPct, double p95SwingPct) {
            this.stable = stable;
            this.tpsCvPct = tpsCvPct;
            this.p95SwingPct = p95SwingPct;
        }

        String describe(double tpsCvLimit, double p95SwingLimit) {
            return "tps_cv=" + pct(tpsCvPct) + " (上限 " + round(tpsCvLimit) + "%), p95_swing="
                    + pct(p95SwingPct) + " (上限 " + round(p95SwingLimit) + "%)";
        }
    }
}
