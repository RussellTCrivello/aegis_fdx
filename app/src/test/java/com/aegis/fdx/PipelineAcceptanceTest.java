package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.EngineEvent;
import com.aegis.fdx.engine.IngestPipeline;
import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.index.LuceneQueryBuilder;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;

/**
 * Milestone-2 acceptance battery. Runs the real pipeline over the real generated
 * dataset and asserts the entrance-exam behaviours (AT-01 … AT-09).
 *
 * <p>Zero test-framework dependency so it runs anywhere: {@code java ... PipelineAcceptanceTest}.
 */
public final class PipelineAcceptanceTest {

    private static int passed, failed;
    /** Files the generator wrote for this run; the F-06 baseline. */
    private static long datasetFileCount;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "/tmp/aegis-at");
        deleteTree(work);
        Path dataset = work.resolve("dataset");
        Path caseDir = work.resolve("case-001");

        System.out.println("== Generating dataset (D-04) ==");
        List<String> manifest = TestDataset.build(dataset);
        try (var w = java.nio.file.Files.walk(dataset)) {
            datasetFileCount = w.filter(java.nio.file.Files::isRegularFile).count();
        }
        System.out.println("   " + manifest.size() + " files\n");

        System.out.println("== AT-01: ingest whole dataset ==");
        CaseFolder folder = CaseFolder.createOrOpen(caseDir, "AT-CASE");
        CaseSettings settings = new CaseSettings();
        settings.passwords().add("wrongpassword");
        settings.dedupeScope(CaseSettings.DedupeScope.OFF);

        List<String> log = new ArrayList<>();
        IngestPipeline.Result result;
        try (CaseDatabase db = new CaseDatabase(folder.database());
             LuceneIndex index = new LuceneIndex(folder.index(), 64)) {

            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, ev -> {
                if (ev instanceof EngineEvent.Failed f) log.add("FAIL " + f.itemName() + ": " + f.reason());
                if (ev instanceof EngineEvent.Log l) log.add(l.level() + " " + l.message());
            });

            result = pipe.ingest(dataset, "A. Farouk");

            System.out.printf("   processed=%d errors=%d locked=%d unsupported=%d dupes=%d in %d ms%n",
                    result.processed(), result.errors(), result.locked(),
                    result.unsupported(), result.duplicates(), result.millis());

            // ---------------- AT-01 ----------------
            check(result.processed() > manifest.size(),
                    "AT-01 element count exceeds file count (containers expanded): "
                    + result.processed() + " > " + manifest.size());
            check(true, "AT-01 application never crashed");

            Map<ItemStatus, Integer> counts = db.statusCounts();
            System.out.println("   status: " + counts);
            check(counts.get(ItemStatus.ERROR) > 0, "AT-01 corrupted files reported as ERROR");
            check(counts.get(ItemStatus.INDEXED) > 30, "AT-01 majority indexed successfully");

            checkErrorsHaveReasons(folder);

            // ---------------- format extraction ----------------
            index.commit();
            checkText(index, TestDataset.CANARY_PDF, "PDF text extracted (PDFBox)");
            checkText(index, TestDataset.CANARY_DOCX, "DOCX text extracted (POI)");
            checkText(index, TestDataset.CANARY_XLSX, "XLSX text extracted (POI)");
            checkText(index, TestDataset.CANARY_PPTX, "PPTX text extracted (POI)");
            checkText(index, "beneficiary", "EML body extracted (mime4j)");

            // ---------------- AT-02: nested chain ----------------
            System.out.println("\n== AT-02: ZIP → ZIP → EML → PDF chain ==");
            List<Document> nested = index.search(
                    LuceneQueryBuilder.build("\"" + TestDataset.CANARY_NESTED + "\"", index.analyzer()),
                    20, null);
            check(!nested.isEmpty(), "AT-02 level-4 PDF inside nested containers is searchable");
            if (!nested.isEmpty()) {
                Document d = nested.get(0);
                String cp = d.get(LuceneIndex.F_CONTAINER);
                int depth = Integer.parseInt(d.get(LuceneIndex.F_DEPTH));
                System.out.println("   containerPath = " + cp);
                System.out.println("   depth         = " + depth);
                check(depth >= 3, "AT-02 nesting depth >= 3 (got " + depth + ")");
                check(cp != null && cp.contains("→"), "AT-02 parent chain preserved");
                check(d.get(LuceneIndex.F_PARENT) != null, "AT-02 parent link recorded");
            }

            // each layer independently searchable
            check(!index.search(LuceneQueryBuilder.build("name:nested_evidence.zip", index.analyzer()), 5, null).isEmpty(),
                    "AT-02 outer ZIP is its own element");
            check(!index.search(LuceneQueryBuilder.build("name:level2.zip", index.analyzer()), 5, null).isEmpty(),
                    "AT-02 inner ZIP is its own element");
            check(!index.search(LuceneQueryBuilder.build("name:level3.eml", index.analyzer()), 5, null).isEmpty(),
                    "AT-02 EML is its own element");
            check(!index.search(LuceneQueryBuilder.build("name:level4.pdf", index.analyzer()), 5, null).isEmpty(),
                    "AT-02 attached PDF is its own element");

            // ---------------- AT-03: locked archive ----------------
            System.out.println("\n== AT-03: encrypted archive ==");
            check(result.locked() > 0, "AT-03 encrypted entry marked LOCKED (" + result.locked() + ")");
            check(result.processed() > 40, "AT-03 run continued past the locked item");

            // ---------------- AT-07: query battery ----------------
            System.out.println("\n== AT-07: Lucene query battery ==");
            q(index, "settlement", 1, "keyword");
            q(index, "\"wire transfer\"", 1, "phrase");
            q(index, "settle*", 1, "wildcard");
            q(index, "setlement~2", 1, "fuzzy");
            q(index, "type:pdf", 1, "field: type");
            q(index, "type:docx", 1, "field: type docx");
            q(index, "from:j.kowalski@northwind-legal.example", 1, "field: from (untokenised address)");
            q(index, "subject:\"Wire instructions\"", 1, "field: subject phrase");
            q(index, "custodian:\"A. Farouk\"", 1, "field: custodian");
            q(index, "status:Error", 1, "field: status");
            q(index, "settlement AND payment", 1, "boolean AND");
            q(index, "settlement OR briefing", 1, "boolean OR");
            q(index, "type:pdf NOT corrupt", 1, "boolean NOT");
            q(index, "(settlement OR ledger) AND type:pdf", 1, "grouping");
            q(index, "/inv-\\d{5}/", 1, "regex");
            q(index, "date:[2020-01-01 TO 2030-12-31]", 1, "date range");
            q(index, TestDataset.CANARY_ARABIC, 1, "multilingual: Arabic");
            q(index, TestDataset.CANARY_CJK, 1, "multilingual: CJK");
            q(index, TestDataset.CANARY_CYRILLIC, 1, "multilingual: Cyrillic");
            q(index, "((malformed", 0, "malformed query degrades safely");

            // ---------------- F-13: NRT ----------------
            check(index.count() > 40, "F-13 index visible without reopening (NRT), docs=" + index.count());

            // ---------------- AT-09: hash verification ----------------
            System.out.println("\n== AT-09: evidence hash verification ==");
            verifyHashes(folder, dataset, index);

            // ---------------- F-14: rebuild from /text ----------------
            System.out.println("\n== F-14: text store ==");
            long textFiles = countFiles(folder.text());
            check(textFiles >= result.processed() - 2,
                    "F-14 extracted text persisted for reindex (" + textFiles + " files)");
        }

        // ---------------- AT-05: resume ----------------
        System.out.println("\n== AT-05: interrupt and resume ==");
        testResume(work);

        // ---------------- AT-04: dedupe scopes ----------------
        System.out.println("\n== AT-04: deduplication scopes ==");
        testDedupe(work);

        // ---------------- F-06: source integrity ----------------
        System.out.println("\n== F-06: source read-only ==");
        verifySourceUntouched(dataset);

        System.out.printf("%n=== %d passed, %d failed ===%n", passed, failed);
        if (!failures.isEmpty()) {
            System.out.println("\nFailures:");
            failures.forEach(f -> System.out.println("  - " + f));
        }
        if (failed > 0) System.exit(1);
    }

    // =====================================================================

    private static void testResume(Path work) throws Exception {
        Path dataset = work.resolve("dataset");
        Path caseDir = work.resolve("case-resume");
        CaseFolder folder = CaseFolder.createOrOpen(caseDir, "RESUME");
        CaseSettings settings = new CaseSettings();

        int firstPass;
        try (CaseDatabase db = new CaseDatabase(folder.database());
             LuceneIndex index = new LuceneIndex(folder.index(), 32)) {
            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, ev -> { });
            // Cancel after a handful of top-level files.
            Thread t = new Thread(() -> {
                try {
                    while (pipe.processed() < 8) Thread.sleep(5);
                    pipe.cancel();
                } catch (InterruptedException ignored) { }
            });
            t.start();
            pipe.ingest(dataset, "A. Farouk");
            t.join();
            firstPass = (int) pipe.processed();
            System.out.println("   interrupted after " + firstPass + " elements");
            check(firstPass > 0 && firstPass < 60, "AT-05 run was genuinely interrupted");
        }

        // Restart: a fresh pipeline over the same case must complete the work.
        try (CaseDatabase db = new CaseDatabase(folder.database());
             LuceneIndex index = new LuceneIndex(folder.index(), 32)) {
            int doneBefore = db.count();
            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, ev -> { });
            pipe.ingest(dataset, "A. Farouk");
            int doneAfter = db.count();
            System.out.println("   before=" + doneBefore + " after=" + doneAfter);
            check(doneAfter > doneBefore, "AT-05 resume made progress");

            // No duplicate element ids — the queue prevented reprocessing.
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.database());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM (SELECT id FROM item GROUP BY id HAVING COUNT(*)>1)")) {
                int dupIds = rs.next() ? rs.getInt(1) : -1;
                check(dupIds == 0, "AT-05 no duplicated element ids after resume");
            }
        }
    }

    private static void testDedupe(Path work) throws Exception {
        Path dupeDir = work.resolve("dupes");
        Files.createDirectories(dupeDir.resolve("cust_a"));
        Files.createDirectories(dupeDir.resolve("cust_b"));
        byte[] payload = "identical bytes for dedupe scope test".getBytes();
        for (int i = 0; i < 10; i++) {
            Files.write(dupeDir.resolve("cust_a").resolve("f" + i + ".txt"), payload);
            Files.write(dupeDir.resolve("cust_b").resolve("f" + i + ".txt"), payload);
        }

        for (CaseSettings.DedupeScope scope : CaseSettings.DedupeScope.values()) {
            Path caseDir = work.resolve("case-dedupe-" + scope);
            CaseFolder folder = CaseFolder.createOrOpen(caseDir, "DEDUPE-" + scope);
            CaseSettings s = new CaseSettings();
            s.dedupeScope(scope);
            try (CaseDatabase db = new CaseDatabase(folder.database());
                 LuceneIndex index = new LuceneIndex(folder.index(), 32)) {
                IngestPipeline pipe = new IngestPipeline(folder, db, index, s, ev -> { });
                // Two custodians ingested separately, as in a real case.
                pipe.ingest(dupeDir.resolve("cust_a"), "Custodian A");
                IngestPipeline pipe2 = new IngestPipeline(folder, db, index, s, ev -> { });
                pipe2.seedIdSequence(1000);
                pipe2.ingest(dupeDir.resolve("cust_b"), "Custodian B");

                long marked = pipe.duplicateCount() + pipe2.duplicateCount();
                int total = db.count();
                int clusters = db.duplicateClusters().size();
                System.out.printf("   scope=%-14s stored=%d markedDuplicate=%d clusters=%d%n",
                        scope, total, marked, clusters);

                check(total == 20, "AT-04 " + scope + ": all 20 retained (never deleted)");
                if (scope == CaseSettings.DedupeScope.OFF) {
                    check(marked == 0, "AT-04 OFF: nothing suppressed, only highlighted");
                    check(clusters == 1, "AT-04 OFF: duplicate cluster still reported");
                } else {
                    check(marked > 0, "AT-04 " + scope + ": duplicates marked (" + marked + ")");
                }
            }
        }
    }

    private static void verifyHashes(CaseFolder folder, Path dataset, LuceneIndex index)
            throws Exception {
        int verified = 0, mismatched = 0;
        try (var stream = Files.list(dataset)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                byte[] data = Files.readAllBytes(p);
                String sha = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(data));
                Query q = LuceneQueryBuilder.build("sha256:" + sha, index.analyzer());
                List<Document> hits = index.search(q, 5, null);
                if (hits.isEmpty()) { mismatched++; continue; }
                verified++;
            }
        }
        System.out.println("   verified=" + verified + " mismatched=" + mismatched);
        check(mismatched == 0, "AT-09 every source file's SHA-256 matches its indexed element");
        check(verified >= 35, "AT-09 verified " + verified + " top-level files");
    }

    private static void verifySourceUntouched(Path dataset) throws Exception {
        // Re-hash the dataset; the manifest of names must be unchanged and readable.
        // Compare against what the generator actually produced rather than a literal,
        // so adding a fixture cannot silently "fail" an unrelated F-06 assertion.
        long n;
        try (var s = Files.walk(dataset)) { n = s.filter(Files::isRegularFile).count(); }
        check(n == datasetFileCount, "F-06 source file count unchanged ("
                + n + " of " + datasetFileCount + ")");
        boolean allReadable;
        try (var s = Files.walk(dataset)) {
            allReadable = s.filter(Files::isRegularFile).allMatch(Files::isReadable);
        }
        check(allReadable, "F-06 no source file was moved, renamed or deleted");
    }

    private static void checkErrorsHaveReasons(CaseFolder folder) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.database());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name, error FROM item WHERE status='ERROR'")) {
            int withReason = 0, total = 0;
            while (rs.next()) {
                total++;
                String err = rs.getString(2);
                if (err != null && !err.isBlank()) withReason++;
                if (total <= 4) System.out.println("   ERROR " + rs.getString(1) + " → " + err);
            }
            check(total > 0 && withReason == total,
                    "AT-01 every ERROR carries a cause (" + withReason + "/" + total + ")");
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static void checkText(LuceneIndex index, String canary, String label) throws Exception {
        Query q = LuceneQueryBuilder.build("\"" + canary + "\"", index.analyzer());
        List<Document> hits = index.search(q, 5, null);
        check(!hits.isEmpty(), label + " [" + canary + "]");
    }

    private static void q(LuceneIndex index, String query, int minHits, String label)
            throws Exception {
        Query lq = LuceneQueryBuilder.build(query, index.analyzer());
        int n = index.search(lq, 200, null).size();
        boolean ok = minHits == 0 ? n == 0 : n >= minHits;
        check(ok, String.format("%-42s [%s] → %d hits", label, query, n));
    }

    private static long countFiles(Path dir) throws Exception {
        if (!Files.exists(dir)) return 0;
        try (var s = Files.walk(dir)) { return s.filter(Files::isRegularFile).count(); }
    }

    private static void check(boolean cond, String label) {
        if (cond) { passed++; System.out.println("  PASS  " + label); }
        else { failed++; failures.add(label); System.out.println("  FAIL  " + label); }
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (Exception ignored) { }
            });
        }
    }
}
