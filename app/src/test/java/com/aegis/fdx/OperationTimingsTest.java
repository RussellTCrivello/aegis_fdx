package com.aegis.fdx;

import com.aegis.fdx.store.OperationTimings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §32. The instrumentation exists so that performance claims can be checked on the
 * machine that is actually running the application. These tests check that it counts
 * what it says it counts, survives concurrent recording, and cannot itself become the
 * cost it is measuring.
 */
class OperationTimingsTest {

    @BeforeEach
    void clean() {
        OperationTimings.reset();
        OperationTimings.enabled(true);
    }

    @Test
    void countsAndTotalsAreExact() {
        for (int i = 0; i < 100; i++) {
            OperationTimings.record("op", 1_000_000L); // 1 ms each
        }
        OperationTimings.Snapshot s = OperationTimings.snapshot().get("op");
        assertNotNull(s);
        assertEquals(100, s.count());
        assertEquals(100.0, s.totalMs(), 0.001);
        assertEquals(1.0, s.meanMs(), 0.001);
        assertEquals(1.0, s.maxMs(), 0.001);
    }

    @Test
    void percentilesSeparateFastFromSlow() {
        // 980 fast calls at 100 microseconds, 20 pathological calls at 10 seconds. This
        // is the shape that matters: a mean would hide the outliers entirely.
        //
        // The counts are deliberate. p99 of 1,000 samples is the 990th, so the slow tail
        // must be at least 2% of the population to appear there at all; with 99 fast and
        // one slow call the p99 is still a fast call, which is correct and is exactly
        // why a p99 alone is not a safety net on a low-traffic operation.
        for (int i = 0; i < 980; i++) {
            OperationTimings.record("mixed", 100_000L);
        }
        for (int i = 0; i < 20; i++) {
            OperationTimings.record("mixed", 10_000_000_000L);
        }

        OperationTimings.Snapshot s = OperationTimings.snapshot().get("mixed");
        assertEquals(1000, s.count());
        assertTrue(s.p50Ms() < 1.0, "p50 should stay sub-millisecond, was " + s.p50Ms());
        assertTrue(s.p95Ms() < 1.0, "p95 should stay sub-millisecond, was " + s.p95Ms());
        assertTrue(s.p99Ms() >= 1000.0, "p99 must expose the outlier, was " + s.p99Ms());
        assertEquals(10_000.0, s.maxMs(), 1.0);
    }

    @Test
    void bucketsAreWithinAFactorOfTwoOfTheTruth() {
        // Logarithmic buckets round up to the next power of two of microseconds, so a
        // reported percentile is never below the truth and never more than 2x above it.
        long[] samples = {5_000L, 250_000L, 3_000_000L, 40_000_000L};
        for (long ns : samples) {
            OperationTimings.reset();
            for (int i = 0; i < 50; i++) {
                OperationTimings.record("b", ns);
            }
            double actualMs = ns / 1e6;
            double reported = OperationTimings.snapshot().get("b").p50Ms();
            assertTrue(reported >= actualMs,
                    "bucket must not under-report: " + reported + " < " + actualMs);
            assertTrue(reported <= actualMs * 2.001,
                    "bucket must not over-report by more than 2x: " + reported
                            + " vs " + actualMs);
        }
    }

    @Test
    void timeRecordsEvenWhenTheWorkThrows() throws Exception {
        assertThrows(IllegalStateException.class, () -> OperationTimings.time("failing", () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, OperationTimings.snapshot().get("failing").count());

        String v = OperationTimings.time("ok", () -> "value");
        assertEquals("value", v);
        assertEquals(1, OperationTimings.snapshot().get("ok").count());
    }

    @Test
    void disablingStopsRecordingEntirely() {
        OperationTimings.enabled(false);
        try {
            for (int i = 0; i < 1000; i++) {
                OperationTimings.record("off", 1_000_000L);
            }
            assertFalse(OperationTimings.snapshot().containsKey("off"));
        } finally {
            OperationTimings.enabled(true);
        }
    }

    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        OperationTimings.record("shared", 1_000L);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals((long) threads * perThread,
                OperationTimings.snapshot().get("shared").count());
    }

    @Test
    void recordingIsCheapEnoughNotToDistortWhatItMeasures() {
        // Warm up, then measure. The budget is 2 microseconds per call: the fastest
        // operation instrumented here is a dashboard read at ~140 microseconds, so the
        // measurement must cost well under a percent of it.
        for (int i = 0; i < 200_000; i++) {
            OperationTimings.record("warm", 1000L);
        }
        OperationTimings.reset();

        int n = 500_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            OperationTimings.record("cost", 1000L);
        }
        double perCallNanos = (System.nanoTime() - t0) / (double) n;
        assertTrue(perCallNanos < 2000.0,
                "instrumentation cost " + perCallNanos + " ns/call, budget 2000");
    }

    @Test
    void snapshotIsOrderedByTotalTimeSpent() {
        OperationTimings.record("cheap", 1_000L);
        OperationTimings.record("expensive", 5_000_000_000L);
        OperationTimings.record("middling", 50_000_000L);

        var keys = OperationTimings.snapshot().keySet().stream().toList();
        assertEquals(java.util.List.of("expensive", "middling", "cheap"), keys);
    }

    @Test
    void unrecordedOperationIsAbsentRatherThanZero() {
        Map<String, OperationTimings.Snapshot> snap = OperationTimings.snapshot();
        assertTrue(snap.isEmpty());
        assertFalse(snap.containsKey(OperationTimings.OCR));
    }
}
