package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.IntegrityVerifier;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.model.Item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when the case is not in the state the application hoped for.
 *
 * <p>Cases are copied between machines, live on network shares, and get caught by a
 * laptop closing mid-run. The interesting question is not whether the happy path works
 * — the acceptance suites answer that — but whether a damaged, locked or half-copied
 * case produces a clear sentence and an intact case, or a stack trace and a worse case
 * than before.
 *
 * <p>Each check breaks something real on disk and then asserts two things: that the
 * application says what is wrong in words an examiner can act on, and that nothing was
 * destroyed on the way to finding out.
 */
public final class ResilienceTest {

    @Test
    @DisplayName("A damaged search index is rebuilt from the case database")
    void damagedIndexIsRebuilt() throws Exception {
        Path root = caseRoot("damaged-index");
        int stored;
        try (LiveCase c = open(root)) {
            seed(c, root.getParent().resolve("evidence"));
            stored = c.db().count();
            assertEquals(stored, c.indexedCount());
        }

        // Overwrite the segment files, as an interrupted copy would.
        for (Path p : filesUnder(root.resolve("index"))) {
            if (p.getFileName().toString().startsWith("segments")
                    || p.getFileName().toString().endsWith(".cfs")) {
                Files.write(p, "not an index".getBytes(StandardCharsets.UTF_8));
            }
        }

        try (LiveCase c = open(root)) {
            assertNotNull(c.indexRepair(), "the repair must be reported, not done silently");
            assertTrue(c.indexRepair().contains("rebuilt"), c.indexRepair());
            assertEquals(stored, c.db().count(), "the database was never at risk");
            assertEquals(stored, c.indexedCount(), "every element is searchable again");

            AegisFacades f = AegisFacades.open(c);
            assertFalse(f.search().search("acme").results().isEmpty(),
                    "the rebuilt index answers the same questions as the old one");

            // The damaged index is kept for examination rather than deleted.
            assertTrue(filesUnder(root.resolve("logs")).stream()
                            .anyMatch(p -> p.toString().contains("index-damaged-")),
                    "the damaged index must be preserved under logs/");
        }
    }

    @Test
    @DisplayName("A rebuild restores tags, notes and text, not just the file names")
    void rebuildKeepsEverythingTheDatabaseKnows() throws Exception {
        Path root = caseRoot("rebuild-fidelity");
        try (LiveCase c = open(root)) {
            AegisFacades f = seed(c, root.getParent().resolve("evidence-fidelity"));
            List<Item> items = c.allItems();
            Item first = items.get(0);
            c.applyTag(List.of(first), "Responsive", "tester");
            c.setNotes(first, "read and marked", "tester");

            int n = c.rebuildIndex();
            assertEquals(c.db().count(), n, "the rebuild covers the whole case");

            Item after = c.byId(first.id());
            assertNotNull(after);
            assertTrue(after.tags().contains("Responsive"), "tags survive a rebuild");
            assertEquals("read and marked", after.notes(), "notes survive a rebuild");
            assertEquals(first.sha256(), after.sha256(), "hashes are unchanged");
            assertFalse(after.extractedText().isBlank(), "extracted text is restored from the text store");
            assertFalse(f.search().search("tag:Responsive").results().isEmpty(),
                    "and the rebuilt index can be searched on them");
        }
    }

    @Test
    @DisplayName("A case already open elsewhere is refused, and left alone")
    void aCaseOpenElsewhereIsRefused() throws Exception {
        Path root = caseRoot("locked");
        try (LiveCase first = open(root)) {
            seed(first, root.getParent().resolve("evidence-locked"));
            int stored = first.db().count();

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> open(root),
                    "a second window must not open a case that is already open");
            assertTrue(refused.getMessage().contains("already open"), refused.getMessage());

            assertEquals(stored, first.db().count(), "the open case is untouched");
            assertEquals(stored, first.indexedCount(),
                    "and — the point of the check — its index was not treated as damage");
        }

