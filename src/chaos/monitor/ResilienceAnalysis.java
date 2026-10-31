package chaos.monitor;

import java.util.*;

/** Pure, window-based analysis. Missing evidence stays unknown rather than becoming a pass. */
public final class ResilienceAnalysis {
    private ResilienceAnalysis() {}

    public static final class Sample {
        public final String phase;
        public final long startMs, endMs, successes, errors;
        public final boolean complete, covered;
        public final LatencyHistogram successfulLatency;
        public Sample(String phase, long startMs, long endMs, long successes, long errors,
                      boolean complete, boolean covered) {
            this(phase, startMs, endMs, successes, errors, complete, covered, null);
        }
        public Sample(String phase, long startMs, long endMs, long successes, long errors,
                      boolean complete, boolean covered, LatencyHistogram successfulLatency) {
            this.phase = phase; this.startMs = startMs; this.endMs = endMs;
            this.successes = successes; this.errors = errors; this.complete = complete; this.covered = covered;
            this.successfulLatency = successfulLatency;
        }
        public double tps() { return successes * 1000.0 / (endMs - startMs); }
    }

    public static final class Result {
        public final double p0;
        public Double pd, pr, dropPct, meanDropPct, restoredPct, regainedLossPct;
        public Double faultErrorPct;
        public Double baselineP95Ms, faultP95Ms, worstFaultP95Ms, recoveryP95Ms, latencyMultiplier;
        public Double baselineErrorPct, recoveryErrorPct;
        public boolean throughputRecovered;
        public Long t0, td, tr, withdrawalMs;
        public boolean recovered, recoveryStable, evidenceComplete;
        public String isolation = "无法判断", continuity = "无法判断", recovery = "无法判断";
        public String isolationGrade = "unassessed", availability = "unassessed", recoveryStatus = "unassessed";
        public Result(double p0) { this.p0 = p0; }
    }

    public static boolean stable(List<Sample> samples, int count, double cvLimit) {
        if (samples.size() < count) return false;
        double sum = 0, variance = 0;
        List<Sample> last = samples.subList(samples.size() - count, samples.size());
        for (int i = 1; i < last.size(); i++) if (last.get(i).startMs != last.get(i - 1).endMs) return false;
        for (Sample s : last) {
            if (!s.complete || !s.covered || s.tps() <= 0) return false;
            sum += s.tps();
        }
        double mean = sum / count;
        for (Sample s : last) variance += Math.pow(s.tps() - mean, 2);
        return Math.sqrt(variance / count) / mean * 100 <= cvLimit;
    }

    public static boolean recovered(List<Sample> samples, int count, double cvLimit, double target) {
        if (!stable(samples, count, cvLimit)) return false;
        for (Sample s : samples.subList(samples.size() - count, samples.size()))
            if (s.tps() < target) return false;
        return true;
    }

