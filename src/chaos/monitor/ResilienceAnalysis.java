package chaos.monitor;

import java.util.*;

/** Pure, window-based analysis. Missing evidence stays unknown rather than becoming a pass. */
public final class ResilienceAnalysis {
    private ResilienceAnalysis() {}

    public static final class Sample {
        public final String phase;
        public final long startMs, endMs, successes, errors;
        public final boolean complete, covered;
        public Sample(String phase, long startMs, long endMs, long successes, long errors,
                      boolean complete, boolean covered) {
            this.phase = phase; this.startMs = startMs; this.endMs = endMs;
            this.successes = successes; this.errors = errors; this.complete = complete; this.covered = covered;
        }
        public double tps() { return successes * 1000.0 / (endMs - startMs); }
    }

    public static final class Result {
        public final double p0;
        public Double pd, pr, dropPct, meanDropPct, restoredPct, regainedLossPct;
        public Double faultErrorPct;
        public Long t0, td, tr, withdrawalMs;
        public boolean recovered, recoveryStable, evidenceComplete;
        public String isolation = "无法判断", continuity = "无法判断", recovery = "无法判断";
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
}
