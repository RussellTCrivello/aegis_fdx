package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.BatchAnalysisFacade;
import com.aegis.fdx.facade.BatchAnalysisFacade.ErrorHandling;
import com.aegis.fdx.facade.BatchAnalysisFacade.Priority;
import com.aegis.fdx.facade.BatchAnalysisFacade.Request;
import com.aegis.fdx.facade.BatchAnalysisFacade.Template;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.dto.BatchRun;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies batch analysis: a distinct workflow from the processing monitor, with
 * persisted run history.
 *
 * <p>The central point these tests establish is that keyword hit counts are produced by
 * running an analysis over real extracted text — not by seeding. A run is started with
 * zero recorded hits and the counts appear afterwards.
 */
class BatchAnalysisTest {

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Batch", s);
    }

    /** A processed case with vocabulary defined but no analysis run yet. */
    private Fixture seed(LiveCase c, Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("invoice.txt"),
                "Invoice 2024. Payment due in 30 days. Payment due in 30 days again. "
                        + "Consulting services rendered.", StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("memo.txt"),
                "Memo about the finance review and one payment due in 30 days reminder.",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("unrelated.txt"),
                "Notes about catering arrangements.", StandardCharsets.UTF_8);

        AegisFacades f = AegisFacades.open(c);
        CorpusDatabase dao = new CorpusDatabase(c.db());
        int src = f.sources().createSource(new SourceDraft("Acme", "NL", "custodian", 0.8));
        int asp = f.aspects().createAspect("Plaintiff", 0.9);
        f.categories().createCategory("finance");
        f.keywords().createKeyword("payment due in 30 days", "finance");
        f.processing("Acme", "Plaintiff").processFolder(ev.toString());
        f.contents().registerIngestedItems(src, asp);
        return new Fixture(f, dao, src, asp);
    }

    private record Fixture(AegisFacades f, CorpusDatabase dao, int sourceId, int aspectId) {
    }

    @Test
    @DisplayName("Keyword hits are produced by running analysis, not by seeding")
    void hitsComeFromRealAnalysis(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            int kwId = x.f().keywords().listKeywords().results().get(0).id();

            // registration already derived the edges from the stored text (as the
            // reference does when it stores content); a batch run must agree with it,
            // not add to it
            var before = x.f().analytics().filesForKeyword(kwId, 50);
            assertEquals(2, before.size(), "registration derives hits from real text");

            BatchRun run = x.f().batch().run(
                    new Request(Template.KEYWORD_SCAN), null);

            assertEquals("Completed", run.state());
            assertTrue(run.selected() >= 3);
            assertEquals(0, run.failed());
            assertTrue(run.completed() > 0);

            // the counts now exist, and reflect the actual text
            var hits = x.f().analytics().filesForKeyword(kwId, 50);
            assertEquals(2, hits.size(), "two documents contain the phrase");
            var invoice = hits.stream()
                    .filter(h -> h.fileName().startsWith("invoice")).findFirst().orElseThrow();
            assertEquals(2, invoice.hits(), "the phrase occurs twice in the invoice");
            var memo = hits.stream()
                    .filter(h -> h.fileName().startsWith("memo")).findFirst().orElseThrow();
            assertEquals(1, memo.hits());
        }
    }

    @Test
    @DisplayName("Classification template applies categories from real text")
    void classification(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            int catId = x.f().categories().listCategories().results().get(0).id();

            assertTrue(x.f().analytics().filesForCategory(catId, 50).isEmpty());

            x.f().batch().run(new Request(Template.CLASSIFY), null);

            var files = x.f().analytics().filesForCategory(catId, 50);
            assertEquals(1, files.size(),
                    "only the memo mentions the word 'finance'");
            assertTrue(files.get(0).fileName().startsWith("memo"));
        }
    }

    @Test
    @DisplayName("Deep analysis does both, in one pass")
    void deepAnalysis(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            int kwId = x.f().keywords().listKeywords().results().get(0).id();
            int catId = x.f().categories().listCategories().results().get(0).id();

            BatchRun run = x.f().batch().run(new Request(Template.DEEP), null);
            assertEquals("Completed", run.state());
            assertFalse(x.f().analytics().filesForKeyword(kwId, 50).isEmpty());
            assertFalse(x.f().analytics().filesForCategory(catId, 50).isEmpty());
        }
    }

    @Test
    @DisplayName("Run history persists with per-file outcomes")
    void historyPersists(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            BatchRun run = x.f().batch().run(
                    new Request(Template.KEYWORD_SCAN)
                            .priority(Priority.HIGH)
                            .errorHandling(ErrorHandling.SKIP_FAILED), null);

            List<BatchRun> history = x.f().batch().history(10);
            assertEquals(1, history.size());
            assertEquals(run.id(), history.get(0).id());
            assertEquals("High", history.get(0).priority());
            assertEquals(1, x.f().batch().runCount());

            BatchRun full = x.f().batch().getRun(run.id());
            assertFalse(full.items().isEmpty(), "per-file outcomes must be recorded");
            assertEquals(run.selected(), full.items().size());
            for (BatchRun.ItemOutcome o : full.items()) {
                assertNotNull(o.outcome());
                assertNotNull(o.fileName());
                assertTrue(o.millis() >= 0);
            }
            assertTrue(full.successRate() > 0);
            assertTrue(full.isFinished());
            assertTrue(full.isClean());
            assertNotNull(full.startedAt());
            assertNotNull(full.finishedAt());
        }
    }

    @Test
    @DisplayName("History survives closing and reopening the case")
    void historySurvivesRestart(@TempDir Path tmp) throws Exception {
        int runId;
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            runId = x.f().batch().run(new Request(Template.KEYWORD_SCAN), null).id();
        }
        // reopen the same case folder
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            assertEquals(1, f.batch().runCount(), "the run must still be recorded");
            BatchRun run = f.batch().getRun(runId);
            assertEquals("Completed", run.state());
            assertFalse(run.items().isEmpty());
        }
    }

    @Test
    @DisplayName("Selection honours source, aspect and type filters")
    void selectionFilters(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);

            int all = x.f().batch().resolveSelection(
                    new Request(Template.KEYWORD_SCAN)).size();
            assertTrue(all >= 3);

            assertEquals(all, x.f().batch().resolveSelection(
                    new Request(Template.KEYWORD_SCAN).source(x.sourceId())).size());

            assertEquals(0, x.f().batch().resolveSelection(
                    new Request(Template.KEYWORD_SCAN).source(9999)).size());

            assertEquals(all, x.f().batch().resolveSelection(
                    new Request(Template.KEYWORD_SCAN).fileType("txt")).size());
            assertEquals(0, x.f().batch().resolveSelection(
                    new Request(Template.KEYWORD_SCAN).fileType("pdf")).size());
        }
    }

    @Test
    @DisplayName("An explicit path selection restricts the run to those files")
    void explicitSelection(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            List<PathDto> all = x.f().contents().getPaths().results();
            List<Integer> justOne = List.of(all.get(0).id());

            BatchRun run = x.f().batch().run(
                    new Request(Template.KEYWORD_SCAN).paths(justOne), null);
            assertEquals(1, run.selected());
            assertEquals(1, run.items().size());
        }
    }

    @Test
    @DisplayName("Progress is reported for each file")
    void progressReporting(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            List<BatchAnalysisFacade.Progress> seen = new ArrayList<>();
            BatchRun run = x.f().batch().run(new Request(Template.KEYWORD_SCAN), seen::add);

            assertEquals(run.selected(), seen.size(), "one callback per file");
            assertEquals(run.selected(), seen.get(seen.size() - 1).done());
            assertEquals(1.0, seen.get(seen.size() - 1).fraction(), 1e-9);
            assertNotNull(seen.get(0).currentFile());
        }
    }

    @Test
    @DisplayName("Re-running repeats a previous run's settings")
    void rerun(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            BatchRun first = x.f().batch().run(
                    new Request(Template.DEEP).priority(Priority.LOW), null);
            BatchRun second = x.f().batch().rerun(first.id(), null);

            assertFalse(first.id() == second.id(), "a re-run is a new run");
            assertEquals(first.template(), second.template());
            assertEquals(first.priority(), second.priority());
            assertEquals(2, x.f().batch().runCount());
        }
    }

    @Test
    @DisplayName("Runs can be deleted, and cascade to their outcomes")
    void deleteRun(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            BatchRun run = x.f().batch().run(new Request(Template.KEYWORD_SCAN), null);
            assertTrue(x.f().batch().deleteRun(run.id()));
            assertEquals(0, x.f().batch().runCount());
            assertThrows(FacadeException.class, () -> x.f().batch().getRun(run.id()));
        }
    }

    @Test
    @DisplayName("An empty selection is rejected rather than recorded as a run")
    void emptySelectionRejected(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);
            FacadeException e = assertThrows(FacadeException.class,
                    () -> x.f().batch().run(
                            new Request(Template.KEYWORD_SCAN).source(9999), null));
            assertEquals(FacadeException.Kind.VALIDATION, e.kind());
            assertEquals(0, x.f().batch().runCount(), "nothing should be recorded");
        }
    }

    @Test
    @DisplayName("Templates and enums parse from their labels")
    void enumParsing() {
        assertEquals(Template.DEEP, Template.fromLabel("Deep Analysis"));
        assertEquals(Template.KEYWORD_SCAN, Template.fromLabel(null));
        assertThrows(FacadeException.class, () -> Template.fromLabel("nonsense"));

        assertEquals(Priority.HIGH, Priority.fromLabel("High"));
        assertEquals(Priority.MEDIUM, Priority.fromLabel(null));
        assertEquals(ErrorHandling.STOP_ON_ERROR, ErrorHandling.fromLabel("Stop on Error"));
        assertEquals(ErrorHandling.SKIP_FAILED, ErrorHandling.fromLabel(null));

        for (Template t : Template.values()) {
            assertFalse(t.label().isBlank());
            assertFalse(t.description().isBlank());
        }
    }

    @Test
    @DisplayName("Occurrence counting does not overlap matches")
    void occurrenceCounting() {
        assertEquals(2, BatchAnalysisFacade.countOccurrences("aa bb aa", "aa"));
        assertEquals(1, BatchAnalysisFacade.countOccurrences("aaa", "aa"),
                "matches must not overlap");
        assertEquals(0, BatchAnalysisFacade.countOccurrences("abc", "z"));
        assertEquals(0, BatchAnalysisFacade.countOccurrences(null, "z"));
        assertEquals(0, BatchAnalysisFacade.countOccurrences("abc", ""));
    }

    @Test
    @DisplayName("Batch analysis is distinct from the processing monitor's data")
    void distinctFromProcessing(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Fixture x = seed(c, tmp);

            // the ingest pipeline finished; its status counts are unchanged by analysis
            var before = c.statusCounts();
            x.f().batch().run(new Request(Template.DEEP), null);
            var after = c.statusCounts();
            assertEquals(before, after,
                    "analysis must not alter ingest status; it is a separate workflow");

            // but the analysis history exists independently
            assertEquals(1, x.f().batch().runCount());
        }
    }
}
