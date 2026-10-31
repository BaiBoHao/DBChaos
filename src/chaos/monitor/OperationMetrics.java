package chaos.monitor;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Bounded-memory, thread-safe operation counters. Take final snapshots after workers stop. */
public final class OperationMetrics {
    private final AtomicLong attempts = new AtomicLong(), successes = new AtomicLong(), failures = new AtomicLong();
    private final AtomicLong totalNs = new AtomicLong(), maxNs = new AtomicLong(), capacity = new AtomicLong();
    private final AtomicLongArray latency = new AtomicLongArray(64);
    private final ConcurrentHashMap<String, AtomicLong> states = new ConcurrentHashMap<>();

    public void begin() { attempts.incrementAndGet(); }
    public void success(long nanos) { finish(nanos); successes.incrementAndGet(); }
    public void failure(long nanos, SQLException error) {
        finish(nanos);
        String state = error.getSQLState();
        if (state == null || !state.matches("[A-Z0-9]{5}")) state = "UNKNOWN";
        // Bound even unusual JDBC implementations that generate arbitrary SQLStates.
        AtomicLong counter = states.get(state);
        if (counter == null) synchronized (states) {
            if (!states.containsKey(state) && states.size() >= 32) state = "OTHER";
            counter = states.computeIfAbsent(state, k -> new AtomicLong());
        }
        counter.incrementAndGet();
        if ("53300".equals(error.getSQLState()) || error.getErrorCode() == 1040 || error.getErrorCode() == 1203)
            capacity.incrementAndGet();
        failures.incrementAndGet();
    }

    private void finish(long nanos) {
        nanos = Math.max(0, nanos);
        totalNs.addAndGet(nanos);
        maxNs.accumulateAndGet(nanos, Math::max);
        long micros = Math.max(1, nanos / 1000 + (nanos % 1000 == 0 ? 0 : 1));
        int bucket = 64 - Long.numberOfLeadingZeros(micros - 1);
        latency.incrementAndGet(bucket);
    }

    public Snapshot snapshot() {
        Map<String, Long> errors = new TreeMap<>();
        states.forEach((key, value) -> errors.put(key, value.get()));
        long success = successes.get(), failed = failures.get(), completed = success + failed;
        long rank = completed == 0 ? 0 : completed - completed / 20; // ceil(0.95*N)
        long cumulative = 0;
        double p95 = Double.NaN;
        if (completed > 0) for (int i = 0; i < latency.length(); i++) {
            cumulative += latency.get(i);
            if (cumulative >= rank) { p95 = Math.scalb(1.0, i) / 1000.0; break; }
        }
        return new Snapshot(attempts.get(), success, failed, capacity.get(),
                completed == 0 ? Double.NaN : totalNs.get() / 1e6 / completed,
                completed == 0 ? Double.NaN : maxNs.get() / 1e6, p95, errors);
    }

    public static final class Snapshot {
        public final long attempts, successes, failures, capacityFailures;
        public final double meanMs, maxMs, p95UpperMs;
        public final Map<String, Long> sqlStates;
        private Snapshot(long attempts, long successes, long failures, long capacityFailures,
                         double meanMs, double maxMs, double p95UpperMs, Map<String, Long> states) {
            this.attempts = attempts; this.successes = successes; this.failures = failures;
            this.capacityFailures = capacityFailures; this.meanMs = meanMs; this.maxMs = maxMs;
            this.p95UpperMs = p95UpperMs; this.sqlStates = Collections.unmodifiableMap(states);
        }
        public long completed() { return successes + failures; }
        public long unfinished() { return Math.max(0, attempts - completed()); }
        /** Completed operations only; unfinished attempts are reported separately. */
        public double successPct() { return completed() == 0 ? Double.NaN : 100.0 * successes / completed(); }
        public double completedPerSecond(double seconds) { return seconds > 0 ? completed() / seconds : Double.NaN; }
    }
}