    public static Result analyze(double p0, Long t0, Long stop, List<Sample> all, int stableWindows,
                                 double cvLimit, double recoveryPct, double isolationDropPct,
                                 boolean executionFailed, String monitoringError) {
        Result result = new Result(p0);
        result.t0 = t0; result.withdrawalMs = stop;
        if (!Double.isFinite(p0) || p0 <= 0) return result;
        List<Sample> fault = new ArrayList<Sample>(), recovery = new ArrayList<Sample>();
        for (Sample sample : all) {
            if (!sample.complete) continue;
            if (sample.phase.equals("fault")) fault.add(sample);
            if (sample.phase.equals("recovery")) recovery.add(sample);
        }
        boolean faultCovered = !fault.isEmpty();
        for (Sample s : fault) faultCovered &= s.covered;
        if (faultCovered) {
            Sample minimum = fault.get(0);
            double total = 0; long successes = 0, errors = 0;
            for (Sample s : fault) {
                if (s.tps() < minimum.tps()) minimum = s;
                total += s.tps(); successes += s.successes; errors += s.errors;
            }
            result.pd = minimum.tps(); result.td = minimum.endMs;
            result.dropPct = (p0 - result.pd) / p0 * 100;
            result.meanDropPct = (p0 - total / fault.size()) / p0 * 100;
            if (successes + errors > 0) result.faultErrorPct = errors * 100.0 / (successes + errors);
        }
        // Only the first qualifying consecutive group determines tr. Degraded stable plateaus do not pass.
        for (int i = stableWindows; i <= recovery.size(); i++) {
            List<Sample> prefix = recovery.subList(0, i);
            if (recovered(prefix, stableWindows, cvLimit, p0 * recoveryPct / 100)) {
                result.recovered = true; result.tr = prefix.get(i - 1).endMs;
                recovery = prefix; break;
            }
        }
        if (!recovery.isEmpty()) {
            List<Sample> tail = recovery.subList(Math.max(0, recovery.size() - stableWindows), recovery.size());
            boolean covered = true; double sum = 0;
            for (Sample s : tail) { covered &= s.covered; sum += s.tps(); }
            if (covered) {
                result.pr = sum / tail.size(); result.restoredPct = result.pr / p0 * 100;
                if (result.pd != null && p0 > result.pd)
                    result.regainedLossPct = (result.pr - result.pd) / (p0 - result.pd) * 100;
            }
            result.recoveryStable = stable(recovery, stableWindows, cvLimit);
        }
        result.evidenceComplete = t0 != null && stop != null && faultCovered
                && !executionFailed && monitoringError == null && Double.isFinite(p0) && p0 > 0;
        if (result.evidenceComplete) {
            result.isolation = result.dropPct <= isolationDropPct
                    ? "观测到业务吞吐影响较小；不证明数据库内部隔离机制"
                    : "观测到业务吞吐影响较大；未达到本次隔离影响阈值";
            if (result.dropPct <= isolationDropPct && result.faultErrorPct != null && result.faultErrorPct > 0)
                result.isolation = "吞吐影响较小但出现业务错误，需结合错误率评价隔离能力";
            result.continuity = result.pd > 0
                    ? "每个完整故障窗口均有业务事务成功（仅代表采样粒度内持续服务）"
                    : "存在零成功吞吐窗口；无法仅据此区分数据库停机、请求阻塞或负载停止";
            result.recovery = result.recovered ? "故障撤销后，吞吐量已恢复至阈值并连续稳定"
                    : result.recoveryStable ? "观测期末吞吐已稳定，但未恢复至基线阈值"
                    : "观测期限内未确认性能恢复，不能据此断言永远无法恢复";
        } else if (executionFailed) {
            result.isolation = result.continuity = result.recovery = "注入执行失败，无法据此评价该故障的数据库韧性";
        }
        if (!result.evidenceComplete) {
            result.recovered = false;
            result.tr = null;
        }
        return result;
    }

