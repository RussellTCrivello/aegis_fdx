package com.aegis.fdx.engine;

import com.aegis.fdx.analyzers.ArchiveAnalyzer;
import com.aegis.fdx.analyzers.EmlAnalyzer;
import com.aegis.fdx.analyzers.ImageAnalyzer;
import com.aegis.fdx.analyzers.MboxAnalyzer;
import com.aegis.fdx.analyzers.MsgAnalyzer;
import com.aegis.fdx.analyzers.OfficeAnalyzer;
import com.aegis.fdx.analyzers.PdfAnalyzer;
import com.aegis.fdx.analyzers.PstAnalyzer;
import com.aegis.fdx.analyzers.TextAnalyzer;
import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.spi.Analyzer;
import com.aegis.fdx.spi.AnalyzerRegistry;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A-01: the production pipeline.
 *
 * <pre>
 * Intake ──► Analyse/Extract ──► Hash/Dedupe ──► Index ──► Store
 * </pre>
 *
 * <p>Every stage is a private method on one element, so any stage can be replayed
 * independently. Children discovered during extraction are pushed onto a work
 * stack and run through the identical stage sequence, which is what makes nesting
 * recursive to {@link CaseSettings#maxArchiveDepth()}.
 *
 * <p><b>Failure policy (N-05):</b> no element can abort the run. Analyzer
 * exceptions become {@code ERROR} status plus a recorded reason; unclaimed types
 * become {@code UNSUPPORTED} and are still stored, hashed and indexed; encrypted
 * containers become {@code LOCKED}. The loop always continues.
 */
public final class IngestPipeline {

    /** Elements bigger than this are streamed from disk, never fully buffered. */
    private static final long STREAM_THRESHOLD = 100L * 1024 * 1024;   // N-04

    private final CaseFolder folder;
    private final CaseDatabase db;
    private final LuceneIndex index;
    private final CaseSettings settings;
    private final AnalyzerRegistry registry;
    private final Consumer<EngineEvent> listener;

    /** M3-A. Null when OCR is disabled for the case. */
    private final com.aegis.fdx.ocr.OcrStage ocrStage;
    /** Elements deferred to the async OCR sweep: id -> bytes. */
    private final java.util.Map<String, byte[]> ocrQueue =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private java.util.concurrent.ExecutorService ocrPool;

    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong locked = new AtomicLong();
    private final AtomicLong unsupported = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    /** Elements skipped because a previous run already completed them (AT-05). */
    private final AtomicLong skipped = new AtomicLong();

    private long idSeq;

    public IngestPipeline(CaseFolder folder, CaseDatabase db, LuceneIndex index,
                          CaseSettings settings, Consumer<EngineEvent> listener) {
        this.folder = folder;
        this.db = db;
        this.index = index;
        this.settings = settings;
        this.listener = listener == null ? e -> { } : listener;
        this.registry = defaultRegistry(settings);

        com.aegis.fdx.ocr.OcrStage stage = null;
        if (settings.ocrEnabled()) {                       // F-09, per-case toggle
            com.aegis.fdx.ocr.OcrEngine eng = new com.aegis.fdx.ocr.OcrEngine();
            if (eng.available()) {
                stage = new com.aegis.fdx.ocr.OcrStage(eng, settings.ocrLanguages());
            }
        }
        this.ocrStage = stage;
    }

    /** M3-A. Exposed for the processing report; null when OCR is off/unavailable. */
    public com.aegis.fdx.ocr.OcrStage ocrStage() { return ocrStage; }

    /** The built-in analyzer set. Third-party plugins are appended via the SPI. */
    public static AnalyzerRegistry defaultRegistry(CaseSettings settings) {
        List<String> pw = settings.passwords();
        return new AnalyzerRegistry()
                .register(new PstAnalyzer())
                .register(new MsgAnalyzer())
                .register(new MboxAnalyzer())
                .register(new EmlAnalyzer())
                .register(new ArchiveAnalyzer(pw))
                .register(new PdfAnalyzer(pw))
                .register(new OfficeAnalyzer())
                .register(new ImageAnalyzer())
                .register(new TextAnalyzer())
                .loadServiceProviders();
    }

    public void pause() { if (paused.compareAndSet(false, true)) listener.accept(new EngineEvent.Paused()); }
    public void resume() { if (paused.compareAndSet(true, false)) listener.accept(new EngineEvent.Resumed()); }
    public void cancel() { cancelled.set(true); }
    public boolean cancelled() { return cancelled.get(); }

    public long processed() { return processed.get(); }
    public long errorCount() { return errors.get(); }
    public long lockedCount() { return locked.get(); }
    public long unsupportedCount() { return unsupported.get(); }
    public long duplicateCount() { return duplicates.get(); }
    public long skippedCount() { return skipped.get(); }

    // =====================================================================
    // Intake
    // =====================================================================

    /**
     * Walks a folder or single file read-only (F-06) and runs every discovered
     * file through the pipeline. Already-completed queue rows are skipped, which
     * is what makes a restart resume rather than redo (F-07 / AT-05).
     */
    public Result ingest(Path source, String custodian) throws Exception {
        long t0 = System.currentTimeMillis();

        // Never reuse an id a previous run already issued.
        try { idSeq = Math.max(idSeq, db.maxElementSequence()); }
        catch (Exception ignored) { }

        List<Path> roots = Files.isDirectory(source)
                ? walk(source)
                : List.of(source);

        listener.accept(new EngineEvent.Started(folder.name(), roots.size()));
        listener.accept(new EngineEvent.Log("INFO", "Intake: " + roots.size()
                + " top-level files from " + source + " (custodian=" + custodian + ")"));

        for (Path p : roots) {
            if (cancelled.get()) break;
            awaitResume();

            try {
                // AT-05 resume: identity is the source path, not the sequence number.
                String already = db.completedIdForSource(p.toString());
                if (already != null) {
                    skipped.incrementAndGet();
                    continue;
                }
            } catch (Exception ignored) { }

            String itemId = nextId();
            try {
                db.enqueue(itemId, p.toString(), null, 0);
                db.setQueueState(itemId, "PROCESSING");

                Item it = fromFile(itemId, p, custodian);
                runElement(it, p, null);
                db.setQueueState(itemId, queueStateOf(it.status()));
            } catch (Exception e) {
                errors.incrementAndGet();
                listener.accept(new EngineEvent.Failed(itemId, p.getFileName().toString(),
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                try { db.setQueueState(itemId, "ERROR"); } catch (Exception ignored) { }
            }
            emitProgress(roots.size(), t0);
        }

        index.commit();
        runOcrSweep();
        index.commit();

        long ms = System.currentTimeMillis() - t0;
        listener.accept(new EngineEvent.Finished(
                processed.get() - errors.get() - locked.get() - unsupported.get(),
                errors.get(), locked.get(), unsupported.get(), ms));

        if (skipped.get() > 0) {
            listener.accept(new EngineEvent.Log("INFO", "Resume: skipped "
                    + skipped.get() + " element(s) completed by a previous run"));
        }
        return new Result(processed.get(), errors.get(), locked.get(),
                unsupported.get(), duplicates.get(), ms);
    }

    // =====================================================================
    // M3-A. Asynchronous OCR sweep
    // =====================================================================

    /**
     * OCRs every deferred element on a bounded worker pool, re-indexing each one
     * as its text arrives. Runs after intake so it never blocks ingestion; the
     * index is already searchable before a single page is recognised.
     *
     * <p>Failures are per-element and never abort the sweep (N-05).
     */
    private void runOcrSweep() {
        if (ocrStage == null || ocrQueue.isEmpty()) return;

        int total = ocrQueue.size();
        listener.accept(new EngineEvent.Log("INFO", "OCR: " + total
                + " element(s) queued · languages=" + String.join("+", settings.ocrLanguages())));

        int threads = Math.max(1, Math.min(settings.workers(), total));
        ocrPool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "aegis-ocr");
            t.setDaemon(true);
            return t;
        });

        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        List<java.util.Map.Entry<String, byte[]>> work;
        synchronized (ocrQueue) { work = new java.util.ArrayList<>(ocrQueue.entrySet()); }

        java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger();

        for (var entry : work) {
            futures.add(ocrPool.submit(() -> {
                if (cancelled.get()) return;
                awaitResume();
                String id = entry.getKey();
                try {
                    Item it = index.byId(id);
                    if (it == null) return;
                    boolean got = ocrStage.apply(it, entry.getValue());
                    // Re-store so the new text is searchable and rebuildable (F-14).
                    synchronized (this) {
                        folder.writeText(it.id(), it.extractedText());
                        index.put(it);
                        db.save(it);
                    }
                    if (got) {
                        listener.accept(new EngineEvent.Log("INFO",
                                "OCR: " + it.name() + " — recognised "
                                        + it.extractedText().length() + " chars"));
                    }
                } catch (Throwable t) {
                    listener.accept(new EngineEvent.Log("ERROR",
                            "OCR failed for " + id + ": " + t.getMessage()));
                } finally {
                    int n = done.incrementAndGet();
                    if (n % 5 == 0 || n == total) {
                        listener.accept(new EngineEvent.Log("INFO",
                                "OCR progress " + n + "/" + total));
                    }
                }
            }));
        }

        for (var f : futures) {
            try { f.get(); } catch (Exception ignored) { }
        }
        ocrPool.shutdown();
        ocrQueue.clear();

        var sum = ocrStage.summary();
        listener.accept(new EngineEvent.Log("INFO", String.format(
                "OCR complete: %d processed, %d with text, %d failed, %,d characters added",
                sum.processed(), sum.recognised(), sum.failed(), sum.charactersAdded())));
    }

    private static List<Path> walk(Path root) throws IOException {
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private Item fromFile(String id, Path p, String custodian) throws IOException {
        Item it = new Item(id, p.getFileName().toString());
        it.sourcePath(p.toAbsolutePath().toString());
        it.custodian(custodian);
        it.size(Files.size(p));
        try {
            var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
            it.created(attrs.creationTime().toInstant().truncatedTo(ChronoUnit.MILLIS));
            it.modified(attrs.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MILLIS));
            // F-06 baseline, captured before any analyzer can overwrite modified().
            it.fsModified(attrs.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MILLIS));
            it.accessed(attrs.lastAccessTime().toInstant().truncatedTo(ChronoUnit.MILLIS));
        } catch (Exception ignored) { }
        return it;
    }

    // =====================================================================
    // Per-element stage sequence
    // =====================================================================

    /**
     * Runs one element through Extract → Hash → Dedupe → Index → Store, then
     * drains any children it produced. Children are processed depth-first so a
     * container's members land immediately after it.
     */
    private void runElement(Item root, Path file, byte[] inMemory) {
        Deque<Pending> stack = new ArrayDeque<>();
        stack.push(new Pending(root, file, inMemory));

        while (!stack.isEmpty()) {
            if (cancelled.get()) return;
            awaitResume();

            Pending cur = stack.pop();
            Item it = cur.item;
            // A status set by the parent analyzer (LOCKED on an encrypted member) is
            // terminal and must survive this element's own stage run.
            ItemStatus preset = it.status();
            if (!terminal(preset)) it.status(ItemStatus.PROCESSING);

            // ---- Stage 2: Analyse / Extract ---------------------------------
            byte[] payload = cur.bytes;
            try {
                if (payload == null && cur.path != null && Files.size(cur.path) <= STREAM_THRESHOLD) {
                    payload = Files.readAllBytes(cur.path);
                }
            } catch (Exception e) {
                it.errors().add("Read failed: " + e.getMessage());
                it.status(ItemStatus.ERROR);
            }

            List<Pending> children = new java.util.ArrayList<>();
            if (it.status() != ItemStatus.ERROR) {
                extract(it, payload, cur.path, children);
            }

            // ---- Stage 3: Hash / Dedupe -------------------------------------
            hash(it, payload, cur.path);
            dedupe(it);

            // ---- Stage 4 + 5: Index and Store -------------------------------
            store(it, payload);

            // M3-A: queue OCR work instead of doing it inline. Rendering and
            // recognising a scanned page costs seconds; doing it here would stall
            // intake and the UI. The sweep runs after the main pass.
            if (ocrStage != null && payload != null
                    && com.aegis.fdx.ocr.OcrStage.shouldOcr(it, payload)) {
                it.needsOcr(true);
                ocrQueue.put(it.id(), payload);
            }

            tally(it);
            processed.incrementAndGet();
            listener.accept(new EngineEvent.ItemIndexed(it, processed.get(), 0));

            // children last-in-first-out keeps ordering natural
            for (int i = children.size() - 1; i >= 0; i--) stack.push(children.get(i));
        }
    }

    /** Stage 2. Selects an analyzer by magic number and runs it. */
    private void extract(Item it, byte[] payload, Path path, List<Pending> children) {
        if (it.depth() > settings.maxArchiveDepth()) {
            it.addMetadata("Depth-Limit", "stopped at depth " + it.depth());
            it.status(ItemStatus.INDEXED);
            return;
        }
        // Elements already fully parsed by their parent (e.g. PST messages), and
        // placeholders the parent already marked LOCKED/ERROR: nothing to re-run.
        if (payload == null && path == null) {
            if (!terminal(it.status())) it.status(ItemStatus.INDEXED);
            return;
        }

        try (InputStream raw = payload != null
                ? new ByteArrayInputStream(payload)
                : Files.newInputStream(path);
             BufferedInputStream in = new BufferedInputStream(raw, 1 << 16)) {

            byte[] header = registry.peek(in);
            Analyzer a = registry.select(header, it.name());

            if (a == null) {
                // F-02: never ignored — stored, hashed, marked Unsupported.
                it.status(ItemStatus.UNSUPPORTED);
                it.addMetadata("Unsupported", "no analyzer claimed this format");
                if (it.mediaType() == null) it.mediaType("application/octet-stream");
                return;
            }

            it.addMetadata("Analyzer", a.id());
            Analyzer.ChildSink sink = (child, content) -> {
                byte[] data = null;
                if (content != null) {
                    try { data = content.readAllBytes(); } catch (IOException ignored) { }
                }
                children.add(new Pending(child, null, data));
            };

            a.analyze(it, in, sink);
            if (it.status() == ItemStatus.PROCESSING) it.status(ItemStatus.INDEXED);

        } catch (Exception e) {
            // N-05: recorded, never propagated.
            it.status(ItemStatus.ERROR);
            it.errors().add(e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "malformed input" : e.getMessage()));
        }
    }

    /** Stage 3a. F-05: MD5 + SHA-256 over the element's actual bytes. */
    private void hash(Item it, byte[] payload, Path path) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");

            if (payload != null) {
                md5.update(payload);
                sha.update(payload);
            } else if (path != null) {
                // Streamed for oversized files (N-04).
                try (InputStream in = Files.newInputStream(path);
                     DigestInputStream d1 = new DigestInputStream(in, md5);
                     DigestInputStream d2 = new DigestInputStream(d1, sha)) {
                    byte[] buf = new byte[1 << 16];
                    while (d2.read(buf) > 0) { /* digesting */ }
                }
            } else {
                // Parsed-only element (PST message): hash its canonical text.
                byte[] b = (it.id() + '\u0000' + it.extractedText())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                md5.update(b);
                sha.update(b);
            }
            it.md5(HexFormat.of().formatHex(md5.digest()));
            it.sha256(HexFormat.of().formatHex(sha.digest()));
        } catch (Exception e) {
            it.errors().add("Hashing failed: " + e.getMessage());
        }
    }

    /** Stage 3b. F-05: duplicates are marked in all locations, never deleted. */
    private void dedupe(Item it) {
        try {
            String existing = db.findDuplicate(it.sha256(), it.custodian(),
                    settings.dedupeScope().name());
            if (existing != null && !existing.equals(it.id())) {
                it.duplicate(true);
                it.duplicateOf(existing);
                it.addMetadata("Duplicate-Of", existing);
                duplicates.incrementAndGet();
            }
        } catch (Exception e) {
            it.errors().add("Dedupe check failed: " + e.getMessage());
        }
    }

    /** Stages 4 and 5. Text to /text, row to SQLite, document to Lucene. */
    private void store(Item it, byte[] payload) {
        try {
            folder.writeText(it.id(), it.extractedText());     // F-14 rebuild source
        } catch (Exception e) {
            it.errors().add("Text store failed: " + e.getMessage());
        }
        try {
            index.put(it);                                     // F-13 NRT searchable
        } catch (Exception e) {
            it.errors().add("Index failed: " + e.getMessage());
            it.status(ItemStatus.ERROR);
        }
        try {
            db.save(it);                                       // A-04 durable
        } catch (Exception e) {
            listener.accept(new EngineEvent.Log("ERROR",
                    "DB write failed for " + it.id() + ": " + e.getMessage()));
        }
    }

    // =====================================================================

    private void tally(Item it) {
        switch (it.status()) {
            case ERROR -> {
                errors.incrementAndGet();
                listener.accept(new EngineEvent.Failed(it.id(), it.name(),
                        it.errors().isEmpty() ? "unknown" : it.errors().get(0)));
            }
            case LOCKED -> {
                locked.incrementAndGet();
                listener.accept(new EngineEvent.Log("WARN",
                        "Locked: " + it.name() + " — no supplied password matched, continuing"));
            }
            case UNSUPPORTED -> unsupported.incrementAndGet();
            default -> { }
        }
    }

    /** LOCKED / ERROR / UNSUPPORTED are decisions, not transient states. */
    private static boolean terminal(ItemStatus s) {
        return s == ItemStatus.LOCKED || s == ItemStatus.ERROR || s == ItemStatus.UNSUPPORTED;
    }

    private static String queueStateOf(ItemStatus s) {
        return switch (s) {
            case ERROR -> "ERROR";
            case LOCKED -> "LOCKED";
            case UNSUPPORTED -> "UNSUPPORTED";
            default -> "DONE";
        };
    }

    private void emitProgress(long total, long t0) {
        long done = processed.get();
        if (done % 5 != 0) return;
        double secs = Math.max(1, System.currentTimeMillis() - t0) / 1000.0;
        Runtime rt = Runtime.getRuntime();
        listener.accept(new EngineEvent.Progress(done, total, done / secs,
                0, (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024), settings.workers()));
    }

    private void awaitResume() {
        while (paused.get() && !cancelled.get()) {
            try { Thread.sleep(50); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private synchronized String nextId() {
        return String.format("E-%06d", ++idSeq);
    }

    public void seedIdSequence(long from) { this.idSeq = from; }

    private record Pending(Item item, Path path, byte[] bytes) { }

    public record Result(long processed, long errors, long locked, long unsupported,
                         long duplicates, long millis) { }
}
