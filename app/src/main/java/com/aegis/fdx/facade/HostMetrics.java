package com.aegis.fdx.facade;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Live host resource readings: CPU, memory and the disk holding the case.
 *
 * <p>Why this class exists, and what it deliberately does not do. The Python reference
 * shows a "Resource Optimization" panel with CPU, memory and disk meters, but those
 * meters are literals in the template (45%, 62%, 38%) — they are not measured. Rather
 * than copy a decorative panel, this reads the real numbers the JVM can actually obtain
 * from the operating system, and where a platform does not expose a figure it says so
 * instead of inventing one. Every accessor is therefore three-valued: a measured value,
 * or {@link #UNAVAILABLE}, never a plausible-looking default.
 *
 * <p>The richer CPU and physical-memory counters live on
 * {@code com.sun.management.OperatingSystemMXBean}, an extension interface that is
 * present on HotSpot-derived JDKs but is not guaranteed by the platform specification,
 * and whose method names changed across versions ({@code getSystemCpuLoad} became
 * {@code getCpuLoad}, {@code getTotalPhysicalMemorySize} became
 * {@code getTotalMemorySize}). Binding to it directly would make the application fail
 * to start on a runtime that lacks it, which would be a new dependency for a
 * non-essential display. It is therefore reached reflectively: present means measured,
 * absent means reported as unavailable.
 *
 * <p>This class performs no I/O beyond querying the filesystem for the case volume, and
 * is safe to call repeatedly — the interface samples it on a timer.
 */
public final class HostMetrics {

    /** Returned by every reading that this platform does not expose. */
    public static final double UNAVAILABLE = -1.0;

    /** Returned by every byte-count reading that this platform does not expose. */
    public static final long UNAVAILABLE_BYTES = -1L;

    private HostMetrics() {
    }

    /**
     * One sample of host state.
     *
     * <p>Fractions are 0..1, or {@link HostMetrics#UNAVAILABLE}. Byte counts are
     * absolute, or {@link HostMetrics#UNAVAILABLE_BYTES}.
     */
    public record Reading(
            double processCpu,
            double systemCpu,
            long heapUsed,
            long heapCommitted,
            long heapMax,
            long physicalTotal,
            long physicalFree,
            int processors,
            double loadAverage,
            String volumeName,
            long volumeTotal,
            long volumeUsable,
            long caseBytes) {

        public boolean hasProcessCpu() {
            return isMeasured(processCpu);
        }

        public boolean hasSystemCpu() {
            return isMeasured(systemCpu);
        }

        public boolean hasPhysicalMemory() {
            return physicalTotal > 0 && physicalFree >= 0;
        }

        public boolean hasVolume() {
            return volumeTotal > 0 && volumeUsable >= 0;
        }

        public boolean hasLoadAverage() {
            return loadAverage >= 0;
        }

        /** Heap in use as a fraction of the maximum the JVM may grow to. */
        public double heapFraction() {
            return heapMax > 0 ? clampFraction((double) heapUsed / heapMax) : UNAVAILABLE;
        }

        /** Physical memory in use as a fraction of the total installed. */
        public double physicalUsedFraction() {
            if (!hasPhysicalMemory()) {
                return UNAVAILABLE;
            }
            return clampFraction((double) (physicalTotal - physicalFree) / physicalTotal);
        }

        /** Occupied space on the volume holding the case, as a fraction of its size. */
        public double volumeUsedFraction() {
            if (!hasVolume()) {
                return UNAVAILABLE;
            }
            return clampFraction((double) (volumeTotal - volumeUsable) / volumeTotal);
        }

        public long physicalUsed() {
            return hasPhysicalMemory() ? physicalTotal - physicalFree : UNAVAILABLE_BYTES;
        }

        /** The case's own share of the volume, as a fraction; useful for capacity planning. */
        public double caseFractionOfVolume() {
            if (!hasVolume() || caseBytes < 0) {
                return UNAVAILABLE;
            }
            return clampFraction((double) caseBytes / volumeTotal);
        }
    }

    /**
     * Takes a sample.
     *
     * @param caseFolder any path on the volume to report on; may be null, in which case
     *                   no volume figures are produced
     * @param caseBytes  bytes the case occupies, or a negative number if not known
     */
    public static Reading read(Path caseFolder, long caseBytes) {
        Runtime rt = Runtime.getRuntime();
        long heapCommitted = rt.totalMemory();
        long heapUsed = heapCommitted - rt.freeMemory();
        long heapMax = rt.maxMemory();

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double processCpu = fraction(os, "getProcessCpuLoad");
        double systemCpu = fraction(os, "getCpuLoad");
        if (!isMeasured(systemCpu)) {
            systemCpu = fraction(os, "getSystemCpuLoad");
        }
        long physicalTotal = bytes(os, "getTotalMemorySize", "getTotalPhysicalMemorySize");
        long physicalFree = bytes(os, "getFreeMemorySize", "getFreePhysicalMemorySize");

        double load = os.getSystemLoadAverage();
        if (Double.isNaN(load) || load < 0) {
            load = UNAVAILABLE;
        }

        String volumeName = null;
        long volumeTotal = UNAVAILABLE_BYTES;
        long volumeUsable = UNAVAILABLE_BYTES;
        if (caseFolder != null) {
            try {
                Path probe = caseFolder;
                while (probe != null && !Files.exists(probe)) {
                    probe = probe.getParent();
                }
                if (probe != null) {
                    FileStore store = Files.getFileStore(probe);
                    volumeName = store.name() + " (" + store.type() + ")";
                    volumeTotal = store.getTotalSpace();
                    volumeUsable = store.getUsableSpace();
                }
            } catch (Exception e) {
                // A filesystem that refuses to report capacity is reported as unavailable,
                // not guessed at.
                volumeName = null;
                volumeTotal = UNAVAILABLE_BYTES;
                volumeUsable = UNAVAILABLE_BYTES;
            }
        }

        return new Reading(processCpu, systemCpu, heapUsed, heapCommitted, heapMax,
                physicalTotal, physicalFree, rt.availableProcessors(), load,
                volumeName, volumeTotal, volumeUsable, caseBytes);
    }

    /** Formats a fraction as a percentage, or the given text when it was not measured. */
    public static String percent(double fraction, String whenUnavailable) {
        if (!isMeasured(fraction)) {
            return whenUnavailable;
        }
        return String.format("%.0f%%", fraction * 100.0);
    }

    private static boolean isMeasured(double v) {
        return !Double.isNaN(v) && v >= 0;
    }

    private static double clampFraction(double v) {
        if (Double.isNaN(v)) {
            return UNAVAILABLE;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Reads a 0..1 CPU figure reflectively.
     *
     * <p>The bean returns a negative number when it has not yet accumulated enough
     * samples, which is a genuine "not yet known" and is passed through as unavailable.
     */
    private static double fraction(OperatingSystemMXBean os, String method) {
        Object v = invoke(os, method);
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return isMeasured(d) ? Math.min(1.0, d) : UNAVAILABLE;
        }
        return UNAVAILABLE;
    }

    private static long bytes(OperatingSystemMXBean os, String... methods) {
        for (String m : methods) {
            Object v = invoke(os, m);
            if (v instanceof Number n && n.longValue() >= 0) {
                return n.longValue();
            }
        }
        return UNAVAILABLE_BYTES;
    }

    /**
     * Calls a method on the extended bean interface, if this runtime has it.
     *
     * <p>The lookup is done on {@code com.sun.management.OperatingSystemMXBean} — the
     * exported interface — and not on the bean's implementation class, which lives in a
     * package the module system does not open. Looking it up on the implementation would
     * fail with an access error on every modern JDK.
     */
    private static Object invoke(OperatingSystemMXBean os, String method) {
        Class<?> extended = EXTENDED_BEAN;
        if (extended == null || !extended.isInstance(os)) {
            return null;
        }
        try {
            Method m = extended.getMethod(method);
            return m.invoke(os);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Class<?> EXTENDED_BEAN = extendedBean();

    private static Class<?> extendedBean() {
        try {
            return Class.forName("com.sun.management.OperatingSystemMXBean");
        } catch (Throwable t) {
            return null;
        }
    }
}
