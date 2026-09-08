package com.aegis.fdx.spi;

import com.aegis.fdx.model.Item;

import java.io.InputStream;
import java.util.List;

/**
 * A-02: the entire plugin contract. Adding a new format means implementing and
 * registering this one interface — no kernel changes.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and must
 * be stateless and thread-safe; the pipeline invokes them from the worker pool.
 */
public interface Analyzer {

    /** Stable id used in the format-support matrix and license report. */
    String id();

    /** Media types this analyzer claims, e.g. {@code application/pdf}. */
    List<String> mediaTypes();

    /**
     * Cheap sniff over the first bytes of the stream. Return a score in
     * {@code [0,1]}; the pipeline picks the highest scorer. Magic-number based
     * detection also powers signature carving of unallocated space.
     */
    double sniff(byte[] header, String fileName);

    /**
     * Parse one element. Implementations MUST stream (N-04) and MUST NOT throw
     * for malformed input — record the failure on the item instead (N-05).
     *
     * @param sink receives child elements discovered inside a container (F-03)
     */
    void analyze(Item item, InputStream in, ChildSink sink) throws Exception;

    /** Higher priority wins ties when two analyzers sniff equally. */
    default int priority() { return 0; }

    /** Callback for nested elements; re-enqueued at {@code parent.depth()+1}. */
    @FunctionalInterface
    interface ChildSink {
        void emit(Item child, InputStream content);
    }
}
