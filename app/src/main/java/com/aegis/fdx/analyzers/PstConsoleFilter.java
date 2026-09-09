package com.aegis.fdx.analyzers;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps java-libpst's per-item diagnostics off the console without losing the count.
 *
 * <p>Every real mailbox holds items whose message class the library does not model:
 * Teams messages, Skype snapshots, delivery reports, out-of-office templates, sharing
 * invitations. {@code PSTObject.createAppropriatePSTMessageObject} prints
 * {@code "Unknown message type: ..."} to {@code System.out} for each one, with no log
 * level and no switch to turn it off, so a large mailbox buries real progress output
 * under hundreds of lines. The items themselves are unaffected: the factory still
 * returns them as plain messages and the analyzer still ingests them with whatever
 * subject and body they carry.
 *
 * <p>This filter, installed once per JVM, drops exactly those lines from the console
 * and counts them instead: a JVM-wide total plus a per-thread count, so mailboxes
 * ingested concurrently on worker threads are still attributed correctly. The lines
 * themselves are not preserved anywhere, deliberately: each one carries only a class
 * name, the item it describes is ingested regardless, and re-emitting diagnostics
 * from inside the console write path risks lock interplay with logging backends for
 * no forensic gain. The count is what matters, and it lands in the mailbox item's
 * {@code PST-Unknown-Classes} metadata.
 *
 * <p>Every other byte passes through to the wrapped stream untouched, and code that
 * captured {@code System.out} before installation keeps writing to it directly.
 */
public final class PstConsoleFilter {

    /** The library's exact wording; matching is prefix-based, nothing else is dropped. */
    private static final String PREFIX = "Unknown message type: ";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicLong SUPPRESSED_TOTAL = new AtomicLong();
    private static final ThreadLocal<Long> SUPPRESSED_THREAD =
            ThreadLocal.withInitial(() -> 0L);

    private PstConsoleFilter() { }

    /**
     * Wraps {@code System.out} in the filter. Idempotent: only the first call across
     * all threads takes effect.
     */
    public static void installOnce() {
        if (INSTALLED.compareAndSet(false, true)) {
            System.setOut(wrap(System.out));
        }
    }

    /**
     * Wraps any {@code PrintStream} in the filtering layer. Production code uses
     * {@link #installOnce()}; this overload exists so tests can exercise the filter
     * against a buffer without touching the JVM-wide console.
     */
    public static PrintStream wrap(PrintStream downstream) {
        Objects.requireNonNull(downstream, "downstream");
        return new PrintStream(new LineFilter(downstream), true, StandardCharsets.UTF_8);
    }

    /** How many library diagnostics have been dropped JVM-wide. */
    public static long suppressedTotal() {
        return SUPPRESSED_TOTAL.get();
    }

    /** How many have been dropped on the calling thread since the last reset. */
    public static long threadSuppressed() {
        return SUPPRESSED_THREAD.get();
    }

    /** Restarts the calling thread's count, e.g. at the start of one mailbox walk. */
    public static void resetThreadSuppressed() {
        SUPPRESSED_THREAD.set(0L);
    }

    private static void noteSuppressed() {
        SUPPRESSED_TOTAL.incrementAndGet();
        SUPPRESSED_THREAD.set(SUPPRESSED_THREAD.get() + 1);
    }

    /**
     * Buffers bytes until end-of-line, then either drops or forwards the line.
     * All writes arrive under the wrapping {@code PrintStream}'s lock, so the
     * buffer needs no synchronisation of its own.
     */
    private static final class LineFilter extends FilterOutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(256);
        /**
         * True once part of the current line has already gone downstream (via
         * {@link #flush()}). A line is only dropped when it was buffered whole;
         * a line whose prefix already leaked must pass through complete.
         */
        private boolean tainted;

        LineFilter(OutputStream downstream) {
            super(downstream);
        }

        @Override
        public void write(int b) throws IOException {
            if (b == '\n') {
                emitLine();
            } else {
                pending.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int end = off + len;
            int start = off;
            for (int i = off; i < end; i++) {
                if (b[i] == '\n') {
                    pending.write(b, start, i - start);
                    emitLine();
                    start = i + 1;
                }
            }
            pending.write(b, start, end - start);
        }

        @Override
        public void flush() throws IOException {
            if (pending.size() > 0) {
                // Bytes already promised downstream by a flush must go, even
                // mid-line; the line is then too late to drop once completed.
                out.write(pending.toByteArray());
                pending.reset();
                tainted = true;
            }
            out.flush();
        }

        private void emitLine() throws IOException {
            byte[] raw = pending.toByteArray();
            pending.reset();
            boolean whole = !tainted;
            tainted = false;
            // Decode for comparison only; what goes downstream is always the
            // original bytes, so foreign encodings survive untouched.
            String line = new String(stripCr(raw), StandardCharsets.UTF_8);
            if (whole && line.startsWith(PREFIX)) {
                noteSuppressed();
                return;
            }
            out.write(raw);
            out.write('\n');
        }

        private static byte[] stripCr(byte[] raw) {
            if (raw.length > 0 && raw[raw.length - 1] == '\r') {
                return Arrays.copyOf(raw, raw.length - 1);
            }
            return raw;
        }
    }
}
