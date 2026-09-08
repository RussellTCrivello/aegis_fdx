package com.aegis.fdx.engine;

import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.index.LuceneQueryBuilder;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.Term;
import org.apache.lucene.document.LongPoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * The UI's single entry point to a real case: opens the case folder, owns the
 * Lucene index and SQLite database, and runs search and ingest off the FX thread.
 *
 * <p>3.1 / N-03: every method that can block returns a {@link Future} or takes a
 * callback. Nothing here touches JavaFX; the caller marshals results onto the FX
 * thread. The UI therefore cannot be blocked by extraction, OCR, indexing or
 * export, no matter how large the case.
 */
public final class LiveCase implements AutoCloseable {

    private final CaseFolder folder;
    private final CaseDatabase db;
    private final LuceneIndex index;
    private final CaseSettings settings;
    private final ExecutorService searchPool;
    private final ExecutorService ingestPool;

    private volatile IngestPipeline running;
    private volatile IngestPipeline lastPipeline;
    private volatile String indexRepair;

    public LiveCase(Path caseRoot, String name, CaseSettings settings) throws Exception {
        this.settings = settings;
        this.folder = CaseFolder.createOrOpen(caseRoot, name);
        try {
            this.db = new CaseDatabase(folder.database());
        } catch (Exception e) {
            // The one file a case cannot do without. Say which file and why, rather
            // than letting a driver-level message reach the examiner.
            throw new IllegalStateException("This case's database cannot be read: "
                    + folder.database() + " — " + e.getMessage()
                    + ". Restore it from a backup, or open a different case.", e);
        }
        this.index = openOrRebuildIndex();
        this.searchPool = Executors.newSingleThreadExecutor(daemon("aegis-search"));
        this.ingestPool = Executors.newSingleThreadExecutor(daemon("aegis-ingest"));
    }

    /**
     * Opens the search index, rebuilding it from the database if it cannot be opened.
     *
     * <p>The index is derived data: everything in it also exists in {@code case.db} and
     * the text store. So a corrupt or half-written index — an interrupted copy, a full
     * disk, a killed process — must never be the reason a case cannot be opened. The
     * damaged directory is kept, renamed, so that nothing is destroyed on the way to
     * recovering, and a fresh index is built from the record of authority.
     */
    private LuceneIndex openOrRebuildIndex() throws Exception {
        try {
            return new LuceneIndex(folder.index(), settings.indexMemoryMb());
        } catch (Exception failure) {
            // A lock conflict is not damage: the case is open somewhere else, and the
            // one thing that must not happen is for this attempt to touch that case's
            // index. Refuse, and say what is actually wrong.
            if (isLockConflict(failure)) {
                throw new IllegalStateException("This case is already open — "
                        + folder.root() + " is in use by another window or process. "
                        + "Close it there, then open it here.", failure);
            }

            // Genuine damage: an interrupted copy, a full disk, a killed process. The
            // index is derived data, so it can be rebuilt; the damaged copy is kept
            // rather than deleted, because a forensic tool does not destroy evidence of
            // what went wrong.
            Path damaged = folder.logs().resolve(
                    "index-damaged-" + java.time.Instant.now().toEpochMilli());
            boolean moved = false;
            try {
                Files.createDirectories(folder.logs());
                Files.move(folder.index(), damaged);
                moved = true;
            } catch (Exception cannotMove) {
                deleteTree(folder.index());
            }

            LuceneIndex fresh;
            try {
                fresh = new LuceneIndex(folder.index(), settings.indexMemoryMb());
            } catch (Exception cannotCreate) {
                // Put the case back exactly as it was rather than leaving it worse.
                if (moved) {
                    try {
                        deleteTree(folder.index());
                        Files.move(damaged, folder.index());
                    } catch (Exception ignored) {
                        // Nothing further can be done here; the original failure is
                        // the one worth reporting.
                    }
                }
                throw cannotCreate;
            }

            int rebuilt = rebuildInto(fresh);
            indexRepair = "The search index could not be opened ("
                    + failure.getClass().getSimpleName()
                    + ") and was rebuilt from the case database: " + rebuilt + " element(s)."
                    + (moved ? " The damaged index was kept as logs/" + damaged.getFileName() + "." : "");
            return fresh;
        }
    }

