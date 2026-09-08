package com.aegis.fdx.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §32: measured timings for the operations this application makes performance claims
 * about, so those claims can be checked at runtime rather than asserted from a report.
 *
 * <p>Deliberately small. It records a count, a total, a maximum and a coarse
 * distribution per named operation — enough to answer "is the dashboard still
 * sub-millisecond on this case, on this machine" without becoming a metrics framework
 * that costs more than the operations it measures.
 *
 * <p>Recording is lock-free and allocation-free on the hot path after the first call for
 * a given name, so instrumenting an operation does not change the number being measured.
 * Percentiles come from logarithmic buckets rather than retained samples: exact
 * percentiles would mean keeping every sample, which for a million-item ingest is a
 * memory leak wearing a monitoring costume.
 */
public final class OperationTimings {

    /** Named operations that are timed. Keep this list short and meaningful. */
    public static final String DB_WRITE = "db.write";
    public static final String DB_BATCH_COMMIT = "db.batchCommit";
    public static final String DASHBOARD_SNAPSHOT = "dashboard.snapshot";
    public static final String DASHBOARD_REBUILD = "dashboard.rebuild";
    public static final String RELATIONSHIP_LOOKUP = "relationship.lookup";
    public static final String SEARCH = "search.query";
    public static final String SEARCH_FACETS = "search.facets";
    public static final String INDEX_WRITE = "index.write";
    public static final String EXTRACTION = "pipeline.extraction";
    public static final String OCR = "pipeline.ocr";

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    private static volatile boolean enabled =
            !"false".equalsIgnoreCase(System.getProperty("aegis.instrumentation", "true"));

    private OperationTimings() {
    }

    public static void enabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Records one observation, in nanoseconds. */
    public static void record(String operation, long nanos) {
        if (!enabled) {
            return;
        }
        STATS.computeIfAbsent(operation, k -> new Stat()).add(nanos);
    }

    /**
     * Times {@code work} and records it under {@code operation}. The timing is recorded
     * even when the work throws, because a failing operation's latency is exactly what
     * one wants when a screen has gone slow and started erroring.
     */
    public static <T> T time(String operation, ThrowingSupplier<T> work) throws Exception {
        long t0 = System.nanoTime();
        try {
            return work.get();
        } finally {
            record(operation, System.nanoTime() - t0);
        }
    }

    /** Work that may throw. */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** A snapshot of every operation recorded so far, ordered by total time spent. */
    public static Map<String, Snapshot> snapshot() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        STATS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalNanos.get(),
                        a.getValue().totalNanos.get()))
                .forEach(e -> out.put(e.getKey(), e.getValue().snapshot()));
        return out;
    }

    /** Discards all recorded timings. */
    public static void reset() {
        STATS.clear();
    }

    /** One operation's recorded behaviour. All times in milliseconds. */
    public record Snapshot(long count, double totalMs, double meanMs, double maxMs,
                           double p50Ms, double p95Ms, double p99Ms) {

        @Override
        public String toString() {
            return String.format("n=%,d mean=%.3fms p50=%.3f p95=%.3f p99=%.3f max=%.3f",
                    count, meanMs, p50Ms, p95Ms, p99Ms, maxMs);
        }
    }

    /**
     * Counters for one operation. Buckets are powers of two of microseconds, giving
     * roughly 40 buckets from a microsecond to a couple of minutes — coarse, bounded,
     * and accurate enough to tell 0.1 ms from 15 s, which is the distinction that
     * matters here.
     */
    private static final class Stat {
        private static final int BUCKETS = 40;

        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();
        private final AtomicLong[] histogram = new AtomicLong[BUCKETS];

        Stat() {
            for (int i = 0; i < BUCKETS; i++) {
                histogram[i] = new AtomicLong();
            }
        }

        void add(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
            histogram[bucketOf(nanos)].incrementAndGet();
        }

        private static int bucketOf(long nanos) {
            long micros = Math.max(1, nanos / 1000);
            int b = 63 - Long.numberOfLeadingZeros(micros);
            return Math.min(BUCKETS - 1, Math.max(0, b));
        }

        /** The upper bound of a bucket, in milliseconds. */
        private static double bucketCeilingMs(int bucket) {
            return Math.pow(2, bucket + 1) / 1000.0;
        }

        Snapshot snapshot() {
            long n = count.get();
            if (n == 0) {
                return new Snapshot(0, 0, 0, 0, 0, 0, 0);
            }
            double totalMs = totalNanos.get() / 1e6;
            return new Snapshot(n, totalMs, totalMs / n, maxNanos.get() / 1e6,
                    percentile(n, 0.50), percentile(n, 0.95), percentile(n, 0.99));
        }

        private double percentile(long n, double p) {
            long target = (long) Math.ceil(n * p);
            long seen = 0;
            for (int i = 0; i < BUCKETS; i++) {
                seen += histogram[i].get();
                if (seen >= target) {
                    return bucketCeilingMs(i);
                }
            }
            return maxNanos.get() / 1e6;
        }
    }
}
