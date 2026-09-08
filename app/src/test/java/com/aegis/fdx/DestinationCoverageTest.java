package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.dto.CategoryUsage;
import com.aegis.fdx.facade.dto.EntityStatistics;
import com.aegis.fdx.facade.dto.ErrorReport;
import com.aegis.fdx.facade.dto.KeywordUsage;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.PathNode;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the destinations added after the reference audit.
 *
 * <p>These assert the data each destination renders, not the pixels: every rollup,
 * relationship join, tree and report is exercised against a real processed case, so a
 * destination cannot pass by rendering an empty shell.
 */
class DestinationCoverageTest {

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Destinations", s);
    }

    /** A case with real processed material, including a nested archive. */
    private Seeded seed(LiveCase c, Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("invoice.txt"),
                "Invoice 2024 from Acme for consulting. Payment due in 30 days.",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("memo.txt"),
                "Memo about the Acme consulting agreement and payment terms.",
                StandardCharsets.UTF_8);
        Files.createDirectories(ev.resolve("sub"));
        Files.writeString(ev.resolve("sub").resolve("nested-note.txt"),
                "A nested note mentioning consulting.", StandardCharsets.UTF_8);

        Path zip = ev.resolve("bundle.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("inside.txt"));
            z.write("Bundled consulting document".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }

        AegisFacades f = AegisFacades.open(c);
        CorpusDatabase dao = new CorpusDatabase(c.db());
        int src = f.sources().createSource(
                new SourceDraft("Acme", "NL", "custodian", 0.8).city("Amsterdam"));
        int asp = f.aspects().createAspect("Plaintiff", 0.9);
        int cat = f.categories().createCategory("finance");
        f.categories().linkWordToCategory("invoice", "finance");
        f.keywords().createKeyword("payment due", "finance");
        int kw = f.keywords().listKeywords().results().get(0).id();

        f.processing("Acme", "Plaintiff").processFolder(ev.toString());
        f.contents().registerIngestedItems(src, asp);

        // classify a couple of files so the relationship joins have something real
        for (PathDto p : f.contents().getPaths().results()) {
            if (p.fileName().startsWith("invoice")) {
                dao.linkPathToCategory(p.id(), cat);
                dao.linkPathToKeyword(p.id(), kw, 3);
            }
            if (p.fileName().startsWith("memo")) {
                dao.linkPathToCategory(p.id(), cat);
                dao.linkPathToKeyword(p.id(), kw, 1);
            }
        }
        return new Seeded(f, dao, src, asp, cat, kw);
    }

    private record Seeded(AegisFacades f, CorpusDatabase dao,
                          int sourceId, int aspectId, int categoryId, int keywordId) {
    }

    // ==================================================== detail destinations

    @Test
    @DisplayName("Source detail shows real statistics computed from the registry")
    void sourceStatistics(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);

            EntityStatistics st = s.f().analytics().sourceStatistics(s.sourceId());
            assertTrue(st.files() >= 4, "expected the ingested files, got " + st.files());
            assertTrue(st.bytes() > 0, "byte total must be real");
            assertTrue(st.distinctTypes() >= 2, "txt and zip at least");
            assertEquals(0, st.readFiles(), "nothing reviewed yet");
            assertEquals(0.0, st.reviewProgress(), 1e-9);
            assertEquals(st.files(), st.unreadFiles());
            assertFalse(st.typeBreakdown().isEmpty(), "type histogram must be populated");
            assertNotNull(st.earliest());
            assertNotNull(st.latest());

            // review progress moves when a file is actually marked read
            PathDto first = s.f().contents().getPaths().results().get(0);
            s.f().contents().setPathStatus(first.id(), "Read");
            EntityStatistics after = s.f().analytics().sourceStatistics(s.sourceId());
            assertEquals(1, after.readFiles());
            assertTrue(after.reviewProgress() > 0);
        }
    }

    @Test
    @DisplayName("Aspect detail statistics are scoped to that aspect alone")
    void aspectStatistics(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            int other = s.f().aspects().createAspect("Defendant", 0.4);

            EntityStatistics mine = s.f().analytics().aspectStatistics(s.aspectId());
            EntityStatistics empty = s.f().analytics().aspectStatistics(other);

            assertTrue(mine.files() >= 4);
            assertEquals(0, empty.files(), "an unused aspect must report nothing");
            assertTrue(empty.isEmpty());
        }
    }

    @Test
    @DisplayName("Source and aspect relationship views resolve real categories and keywords")
    void relationships(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);

            List<CategoryUsage> cats = s.f().analytics().categoriesForSource(s.sourceId());
            assertEquals(1, cats.size());
            assertEquals("finance", cats.get(0).word());
            assertEquals(2, cats.get(0).fileCount(), "two files were classified");

            List<KeywordUsage> kws = s.f().analytics().keywordsForSource(s.sourceId());
            assertEquals(1, kws.size());
            assertEquals("payment due", kws.get(0).phrase());
            assertEquals(4, kws.get(0).hits(), "3 + 1 recorded hits");
            assertEquals(2, kws.get(0).files());
            assertEquals("finance", kws.get(0).categoryWord());

            // the same joins scoped by aspect
            assertEquals(1, s.f().analytics().categoriesForAspect(s.aspectId()).size());
            assertEquals(1, s.f().analytics().keywordsForAspect(s.aspectId()).size());
        }
    }

    @Test
    @DisplayName("Category drill-through lists the files under a category")
    void categoryDrillThrough(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            List<PathDto> files = s.f().analytics().filesForCategory(s.categoryId(), 50);
            assertEquals(2, files.size());
            assertTrue(files.stream().anyMatch(p -> p.fileName().startsWith("invoice")));
            assertNotNull(files.get(0).sourceName(), "join must resolve the source name");
        }
    }

    @Test
    @DisplayName("Keyword detail lists the files carrying it, with per-file hit counts")
    void keywordDetail(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            List<KeywordUsage.FileHit> hits =
                    s.f().analytics().filesForKeyword(s.keywordId(), 50);
            assertEquals(2, hits.size());
            assertTrue(hits.get(0).hits() >= hits.get(1).hits(), "ordered by hits");
            assertEquals(3, hits.get(0).hits());
            assertNotNull(hits.get(0).fileName());
        }
    }

    @Test
    @DisplayName("Word detail resolves the categories a word belongs to")
    void wordDetail(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            int wordId = s.f().words().searchWords("invoice", 10, 0).results().get(0).id();
            List<CategoryUsage> cats = s.f().analytics().categoriesForWord(wordId);
            assertEquals(1, cats.size());
            assertEquals("finance", cats.get(0).word());
        }
    }

    // ================================================== analytics destinations

    @Test
    @DisplayName("Path analysis builds a real directory tree with rolled-up totals")
    void directoryTree(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            PathNode root = s.f().analytics().directoryTree(null, null);

            assertTrue(root.totalFiles() >= 4, "tree must contain the registered files");
            assertTrue(root.totalBytes() > 0);
            assertTrue(root.depth() >= 2, "the sub/ folder must create a level");
            assertTrue(root.nodeCount() > 1, "folders must be present");

            // totals roll up: the root equals the sum of its parts
            int counted = countFiles(root);
            assertEquals(root.totalFiles(), counted, "rollup must match the leaves");

            // scoping by source keeps the same material
            PathNode scoped = s.f().analytics().directoryTree(s.sourceId(), null);
            assertEquals(root.totalFiles(), scoped.totalFiles());

            // scoping by a source with nothing yields an empty tree, not an error
            int other = s.f().sources().createSource("Empty", "DE", "none", 0.1);
            assertEquals(0, s.f().analytics().directoryTree(other, null).totalFiles());
        }
    }

    private static int countFiles(PathNode n) {
        int c = n.files().size();
        for (PathNode f : n.folders()) {
            c += countFiles(f);
        }
        return c;
    }

    @Test
    @DisplayName("Archives view exposes the engine's container nesting")
    void archiveTree(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            PathNode root = s.f().analytics().archiveTree();

            assertTrue(root.totalFiles() > 0, "elements must appear in the container tree");
            // the ZIP must have produced a container node with a child
            boolean hasContainer = root.folders().stream().anyMatch(PathNode::isContainer);
            assertTrue(hasContainer, "the ingested archive must appear as a container");
            assertTrue(root.depth() >= 2, "a container adds a level");
        }
    }

    @Test
    @DisplayName("Comprehensive dashboard filters narrow the counts consistently")
    void combinedFilters(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);

            var all = s.f().analytics().filteredCounts(null, null, null, null);
            assertTrue(all.files() >= 4);

            var bySource = s.f().analytics()
                    .filteredCounts(s.sourceId(), null, null, null);
            assertEquals(all.files(), bySource.files(), "everything came from one source");

            var byCategory = s.f().analytics()
                    .filteredCounts(null, null, s.categoryId(), null);
            assertEquals(2, byCategory.files(), "only two files were classified");

            var byType = s.f().analytics().filteredCounts(null, null, null, "txt");
            assertTrue(byType.files() >= 3 && byType.files() < all.files(),
                    "type filter must narrow the set");

            var impossible = s.f().analytics()
                    .filteredCounts(999, null, null, null);
            assertEquals(0, impossible.files(), "an unmatched filter yields zero, not an error");
        }
    }

    @Test
    @DisplayName("Review progress reports the read/unread split")
    void reviewProgress(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            var before = s.f().analytics().reviewProgress();
            assertTrue(before.getOrDefault("Unread", 0) > 0);

            PathDto p = s.f().contents().getPaths().results().get(0);
            s.f().contents().setPathStatus(p.id(), "Read");

            var after = s.f().analytics().reviewProgress();
            assertEquals(1, after.getOrDefault("Read", 0));
        }
    }

    // ===================================================== system destinations

    @Test
    @DisplayName("Error dashboard surfaces engine failures with causes")
    void errorReport(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("broken");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("fine.txt"), "readable text", StandardCharsets.UTF_8);
        // a file whose extension promises a format its bytes do not honour
        Files.write(ev.resolve("corrupt.docx"), new byte[]{0x50, 0x4b, 0x03, 0x04, 0, 0, 1});

        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int src = f.sources().createSource("S", "NL", "custodian", 0.5);
            int asp = f.aspects().createAspect("A", 0.5);
            f.processing("S", "A").processFolder(ev.toString());
            f.contents().registerIngestedItems(src, asp);

            ErrorReport r = f.analytics().errorReport();
            assertNotNull(r);
            assertFalse(r.byStatus().isEmpty(), "the failing file must be reported");
            assertFalse(r.entries().isEmpty());
            assertEquals(r.entries().size(), r.total());
            assertFalse(r.isClean());

            ErrorReport.Entry e = r.entries().get(0);
            assertNotNull(e.itemId());
            assertNotNull(e.status());
            assertNotNull(e.cause(), "every failure must carry a cause");
            assertFalse(r.byCause().isEmpty(), "causes must be grouped for patterns");
        }
    }

    @Test
    @DisplayName("A clean case reports no errors rather than failing")
    void errorReportClean(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            ErrorReport r = s.f().analytics().errorReport();
            assertTrue(r.isClean(), "the seeded corpus processes cleanly");
            assertEquals(0, r.total());
        }
    }

    // ================================================================ editing

    @Test
    @DisplayName("Source edit persists and rejects a duplicate name")
    void editSource(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int a = f.sources().createSource("Acme", "NL", "custodian", 0.8);
            int b = f.sources().createSource("Beta", "DE", "witness", 0.4);

            assertTrue(f.sources().updateSource(a,
                    new SourceDraft("Acme Renamed", "BE", "vendor", 0.95)
                            .city("Brussels").accessStatus("Restricted")));

            var updated = f.sources().getSource(a);
            assertEquals("Acme Renamed", updated.name());
            assertEquals("BE", updated.country());
            assertEquals("vendor", updated.job());
            assertEquals(0.95, updated.importance(), 1e-9);
            assertEquals("Brussels", updated.city());
            assertEquals("Restricted", updated.accessStatus());

            // renaming onto an existing name is a conflict
            FacadeException e = assertThrows(FacadeException.class,
                    () -> f.sources().updateSource(a,
                            new SourceDraft("Beta", "NL", "j", 0.5)));
            assertEquals(FacadeException.Kind.CONFLICT, e.kind());

            // keeping its own name is fine
            assertTrue(f.sources().updateSource(a,
                    new SourceDraft("Acme Renamed", "NL", "vendor", 0.9)));

            assertThrows(FacadeException.class, () -> f.sources().updateSource(9999,
                    new SourceDraft("X", "NL", "j", 0.5)));
            assertThrows(FacadeException.class, () -> f.sources().updateSource(b,
                    new SourceDraft("", "NL", "j", 0.5)));
        }
    }

    @Test
    @DisplayName("Aspect edit persists and rejects a duplicate name")
    void editAspect(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int a = f.aspects().createAspect("Plaintiff", 0.9);
            f.aspects().createAspect("Defendant", 0.5);

            assertTrue(f.aspects().updateAspect(a, "Claimant", 0.75,
                    LocalDate.of(2024, 6, 1)));
            var updated = f.aspects().getAspect(a);
            assertEquals("Claimant", updated.name());
            assertEquals(0.75, updated.importance(), 1e-9);
            assertEquals(LocalDate.of(2024, 6, 1), updated.dateCreation());

            FacadeException e = assertThrows(FacadeException.class,
                    () -> f.aspects().updateAspect(a, "Defendant", 0.5, null));
            assertEquals(FacadeException.Kind.CONFLICT, e.kind());

            assertThrows(FacadeException.class,
                    () -> f.aspects().updateAspect(a, "X", 5.0, null));
        }
    }

    // ============================================== engine capability retained

    @Test
    @DisplayName("Engine capabilities the reference lacks are still available")
    void engineCapabilitiesRetained(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);

            // forensic hashing
            PathDto p = s.f().contents().getPaths().results().get(0);
            assertNotNull(p.hashValue(), "SHA-256 recorded by the engine");

            // container model
            assertTrue(s.f().analytics().archiveTree().depth() >= 2);

            // duplicate detection
            assertNotNull(c.duplicateClusters());

            // query grammar beyond plain terms
            assertTrue(s.f().search().search(
                    com.aegis.fdx.facade.SearchCriteria.of("consult*")).totalCount() > 0,
                    "wildcard search still works");

            // audit trail
            c.auditSearch("consulting", 4, "tester");
            assertFalse(c.db().auditLog(10).isEmpty());
        }
    }
}
