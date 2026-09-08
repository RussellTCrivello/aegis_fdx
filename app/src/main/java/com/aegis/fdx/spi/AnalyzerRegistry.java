package com.aegis.fdx.spi;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * A-02: discovery and dispatch for {@link Analyzer} plugins.
 *
 * <p>Built-in analyzers are registered explicitly; third-party ones are picked up
 * from the module path via {@link ServiceLoader}. Adding a format never requires a
 * kernel change — implement {@link Analyzer}, register it, done.
 */
public final class AnalyzerRegistry {

    /** Bytes sniffed from the head of each stream for magic-number detection. */
    public static final int HEADER_BYTES = 4096;

    private final List<Analyzer> analyzers = new ArrayList<>();

    public AnalyzerRegistry() { }

    public AnalyzerRegistry register(Analyzer a) {
        analyzers.add(a);
        return this;
    }

    /** Loads any {@code com.aegis.fdx.spi.Analyzer} providers on the path. */
    public AnalyzerRegistry loadServiceProviders() {
        for (Analyzer a : ServiceLoader.load(Analyzer.class)) {
            analyzers.add(a);
        }
        return this;
    }

    public List<Analyzer> all() {
        return List.copyOf(analyzers);
    }

    /**
     * Reads up to {@link #HEADER_BYTES} without consuming them, so the winning
     * analyzer still sees a complete stream.
     *
     * @param in must support {@link InputStream#mark(int)} (wrap in
     *           {@link BufferedInputStream})
     */
    public byte[] peek(InputStream in) throws IOException {
        in.mark(HEADER_BYTES + 1);
        byte[] buf = new byte[HEADER_BYTES];
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        in.reset();
        if (n == buf.length) return buf;
        byte[] out = new byte[Math.max(n, 0)];
        System.arraycopy(buf, 0, out, 0, out.length);
        return out;
    }

    /**
     * Highest {@link Analyzer#sniff} score wins; ties break on
     * {@link Analyzer#priority()}. Returns {@code null} when nothing claims the
     * element — the caller then marks it {@code UNSUPPORTED} and stores it anyway
     * (F-02: never ignored).
     */
    public Analyzer select(byte[] header, String fileName) {
        return analyzers.stream()
                .map(a -> new Scored(a, safeSniff(a, header, fileName)))
                .filter(s -> s.score > 0)
                .max(Comparator.<Scored>comparingDouble(s -> s.score)
                        .thenComparingInt(s -> s.analyzer.priority()))
                .map(s -> s.analyzer)
                .orElse(null);
    }

    private static double safeSniff(Analyzer a, byte[] header, String name) {
        try {
            return a.sniff(header, name);
        } catch (RuntimeException e) {
            return 0;   // N-05: a broken plugin must not break detection
        }
    }

    private record Scored(Analyzer analyzer, double score) { }
}
