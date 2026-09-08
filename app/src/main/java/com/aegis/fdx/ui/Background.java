package com.aegis.fdx.ui;

import javafx.application.Platform;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * §27: the one way a screen is allowed to do slow work.
 *
 * <p>Every dashboard, analysis and relationship screen needs the same shape — gather
 * figures off the JavaFX application thread, then apply them on it — and every screen
 * that hand-rolls it gets one of the same three things wrong: it blocks the thread, it
 * applies a stale result over a newer one, or it lets an exception escape into a
 * background thread where nobody sees it.
 *
 * <h2>Superseding results</h2>
 * A refresh started later always wins. When an operator changes a filter four times in
 * a second, four queries may be in flight and they can finish in any order; without
 * ordering, the screen settles on whichever query happened to be slowest, which is
 * usually the one for a filter the operator has already abandoned. Each
 * {@link Job} carries a monotonic sequence number and a result is dropped if a newer
 * one has already been applied.
 *
 * <h2>Failure</h2>
 * A background failure is reported to the screen's failure handler on the FX thread,
 * never swallowed and never left to a default uncaught-exception handler. A dashboard
 * that silently keeps displaying the previous numbers after its query failed is worse
 * than one that says it could not load.
 */
public final class Background {

    private static final AtomicLong THREAD_SEQ = new AtomicLong();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(
            (ThreadFactory) r -> {
                Thread t = new Thread(r, "aegis-ui-bg-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);           // never holds the application open
                return t;
            });

    private Background() {
    }

    /** A refresh slot for one screen; later refreshes supersede earlier ones. */
    public static Job job() {
        return new Job();
    }

    /** Shuts the shared pool down at application exit. */
    public static void shutdown() {
        POOL.shutdownNow();
    }

    /**
     * One screen's refresh slot. Hold a single instance per screen so that its
     * refreshes are ordered with respect to each other and to nothing else.
     */
    public static final class Job {

        private final AtomicLong issued = new AtomicLong();
        private volatile long applied = -1;

        /**
         * Runs {@code work} off the FX thread and hands the result to {@code onSuccess}
         * on it, unless a later call to this same job has already delivered.
         *
         * @param work      the slow part: queries, aggregation, index reads. Must not
         *                  touch the scene graph.
         * @param onSuccess applied on the FX thread with the result
         * @param onFailure applied on the FX thread when {@code work} threw
         */
        public <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            final long seq = issued.incrementAndGet();
            POOL.execute(() -> {
                T value;
                try {
                    value = work.get();
                } catch (Throwable t) {
                    Platform.runLater(() -> {
                        if (claim(seq)) {
                            onFailure.accept(t);
                        }
                    });
                    return;
                }
                Platform.runLater(() -> {
                    if (claim(seq)) {
                        onSuccess.accept(value);
                    }
                });
            });
        }

        /** True when this result is newer than whatever has already been applied. */
        private synchronized boolean claim(long seq) {
            if (seq <= applied) {
                return false;               // a later refresh already won
            }
            applied = seq;
            return true;
        }

        /** True when a refresh has been issued but not yet applied. */
        public boolean busy() {
            return applied < issued.get();
        }
    }
}
