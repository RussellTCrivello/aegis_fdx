package com.aegis.fdx;

import com.aegis.fdx.ai.agent.AgentActivity;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.model.HttpLocalModelProvider;
import com.aegis.fdx.ai.model.ModelConfig;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.BatchAnalysisFacade;
import com.aegis.fdx.facade.ExportFacade;
import com.aegis.fdx.facade.FileState;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.facade.SortField;
import com.aegis.fdx.facade.SortOrder;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.dto.BatchRun;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.PreviewDto;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The complete scenario, start to finish, against one real case.
 *
 * <p>Every step operates on the same {@code case.db} and the same engine: create the
 * case, define the five concepts, process material through the real pipeline, register
 * it, search it, analyse it, inspect relationships, export it, restart, and finally ask
 * the local agent — which must answer from records this test itself created.
 */
class EndToEndScenarioTest {

    @Test
    @DisplayName("Full lifecycle: create, process, analyse, search, export, restart, ask")
    void fullScenario(@TempDir Path tmp) throws Exception {
        Path caseRoot = tmp.resolve("case");
        Path evidence = tmp.resolve("evidence");
        Files.createDirectories(evidence);

        // ---------- material, including a nested archive ----------
        Files.writeString(evidence.resolve("invoice-2024.txt"),
                "INVOICE 2024-0417. Acme Consulting BV. Consulting services rendered. "
                        + "Payment due in 30 days. Payment due in 30 days from receipt.",
                StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("agreement.txt"),
                "Master services agreement with Acme Consulting BV covering consulting "
                        + "work. Confidentiality clause applies.", StandardCharsets.UTF_8);
        Files.createDirectories(evidence.resolve("correspondence"));
        Files.writeString(evidence.resolve("correspondence").resolve("note.txt"),
                "Note about the finance review and the consulting engagement.",
                StandardCharsets.UTF_8);
        try (ZipOutputStream z = new ZipOutputStream(
                Files.newOutputStream(evidence.resolve("bundle.zip")))) {
            z.putNextEntry(new ZipEntry("bundled-invoice.txt"));
            z.write("Bundled invoice. Payment due in 30 days.".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }

        int sourceId;
        int aspectId;
        int categoryId;
        int keywordId;
        int totalIndexed;

        // ================= session one =================
        CaseSettings settings = new CaseSettings();
        settings.ocrEnabled(false);
        try (LiveCase c = new LiveCase(caseRoot, "Scenario", settings)) {
            AegisFacades f = AegisFacades.open(c);
            CorpusDatabase dao = new CorpusDatabase(c.db());

            // ---------- 1. the five concepts ----------
            sourceId = f.sources().createSource(
                    new SourceDraft("Acme Consulting BV", "NL", "custodian", 0.85)
                            .city("Amsterdam").accessStatus("Full access"));
            aspectId = f.aspects().createAspect("Plaintiff", 0.9);
            categoryId = f.categories().createCategory("finance");
            f.categories().linkWordToCategory("invoice", "finance");
            f.keywords().createKeyword("payment due in 30 days", "finance");
            keywordId = f.keywords().listKeywords().results().get(0).id();
            assertTrue(sourceId > 0 && aspectId > 0 && categoryId > 0 && keywordId > 0);

            // ---------- 2. process through the real pipeline ----------
            var results = f.processing("Acme Consulting BV", "Plaintiff")
                    .processFolder(evidence.toString());
            assertFalse(results.isEmpty(), "the pipeline must report every file");

            // ---------- 3. extraction, metadata, indexing ----------
            totalIndexed = c.indexedCount();
            assertTrue(totalIndexed >= 4, "material must reach the index");
            assertFalse(c.statusCounts().isEmpty());

            // ---------- 4. register into the relational model ----------
            int registered = f.contents().registerIngestedItems(sourceId, aspectId);
            assertTrue(registered >= 4);
            PathDto sample = f.contents().getPaths().results().get(0);
            assertNotNull(sample.hashValue(), "the engine's SHA-256 must be recorded");
            assertNotNull(sample.elementId(), "the registry links back to the element");
            assertEquals("Acme Consulting BV", sample.sourceName());
            assertEquals("Plaintiff", sample.aspectName());

            // ---------- 5. archive nesting ----------
            var containers = f.analytics().archiveTree();
            assertTrue(containers.folders().stream().anyMatch(n -> n.isContainer()),
                    "the ZIP must appear as a container");

            // ---------- 6. search ----------
            var hits = f.search().search("consulting");
            assertTrue(hits.totalCount() >= 3, "free-text search must find the material");

            var sorted = f.search().search(SearchCriteria.of("consulting")
                    .sortBy(SortField.NAME, SortOrder.ASCENDING).page(0, 2));
            assertEquals(2, sorted.results().size(), "paging must work");

            var typed = f.search().search(SearchCriteria.of("consulting").fileType("txt"));
            assertTrue(typed.totalCount() > 0);

            var scoped = f.search().search(SearchCriteria.of("consulting").source(sourceId));
            assertTrue(scoped.totalCount() > 0, "source filter must resolve");

            // ---------- 7. batch analysis produces real hit counts ----------
            int hitsAtRegistration = f.analytics().filesForKeyword(keywordId, 50).size();
            assertTrue(hitsAtRegistration > 0, "registration derives keyword hits from text");
            BatchRun run = f.batch().run(
                    new BatchAnalysisFacade.Request(BatchAnalysisFacade.Template.DEEP), null);
            assertEquals("Completed", run.state());
            assertTrue(run.completed() > 0);

            var keywordHits = f.analytics().filesForKeyword(keywordId, 50);
            assertFalse(keywordHits.isEmpty(), "analysis must produce keyword hits");
            assertEquals(hitsAtRegistration, keywordHits.size(),
                    "re-analysis must not duplicate or lose relationships");
            int totalHits = keywordHits.stream()
                    .mapToInt(h -> h.hits()).sum();
            assertTrue(totalHits >= 3, "the phrase occurs at least three times overall");

            var classified = f.analytics().filesForCategory(categoryId, 50);
            assertFalse(classified.isEmpty(), "analysis must classify material");

            // ---------- 8. relationships in both directions ----------
            assertFalse(f.analytics().categoriesForSource(sourceId).isEmpty());
            assertFalse(f.analytics().keywordsForSource(sourceId).isEmpty());
            assertFalse(f.analytics().categoriesForAspect(aspectId).isEmpty());

            // ---------- 9. statistics ----------
            var srcStats = f.analytics().sourceStatistics(sourceId);
            assertTrue(srcStats.files() >= 4);
            assertTrue(srcStats.bytes() > 0);
            assertTrue(f.analytics().directoryTree(null, null).totalFiles() >= 4);
            assertTrue(f.analytics().filteredCounts(sourceId, aspectId, null, null).files() > 0);

            // ---------- 10. preview and content ----------
            String elementId = hits.results().get(0).id();
            PreviewDto preview = f.preview().getPreview(elementId);
            assertNotNull(preview.previewType());
            String text = f.contents().getContentAsText(sample.id());
            assertNotNull(text);

            // ---------- 11. review workflow ----------
            assertTrue(f.contents().setPathStatus(sample.id(), FileState.READ.label()));
            assertEquals(1, f.analytics().reviewProgress().getOrDefault("Read", 0));

            // ---------- 12. export ----------
            List<SearchResultDto> rows = hits.results();
            byte[] csv = ExportFacade.exportSearchResultsCsv(rows);
            assertTrue(csv.length > 0);
            assertTrue(new String(csv, StandardCharsets.UTF_8).contains("file_name"));
            byte[] xlsx = ExportFacade.exportSearchResultsExcel(rows);
            assertEquals('P', (char) xlsx[0]);

            // ---------- 13. errors and status ----------
            var errors = f.analytics().errorReport();
            assertNotNull(errors);
            assertEquals(errors.entries().size(), errors.total());

            // ---------- 14. audit ----------
            c.auditSearch("consulting", hits.totalCount(), "scenario");
            assertFalse(c.db().auditLog(10).isEmpty());
        }

        // ================= session two: everything survived =================
        try (LiveCase c = new LiveCase(caseRoot, "Scenario", settings)) {
            AegisFacades f = AegisFacades.open(c);

            assertEquals(totalIndexed, c.indexedCount(), "the index must persist");
            assertEquals("Acme Consulting BV", f.sources().getSource(sourceId).name());
            assertEquals("Plaintiff", f.aspects().getAspect(aspectId).name());
            assertTrue(f.contents().getPaths().totalCount() >= 4);
            assertEquals(1, f.batch().runCount(), "the analysis run must persist");
            assertFalse(f.analytics().filesForKeyword(keywordId, 50).isEmpty(),
                    "analysis results must persist");
            assertTrue(f.search().search("consulting").totalCount() >= 3,
                    "search must still work after a restart");

            // ---------- 15. the local agent, over this same case ----------
            try (FakeLocalRuntime rt = new FakeLocalRuntime(11890).start()) {
                rt.reply("{\"tool\": \"search_items\", \"arguments\": "
                                + "{\"query\": \"consulting\"}}")
                  .reply("{\"tool\": \"list_sources\", \"arguments\": {}}")
                  .reply("{\"tool\": \"get_statistics\", \"arguments\": {}}")
                  .reply("The consulting material on this case came from "
                          + "Acme Consulting BV and is attributed to the Plaintiff aspect.");

                AgentService agent = new AgentService(f, new CorpusDatabase(c.db()),
                        new HttpLocalModelProvider(ModelConfig.defaults()
                                .withEndpoint(rt.endpoint()).withChatModel("test-model")));
                assertTrue(agent.isAvailable());

                AgentActivity a = agent.ask(
                        "What consulting material is on this case and where did it come from?",
                        AgentContext.ofScreen("Dashboard"));

                assertFalse(a.failed(), a.failure());
                assertEquals(3, a.steps().size(), "the agent must use several tools");
                assertTrue(a.isGrounded(), "the answer must rest on real records");
                assertFalse(a.evidence().isEmpty());

                // every cited record must actually exist in this case
                for (var ev : a.evidence()) {
                    if ("item".equals(ev.kind())) {
                        assertNotNull(c.byId(ev.id()),
                                "cited item " + ev.id() + " must exist");
                    }
                    if ("source".equals(ev.kind())) {
                        assertNotNull(f.sources().getSource(Integer.parseInt(ev.id())),
                                "cited source " + ev.id() + " must exist");
                    }
                }
                assertTrue(a.toTrace().contains("search_items"));
            }
        }
    }
}
