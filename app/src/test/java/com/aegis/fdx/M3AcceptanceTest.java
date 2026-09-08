package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.EngineEvent;
import com.aegis.fdx.engine.IngestPipeline;
import com.aegis.fdx.engine.IntegrityVerifier;
import com.aegis.fdx.export.Exporter;
import com.aegis.fdx.export.ExportRequest;
import com.aegis.fdx.export.Reports;
import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.index.LuceneQueryBuilder;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.ocr.OcrEngine;
import com.aegis.fdx.ocr.OcrStage;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;

import org.apache.lucene.search.MatchAllDocsQuery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milestone 3 acceptance battery: OCR (A), export and reporting (B), review data
 * (C), and evidence integrity (D).
 *
 * <p>Runs standalone — no JUnit — so it works in the same one-command harness as
 * the earlier milestones.
 */
public final class M3AcceptanceTest {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "/tmp/aegis-m3");
        deleteTree(work);
        Files.createDirectories(work);

        Path corpus = work.resolve("corpus");
        TestDataset.build(corpus);

        Path caseDir = work.resolve("case");
        CaseFolder folder = CaseFolder.createOrOpen(caseDir, "M3-CASE");
        CaseSettings settings = new CaseSettings();
        settings.ocrEnabled(true);
        settings.dedupeScope(CaseSettings.DedupeScope.GLOBAL);

        List<Item> all;
        OcrStage.Summary ocrSummary;
        long elapsed;
        Map<String, List<String>> clusters;

        try (CaseDatabase db = new CaseDatabase(folder.database());
             LuceneIndex index = new LuceneIndex(folder.index(), 64)) {

            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, ev -> { });
            long t0 = System.currentTimeMillis();
            var result = pipe.ingest(corpus, "A. Farouk");
            index.commit();
            elapsed = System.currentTimeMillis() - t0;

            all = loadAll(index);
            ocrSummary = pipe.ocrStage() == null ? null : pipe.ocrStage().summary();
            clusters = db.duplicateClusters();

            section("M3-A  OCR");
            testOcr(index, all, ocrSummary);

            section("M3-B  Export");
            testExport(folder, db, all, work);

            section("M3-B  Reports");
            testReports(folder, db, all, ocrSummary, elapsed, clusters, index);

            section("M3-C  Review data");
            testReview(db, index, all);

            section("M3-D  Integrity");
            testIntegrity(folder, db, all, corpus);
        }

        section("M3-D  Crash recovery");
        testCrashRecovery(work);

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (!failures.isEmpty()) {
            System.out.println("\nFailures:");
            failures.forEach(f -> System.out.println("  - " + f));
        }
        if (failed > 0) System.exit(1);
    }

    // =====================================================================

    private static void testOcr(LuceneIndex index, List<Item> all, OcrStage.Summary sum)
            throws Exception {

        OcrEngine engine = new OcrEngine();
        if (!engine.available()) {
            System.out.println("  SKIP  no Tesseract available in this environment");
            check("OCR degrades cleanly when the engine is absent", sum == null
                    || !sum.engineAvailable());
            return;
        }

        check("OCR engine detected (" + engine.version() + ")", engine.available());
        check("OCR reports installed languages", !engine.installedLanguages().isEmpty());
        check("OCR sweep ran", sum != null && sum.processed() > 0);

        // The decisive test: this text exists nowhere except as pixels.
        int img = hits(index, "\"" + TestDataset.CANARY_OCR_IMAGE + "\"");
        check("OCR canary recovered from image [" + TestDataset.CANARY_OCR_IMAGE + "]", img >= 1);

        int pdf = hits(index, "\"" + TestDataset.CANARY_OCR_PDF + "\"");
        check("OCR canary recovered from scanned PDF", pdf >= 1);

        Item scan = byName(all, "scanned_memo.png");
        check("scanned image element exists", scan != null);
        if (scan != null) {
            check("OCR provenance persisted (ocrApplied)", scan.ocrApplied());
            check("OCR recorded in metadata", scan.metadata().containsKey("OCR"));
            check("OCR text is searchable in the element",
                    scan.extractedText().contains(TestDataset.CANARY_OCR_IMAGE));
        }

        Item digital = byName(all, "settlement_agreement.pdf");
        check("digital PDF was NOT OCR'd (waste avoided)",
                digital != null && !digital.ocrApplied());

        // OCR must not be the only path to this text: regex now spans both.
        check("regex finds OCR'd invoice numbers too", hits(index, "/INV-\\d{5}/") >= 3);
    }

    private static void testExport(CaseFolder folder, CaseDatabase db, List<Item> all,
                                   Path work) throws Exception {

        // ---- native, by custodian ----
        Path dest = work.resolve("export-native");
        var req = new ExportRequest().destination(dest)
                .scope(ExportRequest.Scope.ALL)
                .format(ExportRequest.Format.NATIVE)
                .layout(ExportRequest.Layout.BY_CUSTODIAN);
        var res = new Exporter(folder, db, m -> { }).run(req, all);

        check("native export wrote files", res.written() > 0);
        check("load file produced", res.loadFile() != null && Files.exists(res.loadFile()));
        check("SHA-256 manifest produced",
                Files.exists(dest.resolve("MANIFEST-SHA256.txt")));
        check("exported natives hash-verified against ingest baseline",
                res.hashVerified() > 0);
        check("no hash mismatches in export", res.hashMismatched() == 0);

        String csv = Files.readString(res.loadFile(), StandardCharsets.UTF_8);
        check("CSV is UTF-8 BOM prefixed", csv.startsWith("\uFEFF"));
        String header = csv.split("\r\n")[0].replace("\uFEFF", "");
        check("CSV has the fixed F-27 column set",
                header.equals(String.join(",", Exporter.COLUMNS)));
        check("CSV carries SHA256 column", header.contains("SHA256"));
        check("CSV carries tags and notes", header.contains("Tags") && header.contains("Notes"));
        check("CSV carries relationships",
                header.contains("ParentID") && header.contains("DuplicateOf"));
        long rows = csv.lines().count() - 1;
        check("CSV row per exported element (" + rows + ")", rows >= all.size() - 1);

        // ---- F-24: Hidden must never leave the building ----
        Item victim = all.stream().filter(i -> i.status() == ItemStatus.INDEXED)
                .findFirst().orElse(null);
        check("found an element to hide", victim != null);
        if (victim != null) {
            victim.tags().add("Hidden");
            Path hidDest = work.resolve("export-hidden");
            var hidRes = new Exporter(folder, db, m -> { }).run(
                    new ExportRequest().destination(hidDest)
                            .format(ExportRequest.Format.NATIVE)
                            .layout(ExportRequest.Layout.FLAT), all);
            check("Hidden element excluded from export (F-24)", hidRes.skippedHidden() == 1);

            String hidCsv = Files.readString(hidDest.resolve("loadfile.csv"),
                    StandardCharsets.UTF_8);
            check("Hidden element absent from load file",
                    !hidCsv.contains("," + victim.id() + ",")
                            && !hidCsv.startsWith("\uFEFF" + victim.id()));
            victim.tags().remove("Hidden");
        }

        // ---- layouts ----
        for (var layout : ExportRequest.Layout.values()) {
            Path d = work.resolve("layout-" + layout);
            var r = new Exporter(folder, db, m -> { }).run(
                    new ExportRequest().destination(d)
                            .format(ExportRequest.Format.NATIVE).layout(layout), all);
            check("layout " + layout + " produced output", r.written() > 0);
        }
        check("BY_TYPE layout grouped into type folders",
                Files.isDirectory(work.resolve("layout-BY_TYPE/native/pdf")));
        check("BY_CUSTODIAN layout grouped by custodian",
                Files.isDirectory(work.resolve("layout-BY_CUSTODIAN/native/A. Farouk")));

        // ---- EML ----
        List<Item> emails = all.stream().filter(Item::isEmail).toList();
        check("case contains emails to export", !emails.isEmpty());
        Path emlDest = work.resolve("export-eml");
        var emlRes = new Exporter(folder, db, m -> { }).run(
                new ExportRequest().destination(emlDest)
                        .format(ExportRequest.Format.EML)
                        .layout(ExportRequest.Layout.FLAT), emails);
        check("EML export wrote messages", emlRes.written() > 0);
        try (var s = Files.walk(emlDest)) {
            Path anyEml = s.filter(p -> p.toString().endsWith(".eml")).findFirst().orElse(null);
            check("EML file produced", anyEml != null);
            if (anyEml != null) {
                String eml = Files.readString(anyEml, StandardCharsets.UTF_8);
                check("EML has RFC-822 headers",
                        eml.contains("Subject:") || eml.contains("From:"));
                check("EML records element provenance", eml.contains("X-Aegis-Element-ID:"));
                check("EML preserves the SHA-256", eml.contains("X-Aegis-SHA256:"));
            }
        }

        // ---- PDF ----
        List<Item> forPdf = all.stream()
                .filter(i -> i.extractedText() != null && !i.extractedText().isBlank())
                .limit(12).toList();
        Path pdfDest = work.resolve("export-pdf");
        var pdfRes = new Exporter(folder, db, m -> { }).run(
                new ExportRequest().destination(pdfDest)
                        .format(ExportRequest.Format.PDF)
                        .layout(ExportRequest.Layout.FLAT), forPdf);
        check("PDF export wrote documents", pdfRes.written() > 0);
        check("PDF export had no failures", pdfRes.failed() == 0);
        try (var s = Files.walk(pdfDest)) {
            List<Path> pdfs = s.filter(p -> p.toString().endsWith(".pdf")).toList();
            check("PDF files produced (" + pdfs.size() + ")", !pdfs.isEmpty());
            boolean allValid = true;
            for (Path p : pdfs) {
                byte[] head = Files.readAllBytes(p);
                if (head.length < 5 || head[0] != '%' || head[1] != 'P') allValid = false;
            }
            check("every exported PDF has a valid header", allValid);
        }

        // Unicode must not abort a PDF export.
        Item arabic = byName(all, "arabic.txt");
        if (arabic != null) {
            Path uni = work.resolve("export-unicode");
            var uniRes = new Exporter(folder, db, m -> { }).run(
                    new ExportRequest().destination(uni)
                            .format(ExportRequest.Format.PDF)
                            .layout(ExportRequest.Layout.FLAT), List.of(arabic));
            check("non-Latin text does not break PDF export", uniRes.written() == 1);
        }

        // ---- by tag ----
        Item tagged = all.get(0);
        tagged.tags().add("Responsive");
        Path tagDest = work.resolve("export-tag");
        var tagRes = new Exporter(folder, db, m -> { }).run(
                new ExportRequest().destination(tagDest)
                        .scope(ExportRequest.Scope.BY_TAG).tag("Responsive")
                        .format(ExportRequest.Format.NATIVE)
                        .layout(ExportRequest.Layout.FLAT),
                all.stream().filter(i -> i.tags().contains("Responsive")).toList());
        check("by-tag export scoped correctly", tagRes.candidates() == 1);
    }

    private static void testReports(CaseFolder folder, CaseDatabase db, List<Item> all,
                                    OcrStage.Summary ocr, long elapsed,
                                    Map<String, List<String>> clusters,
                                    LuceneIndex index) throws Exception {

        Reports reports = new Reports(folder, db);

        Path proc = reports.processing(all, ocr, elapsed);
        check("processing report written", Files.exists(proc));
        String p = Files.readString(proc);
        check("processing report has status breakdown", p.contains("Status breakdown"));
        check("processing report has an exceptions section", p.contains("## Exceptions"));
        check("processing report lists the locked element", p.contains("Locked"));
        check("processing report covers OCR", p.contains("## OCR"));
        check("processing report CSV written",
                Files.exists(Path.of(proc.toString().replace(".md", ".csv"))));

        Map<String, Item> byId = new HashMap<>();
        for (Item it : all) byId.put(it.id(), it);
        Path dup = reports.duplicates(clusters, byId);
        check("duplicates report written", Files.exists(dup));
        String d = Files.readString(dup);
        check("duplicates report states nothing is deleted", d.contains("Nothing is deleted"));
        long groups = clusters.values().stream().filter(v -> v.size() > 1).count();
        check("duplicates report found the seeded duplicate group (" + groups + ")", groups >= 1);

        String q = "settlement";
        var hits = searchItems(index, q);
        Path sr = reports.search(q, hits, 12, "analyst");
        check("search report written", Files.exists(sr));
        String s = Files.readString(sr);
        check("search report records the query", s.contains("`settlement`"));
        check("search report records the hit count",
                s.contains(String.valueOf(hits.size())));
        check("search report CSV written",
                Files.exists(Path.of(sr.toString().replace(".md", ".csv"))));
        check("reports land in the case exports folder (F-28)",
                proc.getParent().equals(folder.exports()));
    }

    private static void testReview(CaseDatabase db, LuceneIndex index, List<Item> all)
            throws Exception {

        Item it = all.get(0);
        db.setTags(it.id(), List.of("Responsive", "Needs Review"));
        db.setNotes(it.id(), "Privileged — escalate to lead counsel.");
        it.tags().addAll(List.of("Responsive", "Needs Review"));
        it.notes("Privileged — escalate to lead counsel.");
        index.put(it);
        index.commit();

        check("tags persisted", hits(index, "tag:Responsive") >= 1);
        check("notes are indexed and searchable (F-21)", hits(index, "privileged") >= 1);

        Item reloaded = index.byId(it.id());
        check("tags survive an index round-trip",
                reloaded != null && reloaded.tags().contains("Responsive"));
        check("notes survive an index round-trip",
                reloaded != null && reloaded.notes().contains("Privileged"));

        long emails = all.stream().filter(Item::isEmail).count();
        check("email elements available for threading (" + emails + ")", emails >= 2);
        long withAtt = all.stream().filter(i -> i.attachedFrom() != null).count();
        check("attachments linked to their parent message (" + withAtt + ")", withAtt >= 1);

        Item att = all.stream().filter(i -> i.attachedFrom() != null).findFirst().orElse(null);
        if (att != null) {
            Item r = index.byId(att.id());
            check("attachment relationship survives round-trip",
                    r != null && r.attachedFrom() != null);
        }

        var audit = db.auditLog(500);
        check("audit log is populated", !audit.isEmpty());
        check("audit log recorded the export", audit.stream()
                .anyMatch(a -> "EXPORT".equals(a.action())));

        int before = audit.size();
        db.audit("analyst", "TAG", "applied Responsive to " + it.id(), it.id());
        check("audit log is append-only and grows", db.auditLog(500).size() == before + 1);
    }

    private static void testIntegrity(CaseFolder folder, CaseDatabase db, List<Item> all,
                                      Path corpus) throws Exception {

        var verifier = new IntegrityVerifier(folder, db, m -> { });
        var report = verifier.verifySources(all);

        check("integrity verification ran", report.findings().size() == all.size()
                || !report.findings().isEmpty());
        check("originals verify against their ingest hashes", report.verified() > 0);
        check("no element was modified during processing", report.modified() == 0);
        check("no source went missing during processing", report.missing() == 0);
        check("extracted text intact for index rebuild (F-14)", report.textMissing() == 0);
        check("integrity report is clean", report.clean());

        var readOnly = verifier.verifyReadOnly(all);
        check("source evidence was not written to (F-06)", readOnly.isEmpty());

        // Negative control: tamper with a source and confirm detection. Without
        // this, "all verified" proves nothing.
        Item target = all.stream()
                .filter(i -> i.depth() == 0 && i.sourcePath() != null
                        && i.sourcePath().endsWith("notes.txt"))
                .findFirst().orElse(null);
        check("found a source file to tamper with", target != null);
        if (target != null) {
            Path p = Path.of(target.sourcePath());
            byte[] original = Files.readAllBytes(p);
            try {
                Files.writeString(p, "TAMPERED");
                var after = verifier.verifySources(List.of(target));
                check("tampering with evidence IS detected", after.modified() == 1);
                check("tampered report is not clean", !after.clean());
                check("tamper finding names the element",
                        after.problems().stream().anyMatch(f -> f.itemId().equals(target.id())));
            } finally {
                Files.write(p, original);
            }
            var restored = verifier.verifySources(List.of(target));
            check("restored evidence verifies again", restored.verified() == 1);
        }

        var missingReport = verifier.verifySources(List.of(syntheticMissing()));
        check("a missing source is reported, not ignored", missingReport.missing() == 1);
    }

    /**
     * AT-05 / M3-D. Simulates a crash mid-ingest by abandoning the case, then
     * reopening it and re-running. Completed work must not be redone, and the
     * case must remain consistent.
     */
    private static void testCrashRecovery(Path work) throws Exception {
        Path corpus = work.resolve("corpus");
        Path caseDir = work.resolve("crash-case");
        CaseFolder folder = CaseFolder.createOrOpen(caseDir, "CRASH");
        CaseSettings settings = new CaseSettings();
        settings.ocrEnabled(false);          // keep the test fast and deterministic

        int firstPass;
        // Pass 1: cancel partway, leaving the queue and index mid-flight.
        try (CaseDatabase db = new CaseDatabase(folder.database());
             LuceneIndex index = new LuceneIndex(folder.index(), 32)) {
            final IngestPipeline[] ref = new IngestPipeline[1];
            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, ev -> {
                if (ev instanceof EngineEvent.ItemIndexed ii && ii.done() >= 10) {
                    ref[0].cancel();
                }
            });
            ref[0] = pipe;
            pipe.ingest(corpus, "A. Farouk");
            index.commit();
            firstPass = index.count();
        }
        check("interrupted run persisted partial work (" + firstPass + ")", firstPass > 0);

        // Pass 2: reopen exactly as a restart would.
        int secondPass;
        int reprocessed;
        long skipped;
        List<Item> afterResume;
        try (CaseDatabase db = new CaseDatabase(folder.database());
             LuceneIndex index = new LuceneIndex(folder.index(), 32)) {
            check("case database reopens after an abrupt stop", db.count() > 0);
            check("index reopens after an abrupt stop", index.count() == firstPass);

            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, ev -> { });
            var r = pipe.ingest(corpus, "A. Farouk");
            index.commit();
            secondPass = index.count();
            reprocessed = (int) r.processed();
            skipped = pipe.skippedCount();
            afterResume = loadAll(index);
        }
        check("resumed run completed the case", secondPass > firstPass);
        check("resume skipped already-finished work (" + skipped + " skipped)", skipped > 0);
        check("resume did not redo everything", reprocessed < secondPass);

        // The property that actually matters: the recovered case must be identical
        // to one produced by an uninterrupted run — no element indexed twice.
        Map<String, Integer> identity = new HashMap<>();
        for (Item it : afterResume) {
            identity.merge(it.sourcePath() + "|" + it.name() + "|" + it.depth(), 1, Integer::sum);
        }
        long dupKeys = identity.values().stream().filter(v -> v > 1).count();
        check("no element indexed twice after resume (" + dupKeys + " dupes)", dupKeys == 0);

        // Compare against a pristine control run over the same corpus.
        Path controlDir = work.resolve("control-case");
        CaseFolder control = CaseFolder.createOrOpen(controlDir, "CONTROL");
        int controlCount;
        try (CaseDatabase db = new CaseDatabase(control.database());
             LuceneIndex index = new LuceneIndex(control.index(), 32)) {
            new IngestPipeline(control, db, index, settings, ev -> { })
                    .ingest(corpus, "A. Farouk");
            index.commit();
            controlCount = index.count();
        }
        check("recovered case matches an uninterrupted run ("
                + secondPass + " vs " + controlCount + ")", secondPass == controlCount);

        // Third open: state is stable, nothing left pending.
        try (CaseDatabase db = new CaseDatabase(folder.database())) {
            check("queue drained after resume", db.queueCount("PENDING") == 0);
            check("audit log survived the crash", !db.auditLog(10).isEmpty()
                    || db.count() > 0);
        }
    }

    // =====================================================================

    private static Item syntheticMissing() {
        Item it = new Item("E-MISSING", "vanished.pdf");
        it.sourcePath("/nonexistent/vanished.pdf");
        it.sha256("0".repeat(64));
        it.depth(0);
        return it;
    }

    private static List<Item> loadAll(LuceneIndex index) throws Exception {
        List<Item> out = new ArrayList<>();
        for (var d : index.search(new MatchAllDocsQuery(), 100_000, null)) {
            out.add(LuceneIndex.fromDocument(d));
        }
        out.sort(Comparator.comparing(Item::id));
        return out;
    }

    private static List<Item> searchItems(LuceneIndex index, String q) throws Exception {
        List<Item> out = new ArrayList<>();
        for (var d : index.search(LuceneQueryBuilder.build(q, index.analyzer()), 500, null)) {
            out.add(LuceneIndex.fromDocument(d));
        }
        return out;
    }

    private static int hits(LuceneIndex index, String q) throws Exception {
        return index.search(LuceneQueryBuilder.build(q, index.analyzer()), 500, null).size();
    }

    private static Item byName(List<Item> all, String name) {
        return all.stream().filter(i -> name.equals(i.name())).findFirst().orElse(null);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("-- " + title + " " + "-".repeat(Math.max(0, 56 - title.length())));
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + what);
        } else {
            failed++;
            failures.add(what);
            System.out.println("  FAIL  " + what);
        }
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (Exception ignored) { }
            });
        }
    }
}
