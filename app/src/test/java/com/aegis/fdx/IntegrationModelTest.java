package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.FileState;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.facade.SortField;
import com.aegis.fdx.facade.SortOrder;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the integration of the five added concepts — Sources, Aspects, Categories,
 * Keywords and Contents — with the existing engine and its database.
 *
 * <p>The point of these tests is that the new concepts are <em>not</em> an isolated
 * store: they live in the case's own database, reference engine rows by foreign key,
 * and can be queried together with them.
 */
class IntegrationModelTest {

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Integration", s);
    }

    private Path evidence(Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("invoice.txt"),
                "Invoice 2024 consulting services, payment due", StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("memo.txt"),
                "Memo regarding the consulting agreement", StandardCharsets.UTF_8);
        return ev;
    }

    // ---------------------------------------------------------------- storage

    @Test
    @DisplayName("Integrated concepts live in the case database, not a separate file")
    void singleDatabase(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            f.sources().createSource("Acme", "NL", "custodian", 0.8);

            // No sibling database file was created next to the case database.
            Path dbDir = c.folder().db();
            try (var listing = Files.list(dbDir)) {
                Set<String> names = listing
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".db"))
                        .collect(java.util.stream.Collectors.toSet());
                assertEquals(Set.of("case.db"), names,
                        "the integrated concepts must not introduce a second database");
            }

            // The tables are reachable on the engine's own connection.
            try (Statement st = c.db().connection().createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table'")) {
                java.util.Set<String> tables = new java.util.HashSet<>();
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                // engine tables
                assertTrue(tables.contains("item"), "forensic item table still present");
                assertTrue(tables.contains("queue"), "forensic queue table still present");
                assertTrue(tables.contains("audit"), "forensic audit table still present");
                // integrated concepts
                for (String t : List.of("source", "aspect", "word", "category", "keyword",
                        "hash", "path", "content", "path_category", "path_keyword")) {
                    assertTrue(tables.contains(t), "missing integrated table: " + t);
                }
            }
        }
    }

    @Test
    @DisplayName("A single transaction spans engine and integrated tables")
    void sharedTransaction(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);

            c.db().begin();
            int id = f.sources().createSource("Rollback Co", "NL", "custodian", 0.5);
            assertTrue(id > 0);
            c.db().rollback();

            // The insert was rolled back by the engine's own transaction control,
            // which is only possible because both share one connection.
            assertTrue(f.sources().listSources().stream()
                    .noneMatch(s -> "Rollback Co".equals(s.name())),
                    "rollback on the case connection must undo the source insert");
        }
    }

    // ---------------------------------------------------------------- linkage

    @Test
    @DisplayName("Registered paths reference real engine elements by foreign key")
    void pathsReferenceElements(@TempDir Path tmp) throws Exception {
        Path ev = evidence(tmp);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int srcId = f.sources().createSource("Acme", "NL", "custodian", 0.8);
            int aspectId = f.aspects().createAspect("Plaintiff", 0.9);

            f.processing("Acme", "Plaintiff").processFolder(ev.toString());
            int registered = f.contents().registerIngestedItems(srcId, aspectId);
            assertTrue(registered >= 2);

            // Every path resolves to an item row through the FK.
            try (Statement st = c.db().connection().createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT COUNT(*) FROM path p
                         JOIN item i ON i.id = p.element_id""")) {
                assertTrue(rs.next());
                assertEquals(registered, rs.getInt(1),
                        "every registered path must join to a real element");
            }

            // And the join is queryable through the DAO.
            CorpusDatabase dao = new CorpusDatabase(c.db());
            var joined = dao.selectPathsWithItemStatus(100, 0);
            assertEquals(registered, joined.size());
            assertNotNull(joined.get(0).str("item_status"),
                    "the element's processing status is visible from the registry");
        }
    }

    @Test
    @DisplayName("Deleting an element cascades to its registry row")
    void cascadeFromEngine(@TempDir Path tmp) throws Exception {
        Path ev = evidence(tmp);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int srcId = f.sources().createSource("Acme", "NL", "custodian", 0.8);
            int aspectId = f.aspects().createAspect("Plaintiff", 0.9);
            f.processing("Acme", "Plaintiff").processFolder(ev.toString());
            f.contents().registerIngestedItems(srcId, aspectId);

            PathDto p = f.contents().getPaths().results().get(0);
            String elementId = p.elementId();
            assertNotNull(elementId);

            try (Statement st = c.db().connection().createStatement()) {
                st.executeUpdate("DELETE FROM item WHERE id = '" + elementId + "'");
            }

            // ON DELETE CASCADE removed the registry row with the element.
            assertThrows(FacadeException.class, () -> f.contents().getPath(p.id()),
                    "registry row should have been removed with its element");
        }
    }

    // ------------------------------------------------------------ relationships

    @Test
    @DisplayName("Sources, aspects, categories, keywords and contents relate to a path")
    void relationshipModel(@TempDir Path tmp) throws Exception {
        Path ev = evidence(tmp);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);

            int srcId = f.sources().createSource(
                    new SourceDraft("Acme Consulting", "NL", "custodian", 0.85)
                            .city("Amsterdam").accessStatus("Full access"));
            int aspectId = f.aspects().createAspect("Plaintiff", 0.9);
            int catId = f.categories().createCategory("finance");
            f.categories().linkWordToCategory("invoice", "finance");
            f.keywords().createKeyword("payment due", "finance");

            f.processing("Acme Consulting", "Plaintiff").processFolder(ev.toString());
            f.contents().registerIngestedItems(srcId, aspectId);

            PathDto p = f.contents().getPaths().results().stream()
                    .filter(x -> x.fileName().startsWith("invoice"))
                    .findFirst().orElseThrow();

            // source + aspect attribution
            assertEquals("Acme Consulting", p.sourceName());
            assertEquals("Plaintiff", p.aspectName());
            assertEquals(srcId, p.sourceId());
            assertEquals(aspectId, p.aspectId());

            // hash + content
            assertNotNull(p.hashValue(), "path carries the element's real hash");
            assertFalse(f.contents().getContentAsText(p.id()).isBlank(),
                    "extracted text is stored as content");

            // category attribution of the path itself
            CorpusDatabase dao = new CorpusDatabase(c.db());
            assertTrue(dao.linkPathToCategory(p.id(), catId));
            assertEquals(1, dao.selectPathCategories(p.id()).size());
            assertEquals("finance", dao.selectPathCategories(p.id()).get(0).str("word"));

            // keyword attribution with a hit count
            int kwId = f.keywords().listKeywords().results().get(0).id();
            assertTrue(dao.linkPathToKeyword(p.id(), kwId, 3));
            var kws = dao.selectPathKeywords(p.id());
            assertEquals(1, kws.size());
            assertEquals(3, kws.get(0).i("hits"));
            assertEquals("finance", kws.get(0).str("category_word"));

            // reverse lookup: which files carry this keyword
            var hits = dao.selectPathsForKeyword(kwId, 10);
            assertEquals(1, hits.size());
            assertEquals(p.id(), hits.get(0).i("id"));

            // aggregate views used by the dashboard
            assertEquals(2, dao.countPathsBySource().get("Acme Consulting"));
            assertEquals(2, dao.countPathsByAspect().get("Plaintiff"));
            assertEquals(1, dao.countPathsByCategory().get("finance"));
        }
    }

    @Test
    @DisplayName("Registry filters by source, aspect and review state")
    void registryFilters(@TempDir Path tmp) throws Exception {
        Path ev = evidence(tmp);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int srcId = f.sources().createSource("Acme", "NL", "custodian", 0.8);
            int aspectId = f.aspects().createAspect("Plaintiff", 0.9);
            f.processing("Acme", "Plaintiff").processFolder(ev.toString());
            int n = f.contents().registerIngestedItems(srcId, aspectId);

            assertEquals(n, f.contents()
                    .getPaths(null, srcId, aspectId, null, 100, 0).totalCount());
            assertEquals(0, f.contents()
                    .getPaths(null, 9999, null, null, 100, 0).results().size());

            PathDto p = f.contents().getPaths().results().get(0);
            assertEquals(FileState.UNREAD.label(), p.fileStatus());
            assertTrue(f.contents().setPathStatus(p.id(), FileState.READ.label()));
            assertEquals(1, f.contents()
                    .getPaths(null, null, null, FileState.READ.label(), 100, 0).totalCount());

            assertThrows(FacadeException.class,
                    () -> f.contents().setPathStatus(p.id(), "Archived"));
        }
    }

    // ------------------------------------------------------------ engine intact

    @Test
    @DisplayName("Engine capabilities still work alongside the integrated concepts")
    void engineStillWorks(@TempDir Path tmp) throws Exception {
        Path ev = evidence(tmp);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int srcId = f.sources().createSource("Acme", "NL", "custodian", 0.8);
            int aspectId = f.aspects().createAspect("Plaintiff", 0.9);
            f.processing("Acme", "Plaintiff").processFolder(ev.toString());
            f.contents().registerIngestedItems(srcId, aspectId);

            // search, statuses, audit and duplicate detection all still function
            assertEquals(2, f.search().search("consulting").totalCount());
            assertTrue(c.indexedCount() >= 2, "Lucene index still populated");
            assertFalse(c.statusCounts().isEmpty(), "engine status counts still available");
            assertNotNull(c.duplicateClusters());

            // The audit log records review actions (not ingest), so exercise one.
            c.auditSearch("consulting", 2, "tester");
            assertFalse(c.db().auditLog(10).isEmpty(),
                    "review actions are still audited alongside the new concepts");

            // criteria-based sorting and paging
            var byName = f.search().search(SearchCriteria.of("consulting")
                    .sortBy(SortField.NAME, SortOrder.ASCENDING).page(0, 1));
            var second = f.search().search(SearchCriteria.of("consulting")
                    .sortBy(SortField.NAME, SortOrder.ASCENDING).page(1, 1));
            assertEquals(1, byName.results().size());
            assertFalse(byName.results().get(0).id().equals(second.results().get(0).id()));
        }
    }

    @Test
    @DisplayName("Aspect is the current name; the former Side API still delegates")
    void deprecatedAliasStillWorks(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int viaAspect = f.aspects().createAspect("Plaintiff", 0.9);

            @SuppressWarnings("deprecation")
            var legacy = f.sides();
            assertEquals(1, legacy.getAllSides().size());
            assertEquals("Plaintiff", legacy.getSideById(viaAspect).name());

            @SuppressWarnings("deprecation")
            int viaSide = legacy.createSide("Defendant", 0.4, LocalDate.now());
            assertEquals("Defendant", f.aspects().getAspect(viaSide).name(),
                    "both names address the same storage");
            assertEquals(2, f.aspects().listAspects().size());
        }
    }

    @Test
    @DisplayName("Source draft carries optional details and validates required ones")
    void sourceDraft(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int id = f.sources().createSource(
                    new SourceDraft("Acme", "NL", "custodian", 0.8)
                            .city("Amsterdam")
                            .description("Primary vendor")
                            .ownership("Corporate")
                            .accessStatus("Full access")
                            .entryDate(LocalDate.of(2024, 3, 18)));

            SourceDto s = f.sources().getSource(id);
            assertEquals("Amsterdam", s.city());
            assertEquals("Primary vendor", s.description());
            assertEquals(LocalDate.of(2024, 3, 18), s.entryDate());

            assertThrows(FacadeException.class, () -> f.sources()
                    .createSource(new SourceDraft("", "NL", "j", 0.5)));
            assertThrows(FacadeException.class, () -> f.sources()
                    .createSource(new SourceDraft("X", "NL", "j", 9.0)));
        }
    }
}
