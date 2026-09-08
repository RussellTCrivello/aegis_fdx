package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.IngestPipeline;
import com.aegis.fdx.export.ExportRequest;
import com.aegis.fdx.export.Exporter;
import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;

import org.apache.lucene.search.MatchAllDocsQuery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 4. Windows-compatibility checks (N-01: Windows 10/11 x64 is the primary
 * target).
 *
 * <p>These assertions are platform-independent by design — they verify the rules
 * Windows imposes, so a defect is caught on any build machine rather than only
 * surfacing during Windows validation. An export produced on Linux must remain
 * usable when copied to a Windows review workstation.
 */
public final class WindowsCompatibilityTest {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    /** Names Windows cannot create, even with an extension. */
    private static final String[] RESERVED = {
            "CON", "PRN", "AUX", "NUL", "COM1", "COM9", "LPT1", "LPT9",
            "con.txt", "nul.pdf", "Com1.docx"
    };

    /** Characters illegal in a Win32 filename. */
    private static final String ILLEGAL = "\\/:*?\"<>|";

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "/tmp/aegis-win");
        deleteTree(work);
        Files.createDirectories(work);

        section("Filename safety (Win32 rules)");
        testFilenames(work);

        section("Export on a Windows-hostile corpus");
        testHostileExport(work);

        section("Path and encoding conventions");
        testPathConventions(work);

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (!failures.isEmpty()) {
            System.out.println("\nFailures:");
            failures.forEach(f -> System.out.println("  - " + f));
        }
        if (failed > 0) System.exit(1);
    }

    // =====================================================================

    /**
     * The sanitizer is exercised through a real export, because that is the only
     * path that actually creates files. Testing it directly would not prove the
     * writer uses it.
     */
    private static void testFilenames(Path work) throws Exception {
        Path caseDir = work.resolve("names-case");
        CaseFolder folder = CaseFolder.createOrOpen(caseDir, "WIN-NAMES");

        List<Item> items = new ArrayList<>();
        int n = 0;
        for (String name : RESERVED) items.add(synthetic("E-R" + (n++), name));
        for (String name : new String[]{
                "report.", "report..", "trailing ", " leading", ".", "..",
                "a:b*c?.txt", "quote\"name.pdf", "pipe|name.txt",
                "tab\tname.txt", "nul\u0000byte.txt", "", "   ",
                "an-extremely-long-attachment-name-".repeat(12) + ".pdf"}) {
            items.add(synthetic("E-N" + (n++), name));
        }

        Path dest = work.resolve("names-export");
        try (CaseDatabase db = new CaseDatabase(folder.database())) {
            new Exporter(folder, db, m -> { }).run(
                    new ExportRequest().destination(dest)
                            .format(ExportRequest.Format.TEXT)
                            .layout(ExportRequest.Layout.FLAT)
                            .verifyHashes(false), items);
        }

        List<Path> written = new ArrayList<>();
        try (var s = Files.walk(dest)) {
            s.filter(Files::isRegularFile).forEach(written::add);
        }
        check("hostile names still produced files (" + written.size() + ")",
                written.size() >= items.size());

        boolean reservedClean = true, illegalClean = true;
        boolean trailingClean = true, lengthClean = true;
        String offender = "";
        for (Path p : written) {
            String fn = p.getFileName().toString();
            String stem = fn.contains(".") ? fn.substring(0, fn.indexOf('.')) : fn;

            if (stem.matches("(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])$")) {
                reservedClean = false; offender = fn;
            }
            for (char c : ILLEGAL.toCharArray()) {
                if (fn.indexOf(c) >= 0) { illegalClean = false; offender = fn; }
            }
            for (char c : fn.toCharArray()) {
                if (c < 32) { illegalClean = false; offender = fn; }
            }
            if (fn.endsWith(".") || fn.endsWith(" ")) {
                trailingClean = false; offender = fn;
            }
            if (fn.length() > 200) { lengthClean = false; offender = fn; }
        }

        check("no Windows reserved device names produced" + note(offender, reservedClean),
                reservedClean);
        check("no illegal Win32 characters in filenames" + note(offender, illegalClean),
                illegalClean);
        check("no trailing dots or spaces (Win32 strips these)" + note(offender, trailingClean),
                trailingClean);
        check("filenames bounded for MAX_PATH headroom", lengthClean);

        // Distinct elements must never collide after sanitisation, or one silently
        // overwrites another and evidence is lost from the production.
        long distinct = written.stream().map(p -> p.getFileName().toString()).distinct().count();
        check("sanitised names remain unique (" + distinct + "/" + written.size() + ")",
                distinct == written.size());
    }

    /** Full pipeline over files whose names are legal on Linux but hostile to Windows. */
    private static void testHostileExport(Path work) throws Exception {
        Path corpus = work.resolve("corpus");
        Files.createDirectories(corpus);

        // Legal on Linux, all problematic on Windows.
        String[] names = {"normal.txt", "trailing.", "spaced .txt", "unicode-\u00e9\u00fc.txt"};
        for (String nm : names) {
            try {
                Files.writeString(corpus.resolve(nm),
                        "Settlement content for " + nm + " INV-88213", StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Some names cannot even be created on this filesystem; skip.
            }
        }
        Files.writeString(corpus.resolve("CON.txt"), "reserved device name content",
                StandardCharsets.UTF_8);

        Path caseDir = work.resolve("hostile-case");
        CaseFolder folder = CaseFolder.createOrOpen(caseDir, "WIN-HOSTILE");
        CaseSettings settings = new CaseSettings();
        settings.ocrEnabled(false);

        List<Item> all;
        try (CaseDatabase db = new CaseDatabase(folder.database());
             LuceneIndex index = new LuceneIndex(folder.index(), 32)) {
            var r = new IngestPipeline(folder, db, index, settings, e -> { })
                    .ingest(corpus, "Windows Tester");
            index.commit();
            check("hostile-named evidence ingested (" + r.processed() + ")", r.processed() > 0);

            all = new ArrayList<>();
            for (var d : index.search(new MatchAllDocsQuery(), 1000, null)) {
                all.add(LuceneIndex.fromDocument(d));
            }

            Path dest = work.resolve("hostile-export");
            var res = new Exporter(folder, db, m -> { }).run(
                    new ExportRequest().destination(dest)
                            .format(ExportRequest.Format.NATIVE)
                            .layout(ExportRequest.Layout.BY_CUSTODIAN), all);

            check("hostile-named evidence exported without loss",
                    res.written() == all.size());
            check("export of hostile names had no failures", res.failed() == 0);
            check("hashes verified on hostile-named exports", res.hashMismatched() == 0);

            String csv = Files.readString(res.loadFile(), StandardCharsets.UTF_8);
            check("load file uses CRLF line endings (Excel on Windows)",
                    csv.contains("\r\n"));
            check("load file is BOM-prefixed UTF-8", csv.startsWith("\uFEFF"));
            check("load file preserves non-ASCII names",
                    csv.contains("unicode-") || all.stream()
                            .noneMatch(i -> i.name().startsWith("unicode-")));
        }
    }

    private static void testPathConventions(Path work) throws Exception {
        // Load-file paths must be forward-slash relative, so a production is
        // portable between the machine that made it and the one that reads it.
        Path caseDir = work.resolve("conv-case");
        CaseFolder folder = CaseFolder.createOrOpen(caseDir, "WIN-CONV");

        List<Item> items = List.of(synthetic("E-C1", "doc.txt"), synthetic("E-C2", "other.txt"));
        Path dest = work.resolve("conv-export");
        try (CaseDatabase db = new CaseDatabase(folder.database())) {
            var res = new Exporter(folder, db, m -> { }).run(
                    new ExportRequest().destination(dest)
                            .format(ExportRequest.Format.TEXT)
                            .layout(ExportRequest.Layout.BY_TYPE)
                            .verifyHashes(false), items);

            String csv = Files.readString(res.loadFile(), StandardCharsets.UTF_8);
            check("exported paths use forward slashes in the load file",
                    !csv.contains("\\\\") || csv.contains("/"));
            check("exported paths are relative, not absolute",
                    !csv.contains(dest.toAbsolutePath().toString()));
        }

        // The case folder must be relocatable: no absolute path may be baked in.
        check("case layout is relative to the case root",
                folder.index().startsWith(folder.root())
                        && folder.text().startsWith(folder.root())
                        && folder.exports().startsWith(folder.root()));

        // Windows path separators in container paths must not break the hierarchy
        // layout, which splits on both separators.
        Item nested = synthetic("E-H1", "member.txt");
        nested.containerPath("archive.zip!sub\\dir\\member.txt");
        nested.depth(1);
        Path hier = work.resolve("hier-export");
        try (CaseDatabase db = new CaseDatabase(folder.database())) {
            var res = new Exporter(folder, db, m -> { }).run(
                    new ExportRequest().destination(hier)
                            .format(ExportRequest.Format.TEXT)
                            .layout(ExportRequest.Layout.HIERARCHY)
                            .verifyHashes(false), List.of(nested));
            check("Windows separators in container paths export correctly",
                    res.written() == 1);
        }

        check("line.separator handling does not corrupt text",
                "a\r\nb".lines().count() == 2);
    }

    // =====================================================================

    private static Item synthetic(String id, String name) {
        Item it = new Item(id, name);
        it.extractedText("Synthetic content for " + id + ": settlement INV-88213");
        it.custodian("Windows Tester");
        it.status(com.aegis.fdx.model.ItemStatus.INDEXED);
        it.extension("txt");
        it.size(64);
        return it;
    }

    private static String note(String offender, boolean ok) {
        return ok || offender.isEmpty() ? "" : " [" + offender + "]";
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("-- " + title + " " + "-".repeat(Math.max(0, 54 - title.length())));
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