    /** Service assessment combines throughput, successful-response latency and transaction errors. */
    public static Result analyzeService(double p0, Long t0, Long stop, List<Sample> all, int count,
                                        double cv, double recoveryPct, double maxDrop, boolean failed, String problem,
                                        double isolationLatency, double recoveryLatency, double errorIncrease) {
        Result r = analyze(p0, t0, stop, all, count, cv, recoveryPct, maxDrop, failed, problem);
        r.throughputRecovered = r.recovered;
        List<Sample> baseline = phase(all, "baseline"), fault = phase(all, "fault"), recovery = phase(all, "recovery");
        r.baselineP95Ms = p95(baseline); r.baselineErrorPct = errorPct(baseline);
        r.faultP95Ms = p95(fault);
        for (Sample s : fault) {
            Double value = s.successfulLatency == null ? null : s.successfulLatency.p95();
            if (value != null) r.worstFaultP95Ms = r.worstFaultP95Ms == null ? value : Math.max(r.worstFaultP95Ms, value);
        }
        if (r.baselineP95Ms != null && r.baselineP95Ms > 0 && r.worstFaultP95Ms != null)
            r.latencyMultiplier = r.worstFaultP95Ms / r.baselineP95Ms;
        double allowedError = (r.baselineErrorPct == null ? 0 : r.baselineErrorPct) + errorIncrease;
        r.recovered = false; r.tr = null;
        for (int i = count; i <= recovery.size(); i++) {
            List<Sample> prefix = recovery.subList(0, i), tail = prefix.subList(i - count, i);
            if (!recovered(prefix, count, cv, p0 * recoveryPct / 100)) continue;
            boolean serviceReady = r.baselineP95Ms != null && r.baselineErrorPct != null;
            for (Sample s : tail) {
                Double latency = p95(Collections.singletonList(s)), errors = errorPct(Collections.singletonList(s));
                serviceReady &= latency != null && r.baselineP95Ms != null
                        && latency <= r.baselineP95Ms * recoveryLatency + 1 && errors != null && errors <= allowedError;
            }
            if (serviceReady && r.evidenceComplete) {
                r.recovered = true; r.tr = prefix.get(i - 1).endMs; recovery = prefix; break;
            }
        }
        List<Sample> tail = recovery.subList(Math.max(0, recovery.size() - count), recovery.size());
        boolean covered = !tail.isEmpty(); double sum = 0;
        for (Sample s : tail) { covered &= s.covered; sum += s.tps(); }
        r.pr = covered ? sum / tail.size() : null;
        r.restoredPct = r.pr == null ? null : r.pr / p0 * 100;
        r.regainedLossPct = r.pr != null && r.pd != null && p0 > r.pd ? (r.pr - r.pd) / (p0 - r.pd) * 100 : null;
        r.recoveryP95Ms = covered ? p95(tail) : null; r.recoveryErrorPct = covered ? errorPct(tail) : null;
        r.recoveryStable = stable(recovery, count, cv);
        if (!r.evidenceComplete) return r;

        boolean lowErrors = r.faultErrorPct != null && r.faultErrorPct <= allowedError;
        boolean lowLatency = r.baselineP95Ms != null && r.worstFaultP95Ms != null
                && r.worstFaultP95Ms <= r.baselineP95Ms * isolationLatency + 1;
        if (r.pd == 0 || r.dropPct > Math.max(50, maxDrop) || r.faultErrorPct != null && r.faultErrorPct > Math.max(5, allowedError)
                || r.baselineP95Ms != null && r.worstFaultP95Ms != null && r.worstFaultP95Ms > r.baselineP95Ms * Math.max(5, isolationLatency) + 1) {
            r.isolationGrade = "poor";
            r.isolation = "本次故障隔离效果较差：业务吞吐、响应延迟或业务错误率受到明显影响";
        } else if (r.dropPct <= maxDrop && lowLatency && lowErrors) {
            r.isolationGrade = "good";
            r.isolation = "本次故障隔离效果较好：业务吞吐下降、P95 延迟与错误率均在本次阈值内";
        } else if (r.baselineP95Ms != null && r.faultP95Ms != null && r.faultErrorPct != null) {
            r.isolationGrade = "limited";
            r.isolation = "本次故障隔离效果一般：业务仍能完成请求，但部分性能或错误率指标超过阈值";
        } else r.isolation = "业务吞吐可测，但响应延迟或错误率证据缺失，尚不能完整评价隔离效果";
        List<String> impact = new ArrayList<String>();
        if (r.pd == 0) impact.add("存在业务无成功完成的完整窗口");
        if (r.dropPct > maxDrop) impact.add(String.format(Locale.ROOT, "最低吞吐下降 %.2f%%，超过 %.2f%% 阈值", r.dropPct, maxDrop));
        if (r.baselineP95Ms != null && r.worstFaultP95Ms != null && !lowLatency)
            impact.add(String.format(Locale.ROOT, "最差窗口 P95 从 %.2fms 升至 %.2fms，超过延迟阈值", r.baselineP95Ms, r.worstFaultP95Ms));
        if (r.faultErrorPct != null && !lowErrors)
            impact.add(String.format(Locale.ROOT, "业务错误率 %.2f%%，超过本次 %.2f%% 阈值", r.faultErrorPct, allowedError));
        if (!impact.isEmpty()) r.isolation += "；依据：" + String.join("；", impact);

        long faultSuccesses = 0, faultErrors = 0;
        for (Sample s : fault) { faultSuccesses += s.successes; faultErrors += s.errors; }
        if (faultSuccesses == 0 && faultErrors > 0) {
            r.availability = "unavailable";
            r.continuity = "本次故障阶段业务不可用：已完成的业务请求均失败或超时，没有成功请求；数据库是否响应另看探针";
        } else if (faultSuccesses == 0) {
            r.availability = "no_completed_requests";
            r.continuity = "未观测到业务请求成功完成，需区分请求仍在阻塞与压测停发；不能直接认定数据库宕机";
        } else if (r.pd == 0) {
            r.availability = "intermittent";
            r.continuity = "业务仍可完成部分请求，但存在整个窗口无成功完成的情况，服务出现阶段性停顿";
        } else if (!lowErrors) {
            r.availability = "degraded";
            r.continuity = "业务仍有成功请求，但业务错误率超过本次阈值，可用性受损";
        } else {
            boolean slow = r.baselineP95Ms != null && r.worstFaultP95Ms != null
                    && r.worstFaultP95Ms > r.baselineP95Ms * 1.2 + 1;
            r.availability = slow ? "available_slow" : "available";
            r.continuity = slow ? "业务持续可用，但响应变慢：每个完整窗口均有成功请求，错误率在本次阈值内"
                    : "业务持续可用：每个完整窗口均有成功请求，错误率在本次阈值内";
        }
        r.recoveryStatus = r.recovered ? "recovered" : r.pr == null ? "insufficient_data"
                : r.restoredPct < recoveryPct ? "throughput_not_recovered" : "quality_not_recovered";
        r.recovery = r.recovered ? "业务服务性能已恢复：吞吐量、P95 延迟及错误率均恢复至本次阈值，吞吐连续稳定"
                : r.pr == null ? "恢复记录不足，无法确认撤销后恢复情况"
                : r.restoredPct < recoveryPct ? "观测期内业务吞吐尚未恢复至基线阈值"
                : "吞吐量已回升，但响应延迟、错误率或连续稳定窗口尚未满足恢复条件";
        if (!r.recovered && r.pr != null) {
            List<String> pending = new ArrayList<String>();
            if (r.restoredPct < recoveryPct) pending.add(String.format(Locale.ROOT,
                    "吞吐仅恢复到基线的 %.2f%%，要求至少 %.2f%%", r.restoredPct, recoveryPct));
            if (!r.recoveryStable) pending.add("尚无足够连续稳定的吞吐窗口");
            if (r.baselineP95Ms == null || r.recoveryP95Ms == null) pending.add("成功业务响应延迟记录不足");
            else if (r.recoveryP95Ms > r.baselineP95Ms * recoveryLatency + 1)
                pending.add(String.format(Locale.ROOT, "恢复末期 P95 仍为 %.2fms，基线 %.2fms，本次上限 %.2fms",
                        r.recoveryP95Ms, r.baselineP95Ms, r.baselineP95Ms * recoveryLatency + 1));
            if (r.recoveryErrorPct == null) pending.add("没有可计算恢复错误率的业务完成记录");
            else if (r.recoveryErrorPct > allowedError) pending.add(String.format(Locale.ROOT,
                    "恢复业务错误率 %.2f%%，本次上限 %.2f%%", r.recoveryErrorPct, allowedError));
            if (pending.isEmpty()) pending.add("至少一个恢复窗口的吞吐、延迟或错误率尚未满足逐窗口条件");
            r.recovery = "业务服务性能尚未完全恢复：" + String.join("；", pending);
        }
        return r;
    }

    private static List<Sample> phase(List<Sample> all, String name) {
        List<Sample> samples = new ArrayList<Sample>();
        for (Sample s : all) if (s.phase.equals(name) && s.complete) samples.add(s);
        return samples;
    }
    private static Double p95(List<Sample> samples) {
        LatencyHistogram histogram = new LatencyHistogram();
        for (Sample s : samples) {
            if (!s.covered || s.successes > 0 && s.successfulLatency == null) return null;
            histogram.merge(s.successfulLatency);
        }
        return histogram.p95();
    }
    private static Double errorPct(List<Sample> samples) {
        long ok = 0, errors = 0;
        for (Sample s : samples) { if (!s.covered) return null; ok += s.successes; errors += s.errors; }
        return ok + errors == 0 ? null : errors * 100.0 / (ok + errors);
    }
}
