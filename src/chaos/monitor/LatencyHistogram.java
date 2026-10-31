package chaos.monitor;

/** Bounded logarithmic histogram; percentile estimates round up by at most ~1% (plus 0.01 ms near zero). */
public final class LatencyHistogram {
    private static final double STEP = Math.log(1.01);
    private final long[] counts = new long[2048];
    private long total;
    public void add(double milliseconds) {
        if (!Double.isFinite(milliseconds) || milliseconds < 0) throw new IllegalArgumentException("非法响应时间");
        int index = (int) Math.ceil(Math.log1p(milliseconds) / STEP);
        counts[Math.min(counts.length - 1, index)]++; total++;
    }
    public void merge(LatencyHistogram other) {
        if (other == null) return;
        for (int i = 0; i < counts.length; i++) counts[i] += other.counts[i];
        total += other.total;
    }
    public Double p95() {
        if (total == 0) return null;
        long rank = (long) Math.ceil(total * .95), seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) return Math.expm1(i * STEP);
        }
        return null;
    }
}