    private static boolean isLockConflict(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof org.apache.lucene.store.LockObtainFailedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds the search index from the case database and the text store.
     *
     * <p>Available as an operation in its own right — the Setup destination offers it —
     * because an index can also be merely stale or incomplete after an interrupted run,
     * and rebuilding is cheap next to processing the evidence again.
     *
     * @return how many elements were indexed
     */
    public int rebuildIndex() throws Exception {
        int n = rebuildInto(index);
        indexRepair = "Search index rebuilt from the case database: " + n + " element(s).";
        return n;
    }

    private int rebuildInto(LuceneIndex target) throws Exception {
        List<Item> items = db.allItems(id -> {
            try {
                return folder.readText(id);
            } catch (Exception e) {
                return "";
            }
        });
        for (Item it : items) {
            target.put(it);
        }
        target.commit();
        return items.size();
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // Best effort: a fresh index is created either way.
        }
    }

    /**
     * What had to be repaired when this case was opened, or null when nothing did.
     *
     * <p>The interface shows this: silent recovery is how a case quietly loses work
     * without anyone noticing.
     */
    public String indexRepair() {
        return indexRepair;
    }

    public CaseFolder folder() { return folder; }
    public CaseDatabase db() { return db; }
    public LuceneIndex index() { return index; }
    public CaseSettings settings() { return settings; }
    public String name() { return folder.name(); }

    // ---- ingest --------------------------------------------------------------