        // Once the first session has closed, the case opens normally again.
        try (LiveCase again = open(root)) {
            assertTrue(again.db().count() > 0);
            assertEquals(again.db().count(), again.indexedCount());
            assertEquals(null, again.indexRepair(), "nothing needed repairing");
        }
    }

    @Test
    @DisplayName("An unreadable case database is reported in words, not as a driver error")
    void unreadableDatabaseIsExplained() throws Exception {
        Path root = caseRoot("bad-db");
        try (LiveCase c = open(root)) {
            seed(c, root.getParent().resolve("evidence-bad-db"));
        }

        Path db = root.resolve("db").resolve("case.db");
        byte[] good = Files.readAllBytes(db);
        Files.write(db, "this is not a database".getBytes(StandardCharsets.UTF_8));

        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> open(root));
        assertTrue(failed.getMessage().contains("database cannot be read"), failed.getMessage());
        assertTrue(failed.getMessage().contains("case.db"), "say which file");
        assertTrue(failed.getMessage().contains("backup"), "say what to do about it");

        // And the case is exactly as it was: nothing was rebuilt, moved or cleared.
        Files.write(db, good);
        try (LiveCase c = open(root)) {
            assertTrue(c.db().count() > 0);
            assertEquals(c.db().count(), c.indexedCount());
        }
    }

    @Test
    @DisplayName("Missing extracted text is a finding, not a crash")
    void missingTextIsReported() throws Exception {
        Path root = caseRoot("no-text");
        try (LiveCase c = open(root)) {
            seed(c, root.getParent().resolve("evidence-no-text"));
        }

        for (Path p : filesUnder(root.resolve("text"))) {
            Files.delete(p);
        }

        try (LiveCase c = open(root)) {
            AegisFacades f = AegisFacades.open(c);
            assertTrue(c.db().count() > 0, "the case still opens");
            assertFalse(f.search().search("acme").results().isEmpty(),
                    "search still works: the index holds its own copy of the text");

            IntegrityVerifier.Report report = c.verifyIntegrity(s -> { }).get();
            assertFalse(report.findings().isEmpty(),
                    "the verifier must notice that the evidence text is gone");
        }
    }

    @Test
    @DisplayName("A case whose folder metadata was lost still opens")
    void missingCaseMetadataIsRecreated() throws Exception {
        Path root = caseRoot("no-json");
        int stored;
        try (LiveCase c = open(root)) {
            seed(c, root.getParent().resolve("evidence-no-json"));
            stored = c.db().count();
        }

        Files.deleteIfExists(root.resolve("case.json"));

        try (LiveCase c = open(root)) {
            assertEquals(stored, c.db().count(), "the evidence is in the database, not in case.json");
            assertEquals(stored, c.indexedCount());
            assertTrue(Files.isRegularFile(root.resolve("case.json")),
                    "the descriptor is written again rather than left missing");
        }
    }

    // ============================================================== fixtures

    private static Path work;

    private static Path caseRoot(String name) throws IOException {
        Path base = work != null ? work : (work = Files.createTempDirectory("aegis-resilience-"));
        Path root = base.resolve(name).resolve("case");
        Files.createDirectories(root.getParent());
        return root;
    }

    private static LiveCase open(Path root) {
        try {
            CaseSettings s = new CaseSettings();
            s.ocrEnabled(false);
            return new LiveCase(root, "Resilience", s);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AegisFacades seed(LiveCase c, Path evidence) throws Exception {
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("invoice.txt"),
                "Invoice 2024 from Acme for consulting services. Payment due in 30 days.",
                StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("memo.txt"),
                "Internal memo about the Acme consulting agreement and its payment terms.",
                StandardCharsets.UTF_8);

        AegisFacades f = AegisFacades.open(c);
        int src = f.sources().createSource("Acme", "NL", "custodian", 0.8);
        int asp = f.aspects().createAspect("Plaintiff", 0.9);
        f.processing("Acme", "Plaintiff").processFolder(evidence.toString());
        f.contents().registerIngestedItems(src, asp);
        return f;
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
        }
    }

    // ================================================================== main

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            work = Path.of(args[0]);
            Files.createDirectories(work);
        }
        ResilienceTest t = new ResilienceTest();
        String[] names = {
            "a damaged search index is rebuilt from the case database",
            "a rebuild restores tags, notes and text",
            "a case already open elsewhere is refused, and left alone",
            "an unreadable case database is reported in words",
            "missing extracted text is a finding, not a crash",
            "a case whose folder metadata was lost still opens",
        };
        Check[] body = {
            t::damagedIndexIsRebuilt,
            t::rebuildKeepsEverythingTheDatabaseKnows,
            t::aCaseOpenElsewhereIsRefused,
            t::unreadableDatabaseIsExplained,
            t::missingTextIsReported,
            t::missingCaseMetadataIsRecreated,
        };
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < body.length; i++) {
            try {
                body[i].run();
                System.out.println("  ok    " + names[i]);
                passed++;
            } catch (Throwable e) {
                System.out.println("  FAIL  " + names[i] + " — " + e);
                failed++;
            }
        }
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface Check {
        void run() throws Exception;
    }
}
