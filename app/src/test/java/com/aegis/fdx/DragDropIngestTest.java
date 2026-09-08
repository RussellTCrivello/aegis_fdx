package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.model.Item;

import org.apache.lucene.search.MatchAllDocsQuery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * F-01 drag-and-drop intake, verified at the engine level.
 *
 * <p>The FX drop handler itself cannot be exercised headlessly, so this suite
 * verifies the contract the handler depends on: that dropping files, folders,
 * containers and multi-selections all flow through the <em>same</em>
 * {@code LiveCase.startIngest} path as the Add-evidence dialog, with no separate
 * ingestion route and no element-ID collisions across the sequential submissions
 * a multi-select drop produces.
 *
 * <p>The handler is a thin loop over dropped paths calling {@code startRealIngest}
 * per path; the presence of that wiring is separately gated by
 * {@code final-acceptance.sh}, so together the two cover the feature.
 */
public final class DragDropIngestTest {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "build/test-work/dnd");
        deleteTree(work);
        Files.createDirectories(work);

        section("Drop modes (F-01)");
        testDropModes(work.resolve("modes"));

        section("Multi-select drop (sequential submissions)");
        testMultiSelectDrop(work.resolve("multi"));

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (!failures.isEmpty()) {
            System.out.println("\nFailures:");
            failures.forEach(f -> System.out.println("  - " + f));
        }
        if (failed > 0) System.exit(1);
    }

    /** Explorer permits dropping a loose file, a container, or a folder. All must work. */
    private static void testDropModes(Path work) throws Exception {
        Files.createDirectories(work);

        Path loose = work.resolve("lone-evidence.txt");
        Files.writeString(loose, "lone settlement INV-77777 content");

        Path container = work.resolve("lone-container.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(container))) {
            for (int i = 0; i < 3; i++) {
                z.putNextEntry(new ZipEntry("z" + i + ".txt"));
                z.write(("zipped member " + i).getBytes());
                z.closeEntry();
            }
        }

        Path folder = work.resolve("dropped-folder");
        Files.createDirectories(folder);
        for (int i = 0; i < 3; i++) {
            Files.writeString(folder.resolve("in" + i + ".txt"), "folder content " + i);
        }

        CaseSettings st = new CaseSettings();
        st.ocrEnabled(false);
        try (LiveCase lc = new LiveCase(work.resolve("case"), "DND-MODES", st)) {
            var r1 = lc.startIngest(loose, "Dropper", e -> { }).get();
            check("single dropped file ingested", r1.processed() == 1 && r1.errors() == 0);

            var r2 = lc.startIngest(container, "Dropper", e -> { }).get();
            check("single dropped container expanded (" + r2.processed() + " elements)",
                    r2.processed() >= 4);

            var r3 = lc.startIngest(folder, "Dropper", e -> { }).get();
            check("dropped folder ingested (" + r3.processed() + ")", r3.processed() == 3);

            lc.index().commit();
            List<String> names = names(lc);
            check("loose file present in index", names.contains("lone-evidence.txt"));
            check("container members present in index",
                    names.stream().anyMatch(n -> n.startsWith("z") && n.endsWith(".txt")));
            check("folder contents present in index", names.contains("in0.txt"));

            // A drop must not bypass custodian attribution.
            boolean attributed = true;
            for (Item it : items(lc)) {
                if (it.custodian() == null || it.custodian().isBlank()) attributed = false;
            }
            check("every dropped element carries a custodian", attributed);
        }
    }

    /**
     * A multi-select drop submits one ingest per dropped path against a single case.
     * Element IDs must stay unique across those submissions or evidence is
     * overwritten in the export.
     */
    private static void testMultiSelectDrop(Path work) throws Exception {
        Files.createDirectories(work);

        List<Path> dropped = new ArrayList<>();
        for (int d = 1; d <= 3; d++) {
            Path dir = work.resolve("sel" + d);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("plain" + d + ".txt"), "settlement INV-9000" + d);
            // Containers matter here: children use namespaced ids, parents use the
            // global sequence, so both schemes are exercised across submissions.
            try (ZipOutputStream z = new ZipOutputStream(
                    Files.newOutputStream(dir.resolve("bundle" + d + ".zip")))) {
                for (int i = 0; i < 4; i++) {
                    z.putNextEntry(new ZipEntry("inner" + d + "_" + i + ".txt"));
                    z.write(("nested content " + d + " " + i).getBytes());
                    z.closeEntry();
                }
            }
            dropped.add(dir);
        }

        CaseSettings st = new CaseSettings();
        st.ocrEnabled(false);
        try (LiveCase lc = new LiveCase(work.resolve("case"), "DND-MULTI", st)) {
            long total = 0;
            for (Path p : dropped) {
                total += lc.startIngest(p, "Dropper", e -> { }).get().processed();
            }
            lc.index().commit();

            check("all dropped paths ingested (" + total + ")", total >= 15);

            List<String> ids = new ArrayList<>();
            for (Item it : items(lc)) ids.add(it.id());
            Set<String> uniq = new HashSet<>(ids);

            if (uniq.size() != ids.size()) {
                Map<String, Integer> c = new TreeMap<>();
                for (String i : ids) c.merge(i, 1, Integer::sum);
                System.out.println("        colliding: " + c.entrySet().stream()
                        .filter(e -> e.getValue() > 1).limit(10).toList());
            }
            check("element ids unique across sequential drops ("
                    + uniq.size() + "/" + ids.size() + ")", uniq.size() == ids.size());

            // Each submission must see the prior one's state, not restart numbering.
            check("id sequence advanced past the first submission",
                    lc.db().maxElementSequence() >= 6);
        }
    }

    // =====================================================================

    private static List<Item> items(LiveCase lc) throws Exception {
        List<Item> out = new ArrayList<>();
        for (var d : lc.index().search(new MatchAllDocsQuery(), 5000, null)) {
            out.add(LuceneIndex.fromDocument(d));
        }
        return out;
    }

    private static List<String> names(LiveCase lc) throws Exception {
        List<String> out = new ArrayList<>();
        for (Item it : items(lc)) out.add(it.name());
        return out;
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
