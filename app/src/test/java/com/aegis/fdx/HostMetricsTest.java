package com.aegis.fdx;

import com.aegis.fdx.facade.HostMetrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host resource readings must be measured, or admit that they are not.
 *
 * <p>The Python reference draws CPU, memory and disk meters from literals in its
 * template. The rule this suite enforces is the opposite one: every figure the
 * Performance screen shows comes from the operating system, and anything this platform
 * does not report is returned as unavailable rather than as a plausible number. A
 * reading that "looks right" but was invented would pass a screenshot review and fail
 * an investigation, so it is treated as a defect here.
 *
 * <p>The suite is deliberately platform-tolerant: it never requires a counter to exist,
 * only that an existing counter is sane and a missing counter is declared missing. It
 * can be run under JUnit or directly through {@link #main(String[])}, so it is part of
 * the offline battery as well as the Gradle build.
 */
public final class HostMetricsTest {

    @Test
    @DisplayName("A reading always reports the counters the JVM itself guarantees")
    void jvmCountersAreAlwaysReal() throws Exception {
        HostMetrics.Reading r = HostMetrics.read(tempDir(), 1234L);
        assertNotNull(r, "read() must always return a sample");
        assertTrue(r.processors() >= 1, "a running JVM has at least one processor");
        assertTrue(r.heapUsed() > 0, "a running JVM has a non-empty heap");
        assertTrue(r.heapCommitted() >= r.heapUsed(),
                "committed heap cannot be smaller than used heap");
        assertTrue(r.heapMax() >= r.heapUsed(),
                "maximum heap cannot be smaller than used heap");
        double heap = r.heapFraction();
        assertTrue(heap > 0 && heap <= 1, "heap fraction must be a real fraction: " + heap);
        assertEquals(1234L, r.caseBytes(), "the case size passed in is reported back");
    }

    @Test
    @DisplayName("CPU figures are either measured in 0..1 or declared unavailable")
    void cpuIsMeasuredOrDeclaredMissing() throws Exception {
        HostMetrics.Reading r = HostMetrics.read(tempDir(), -1);

        if (r.hasProcessCpu()) {
            assertTrue(r.processCpu() >= 0 && r.processCpu() <= 1,
                    "process CPU out of range: " + r.processCpu());
        } else {
            assertEquals(HostMetrics.UNAVAILABLE, r.processCpu(),
                    "an unavailable CPU figure must be UNAVAILABLE, not a default");
        }

        if (r.hasSystemCpu()) {
            assertTrue(r.systemCpu() >= 0 && r.systemCpu() <= 1,
                    "system CPU out of range: " + r.systemCpu());
        } else {
            assertEquals(HostMetrics.UNAVAILABLE, r.systemCpu(),
                    "an unavailable CPU figure must be UNAVAILABLE, not a default");
        }
    }

    @Test
    @DisplayName("Physical memory is either consistent or declared unavailable")
    void physicalMemoryIsConsistent() throws Exception {
        HostMetrics.Reading r = HostMetrics.read(tempDir(), -1);
        if (r.hasPhysicalMemory()) {
            assertTrue(r.physicalTotal() > 0, "total physical memory must be positive");
            assertTrue(r.physicalFree() <= r.physicalTotal(),
                    "free memory cannot exceed installed memory");
            assertEquals(r.physicalTotal() - r.physicalFree(), r.physicalUsed(),
                    "used memory is derived, not guessed");
            double used = r.physicalUsedFraction();
            assertTrue(used >= 0 && used <= 1, "memory fraction out of range: " + used);
        } else {
            assertEquals(HostMetrics.UNAVAILABLE, r.physicalUsedFraction(),
                    "no physical memory counter means no fraction, not zero");
        }
    }

    @Test
    @DisplayName("The case volume is measured from the filesystem that holds the case")
    void volumeIsMeasuredFromTheRealFilesystem() throws Exception {
        Path dir = tempDir();
        HostMetrics.Reading r = HostMetrics.read(dir, 4096L);
        assertTrue(r.hasVolume(),
                "a real directory on a real filesystem must report capacity");
        assertNotNull(r.volumeName(), "the volume is named so an operator can identify it");
        assertTrue(r.volumeTotal() > 0, "volume size must be positive");
        assertTrue(r.volumeUsable() <= r.volumeTotal(),
                "free space cannot exceed the volume");
        double used = r.volumeUsedFraction();
        assertTrue(used >= 0 && used <= 1, "volume fraction out of range: " + used);

        // The figure tracks the actual filesystem, not a cached constant.
        HostMetrics.Reading again = HostMetrics.read(dir, 4096L);
        assertEquals(r.volumeTotal(), again.volumeTotal(),
                "volume size is stable between samples on the same filesystem");
    }

    @Test
    @DisplayName("A path that cannot be resolved yields unavailable, never a number")
    void unknownVolumeIsUnavailable() {
        HostMetrics.Reading r = HostMetrics.read(null, -1);
        assertTrue(!r.hasVolume(), "no path means no volume figures");
        assertEquals(HostMetrics.UNAVAILABLE_BYTES, r.volumeTotal());
        assertEquals(HostMetrics.UNAVAILABLE_BYTES, r.volumeUsable());
        assertEquals(HostMetrics.UNAVAILABLE, r.volumeUsedFraction());
        assertEquals(HostMetrics.UNAVAILABLE, r.caseFractionOfVolume(),
                "an unknown case share is unavailable, not 0%");
    }

    @Test
    @DisplayName("Unavailable figures render as words, measured figures as percentages")
    void unavailableRendersAsText() {
        assertEquals("not available",
                HostMetrics.percent(HostMetrics.UNAVAILABLE, "not available"),
                "the interface must be able to say 'not available' instead of '0%'");
        assertEquals("50%", HostMetrics.percent(0.5, "not available"));
        assertEquals("0%", HostMetrics.percent(0.0, "not available"),
                "a genuinely measured zero is still a measurement");
        assertEquals("100%", HostMetrics.percent(1.0, "not available"));
    }

    @Test
    @DisplayName("Sampling repeatedly is cheap and never throws")
    void samplingIsRepeatable() throws Exception {
        Path dir = tempDir();
        long t0 = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            HostMetrics.Reading r = HostMetrics.read(dir, i);
            assertNotNull(r);
        }
        long millis = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(millis < 3000,
                "50 samples took " + millis + " ms; the screen samples every 2 s");
    }

    private static Path tempDir() throws Exception {
        Path p = Path.of(System.getProperty("java.io.tmpdir"), "aegis-hostmetrics");
        Files.createDirectories(p);
        return p;
    }

    // ---- offline runner ---------------------------------------------------

    /** Runs the same assertions without a JUnit launcher, for the offline battery. */
    public static void main(String[] args) throws Exception {
        HostMetricsTest t = new HostMetricsTest();
        int passed = 0;
        int failed = 0;
        // An explicit list keeps the output readable and the order stable.
        String[][] names = {
            {"JVM counters are always real"},
            {"CPU is measured or declared missing"},
            {"physical memory is consistent"},
            {"the case volume is measured"},
            {"an unknown volume is unavailable"},
            {"unavailable renders as words"},
            {"sampling is repeatable"},
        };
        Check[] body = {
            t::jvmCountersAreAlwaysReal,
            t::cpuIsMeasuredOrDeclaredMissing,
            t::physicalMemoryIsConsistent,
            t::volumeIsMeasuredFromTheRealFilesystem,
            t::unknownVolumeIsUnavailable,
            t::unavailableRendersAsText,
            t::samplingIsRepeatable,
        };
        for (int i = 0; i < body.length; i++) {
            try {
                body[i].run();
                System.out.println("  ok    " + names[i][0]);
                passed++;
            } catch (Throwable e) {
                System.out.println("  FAIL  " + names[i][0] + " — " + e);
                failed++;
            }
        }

        // Show what this machine actually reports, so the run is auditable.
        HostMetrics.Reading r = HostMetrics.read(tempDir(), -1);
        System.out.println("  host  process CPU "
                + HostMetrics.percent(r.processCpu(), "not reported")
                + " · system CPU " + HostMetrics.percent(r.systemCpu(), "not reported")
                + " · heap " + HostMetrics.percent(r.heapFraction(), "not reported")
                + " · memory " + HostMetrics.percent(r.physicalUsedFraction(), "not reported")
                + " · volume " + HostMetrics.percent(r.volumeUsedFraction(), "not reported"));

        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface Check {
        void run() throws Exception;
    }
}