    public Future<IngestPipeline.Result> startIngest(Path source, String custodian,
                                                     Consumer<EngineEvent> listener) {
        return ingestPool.submit(() -> {
            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, listener);
            pipe.seedIdSequence(db.count());
            running = pipe;
            lastPipeline = pipe;
            try {
                return pipe.ingest(source, custodian);
            } finally {
                running = null;
            }
        });
    }

    public void pauseIngest() { IngestPipeline p = running; if (p != null) p.pause(); }    public void resumeIngest() { IngestPipeline p = running; if (p != null) p.resume(); }
    public void cancelIngest() { IngestPipeline p = running; if (p != null) p.cancel(); }
    public boolean ingestRunning() { return running != null; }

    /**
     * Runs one element through the pipeline again, off the interface thread.
     *
     * <p>Used by the error views: a file that failed once — locked, unreadable for a
     * moment, on a share that dropped — can be tried again without re-running the case.
     * The element keeps its identity, so nothing that refers to it is orphaned.
     *
     * @param itemId   the element to try again
     * @param listener receives the same engine events an ingest run produces
     */
    public Future<Item> retryElement(String itemId, Consumer<EngineEvent> listener) {
        Consumer<EngineEvent> sink = listener == null ? e -> { } : listener;
        return ingestPool.submit(() -> {
            Item existing = byId(itemId);
            if (existing == null) {
                throw new IllegalArgumentException("no element " + itemId + " in this case");
            }
            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, sink);
            pipe.seedIdSequence(db.count());
            return pipe.retry(existing);
        });
    }

    // ---- search --------------------------------------------------------------

    /** Off-thread search; the callback receives the page on the search thread. */
    public Future<SearchPage> search(String query, Filters filters, boolean includeHidden,
                                     String sortMode, int limit) {
        return searchPool.submit(() -> searchNow(query, filters, includeHidden, sortMode, limit));
    }

    public SearchPage searchNow(String query, Filters filters, boolean includeHidden,
                                String sortMode, int limit) throws Exception {
        long t0 = System.nanoTime();

        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(LuceneQueryBuilder.build(query, index.analyzer()), BooleanClause.Occur.MUST);
        applyFilters(b, filters);
        if (!includeHidden) {
            b.add(new TermQuery(new Term(LuceneIndex.F_TAG, "Hidden")), BooleanClause.Occur.MUST_NOT);
        }
        Query q = b.build();

        IndexSearcher searcher = index.searcher();
        Sort sort = sortOf(sortMode);
        TopDocs td = sort == null ? searcher.search(q, limit) : searcher.search(q, limit, sort);

        List<SearchHit> hits = new ArrayList<>(td.scoreDocs.length);
        String needle = CaseStore.firstKeyword(query);
        for (ScoreDoc sd : td.scoreDocs) {
            Document d = searcher.storedFields().document(sd.doc);
            Item it = LuceneIndex.fromDocument(d);
            String text = it.extractedText();
            int count = needle.isEmpty() ? 1 : countOccurrences(text.toLowerCase(), needle);
            hits.add(new SearchHit(it, sd.score, Math.max(count, 1), fragments(text, needle)));
        }
        long micros = (System.nanoTime() - t0) / 1000;
        return new SearchPage(hits, td.totalHits.value, micros / 1000.0);
    }

    private void applyFilters(BooleanQuery.Builder b, Filters f) {
        if (f == null || f.isEmpty()) return;

        if (!f.types.isEmpty()) {
            BooleanQuery.Builder sub = new BooleanQuery.Builder();
            for (String t : f.types) sub.add(new TermQuery(new Term(LuceneIndex.F_EXT, t)),
                    BooleanClause.Occur.SHOULD);
            b.add(sub.build(), BooleanClause.Occur.MUST);
        }
        if (!f.custodians.isEmpty()) {
            BooleanQuery.Builder sub = new BooleanQuery.Builder();
            for (String c : f.custodians) sub.add(new TermQuery(new Term(LuceneIndex.F_CUSTODIAN, c)),
                    BooleanClause.Occur.SHOULD);
            b.add(sub.build(), BooleanClause.Occur.MUST);
        }
        if (!f.tags.isEmpty()) {
            BooleanQuery.Builder sub = new BooleanQuery.Builder();
            for (String t : f.tags) sub.add(new TermQuery(new Term(LuceneIndex.F_TAG, t)),
                    BooleanClause.Occur.SHOULD);
            b.add(sub.build(), BooleanClause.Occur.MUST);
        }
        if (!f.statuses.isEmpty()) {
            BooleanQuery.Builder sub = new BooleanQuery.Builder();
            for (ItemStatus s : f.statuses) sub.add(
                    new TermQuery(new Term(LuceneIndex.F_STATUS, s.label())), BooleanClause.Occur.SHOULD);
            b.add(sub.build(), BooleanClause.Occur.MUST);
        }
        if (f.hasAttachments != null) {
            b.add(new TermQuery(new Term(LuceneIndex.F_HAS_ATT, f.hasAttachments.toString())),
                    BooleanClause.Occur.MUST);
        }
        if (f.dateFrom != null || f.dateTo != null) {
            long lo = f.dateFrom == null ? Long.MIN_VALUE : f.dateFrom.toEpochMilli();
            long hi = f.dateTo == null ? Long.MAX_VALUE : f.dateTo.toEpochMilli();
            b.add(LongPoint.newRangeQuery(LuceneIndex.F_MODIFIED, lo, hi), BooleanClause.Occur.MUST);
        }
    }

    private static Sort sortOf(String mode) {
        if (mode == null) return null;
        return switch (mode) {
            case "Date ↓" -> new Sort(new SortField("sort_modified", SortField.Type.LONG, true));
            case "Date ↑" -> new Sort(new SortField("sort_modified", SortField.Type.LONG, false));
            case "Name" -> new Sort(new SortField("sort_name", SortField.Type.STRING, false));
            case "Size ↓" -> new Sort(new SortField("sort_size", SortField.Type.LONG, true));
            default -> null;   // relevance
        };
    }

    // ---- review actions (F-19 … F-24) ---------------------------------------

    public void applyTag(List<Item> items, String tag, String user) throws Exception {
        List<String> ids = new ArrayList<>();
        for (Item it : items) {
            if (!it.tags().remove(tag)) it.tags().add(tag);
            db.setTags(it.id(), it.tags());
            index.put(it);
            ids.add(it.id());
        }
        index.commit();
        db.audit(user, "TAG", tag + " × " + items.size(), String.join(",", ids));
    }

    public void setNotes(Item it, String notes, String user) throws Exception {
        it.notes(notes);
        db.setNotes(it.id(), notes);
        index.put(it);
        index.commit();
        db.audit(user, "NOTE", "note updated", it.id());
    }

    public void auditSearch(String query, long hits, String user) {
        try { db.audit(user, "SEARCH", query + " → " + hits + " hits", null); }
        catch (Exception ignored) { }
    }

    // ---- reports (F-23, F-27) ------------------------------------------------

    public Map<ItemStatus, Integer> statusCounts() throws Exception { return db.statusCounts(); }

    public Map<String, List<String>> duplicateClusters() throws Exception {
        return db.duplicateClusters();
    }

    /** F-23: thread grouping by normalised subject. */
    public Map<String, List<Item>> emailThreads(int limit) throws Exception {
        Query q = new BooleanQuery.Builder()
                .add(new org.apache.lucene.search.FieldExistsQuery(LuceneIndex.F_SUBJECT),
                        BooleanClause.Occur.MUST)
                .build();
        Map<String, List<Item>> threads = new LinkedHashMap<>();
        for (Document d : index.search(q, limit, null)) {
            Item it = LuceneIndex.fromDocument(d);
            if (it.subject() == null) continue;
            String key = it.subject().replaceAll("(?i)^\\s*(re|fw|fwd)\\s*:\\s*", "").trim();
            threads.computeIfAbsent(key, k -> new ArrayList<>()).add(it);
        }
        threads.values().forEach(l -> l.sort(Comparator.comparing(
                Item::sentDate, Comparator.nullsLast(Comparator.naturalOrder()))));
        return threads;
    }

    public Item byId(String id) throws Exception {
        List<Document> d = index.search(new TermQuery(new Term(LuceneIndex.F_ID, id)), 1, null);
        return d.isEmpty() ? null : LuceneIndex.fromDocument(d.get(0));
    }

    public int indexedCount() throws Exception { return index.count(); }

    /** M3. Every element in the case, for exports, reports and verification. */
    public java.util.List<Item> allItems() throws Exception {
        java.util.List<Item> out = new java.util.ArrayList<>();
        for (var d : index.search(
                new org.apache.lucene.search.MatchAllDocsQuery(), 1_000_000, null)) {
            out.add(LuceneIndex.fromDocument(d));
        }
        out.sort(java.util.Comparator.comparing(Item::id));
        return out;
    }

    /** M3-B. Runs an export off the FX thread. */
    public Future<com.aegis.fdx.export.Exporter.Result> export(
            com.aegis.fdx.export.ExportRequest req, java.util.List<Item> items,
            java.util.function.Consumer<String> log) {
        return ingestPool.submit(() ->
                new com.aegis.fdx.export.Exporter(folder, db, log).run(req, items));
    }

    /** M3-B. Report generator bound to this case. */
    public com.aegis.fdx.export.Reports reports() {
        return new com.aegis.fdx.export.Reports(folder, db);
    }

    /** M3-D. Verifies every original against its ingest hash, off the FX thread. */
    public Future<IntegrityVerifier.Report> verifyIntegrity(
            java.util.function.Consumer<String> log) {
        return ingestPool.submit(() ->
                new IntegrityVerifier(folder, db, log).verifySources(allItems()));
    }

    /** M3-A. OCR summary from the most recent ingest, or null. */
    public com.aegis.fdx.ocr.OcrStage.Summary ocrSummary() {
        IngestPipeline p = lastPipeline;
        return p == null || p.ocrStage() == null ? null : p.ocrStage().summary();
    }

    // ---- helpers -------------------------------------------------------------

    private static int countOccurrences(String hay, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static List<String> fragments(String text, String needle) {
        if (text == null || text.isEmpty()) return List.of();
        if (needle.isEmpty()) {
            return List.of(text.length() > 160 ? text.substring(0, 160) + "…" : text);
        }
        List<String> out = new ArrayList<>();
        String lower = text.toLowerCase();
        int i = 0;
        while ((i = lower.indexOf(needle, i)) >= 0 && out.size() < 5) {
            int from = Math.max(0, i - 60);
            int to = Math.min(text.length(), i + needle.length() + 60);
            out.add((from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : ""));
            i += needle.length();
        }
        return out.isEmpty()
                ? List.of(text.length() > 160 ? text.substring(0, 160) + "…" : text)
                : out;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void close() {
        searchPool.shutdownNow();
        ingestPool.shutdownNow();
        try { index.close(); } catch (Exception ignored) { }
        db.close();
    }

    public record SearchPage(List<SearchHit> hits, long totalHits, double millis) { }
}
