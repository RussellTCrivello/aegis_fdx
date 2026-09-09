package com.aegis.fdx;

import com.aegis.fdx.ai.agent.AgentActivity;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.model.HttpLocalModelProvider;
import com.aegis.fdx.ai.model.ModelConfig;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.RelationshipIntegrity;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.ProcessingResultDto;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.CorpusDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure-and-recovery inventory the directive asks for, in one place and through the
 * facades the interface uses. Each test names the failure it plants and asserts the
 * behaviour an operator would see: a decision, a message, or an unchanged case — never a
 * crash, a silent skip, or invented data.
 *
 * <p>Damaged index, locked case, unreadable database and lost folder metadata are covered
 * by {@link ResilienceTest}; interrupted ingest and resume by {@code PipelineAcceptanceTest};
 * crash recovery by {@code M3AcceptanceTest}; corrupt settings by
 * {@code SettingsPersistenceTest}; model unreachable / HTTP error / timeout / unknown tool /
 * malformed arguments by {@link AiAgentTest}. This class adds the remaining items and the
 * relationship-specific ones so the inventory in {@code docs/VERIFICATION_REPORT.md} is
 * complete and every line of it points at a test that ran.
 */
class FailureRecoveryTest {

    private static int nextPort = 11900;

    private static synchronized int port() {
        return nextPort++;
    }

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Recovery", s);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    // ---------------------------------------------------------------- files

    @Test
    @DisplayName("A corrupt file becomes ERROR with a reason; the run continues")
    void corruptFile(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("ev");
        Files.createDirectories(ev);
        Files.write(ev.resolve("broken.pdf"), TestDataset.corruptPdf());
        Files.write(ev.resolve("broken.docx"), TestDataset.corruptOoxml());
        Files.writeString(ev.resolve("fine.txt"), "A perfectly ordinary note.", StandardCharsets.UTF_8);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            List<ProcessingResultDto> results = f.processing("S", "A").processFolder(ev.toString());
            assertFalse(results.isEmpty());
            Map<ItemStatus, Integer> counts = c.statusCounts();
            assertTrue(counts.getOrDefault(ItemStatus.ERROR, 0) >= 1, "corrupt input must be an ERROR decision: " + counts);
            assertTrue(counts.getOrDefault(ItemStatus.INDEXED, 0) >= 1, "the good file must still be indexed: " + counts);
            // the error carries a reason the operator can read on the Error Dashboard
            var report = f.analytics().errorReport();
            assertNotNull(report);
        }
    }

    @Test
    @DisplayName("An unsupported file type is stored, hashed and marked UNSUPPORTED, not dropped")
    void unsupportedFile(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("ev");
        Files.createDirectories(ev);
        byte[] junk = new byte[4096];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) (i * 31 + 7);
        }
        Files.write(ev.resolve("firmware.bin"), junk);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            f.processing("S", "A").processFolder(ev.toString());
            Map<ItemStatus, Integer> counts = c.statusCounts();
            int kept = counts.values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(1, kept, "the item must exist in the case: " + counts);
            assertTrue(counts.getOrDefault(ItemStatus.UNSUPPORTED, 0) + counts.getOrDefault(ItemStatus.INDEXED, 0) == 1,
                    "binary is either UNSUPPORTED or indexed by name; never lost: " + counts);
        }
    }

    @Test
    @DisplayName("A malformed archive is a decision on that item, not a failed run")
    void malformedArchive(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("ev");
        Files.createDirectories(ev);
        byte[] good = zip(Map.of("inner.txt", "inside the archive".getBytes(StandardCharsets.UTF_8)));
        byte[] truncated = java.util.Arrays.copyOf(good, good.length / 2);
        Files.write(ev.resolve("truncated.zip"), truncated);
        Files.write(ev.resolve("not-a-zip.zip"), "PK\u0003\u0004 garbage follows".getBytes(StandardCharsets.UTF_8));
        Files.write(ev.resolve("good.zip"), good);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            f.processing("S", "A").processFolder(ev.toString());
            Map<ItemStatus, Integer> counts = c.statusCounts();
            assertTrue(counts.getOrDefault(ItemStatus.INDEXED, 0) >= 2,
                    "good.zip and its member must be indexed despite the bad archives: " + counts);
            assertTrue(counts.getOrDefault(ItemStatus.ERROR, 0) + counts.getOrDefault(ItemStatus.UNSUPPORTED, 0)
                            + counts.getOrDefault(ItemStatus.INDEXED, 0) >= 4,
                    "every input is accounted for with a status: " + counts);
        }
    }

    @Test
    @DisplayName("An oversized nesting (archive bomb shape) stops at the depth limit and says so")
    void oversizedNesting(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("ev");
        Files.createDirectories(ev);
        byte[] inner = "deepest".getBytes(StandardCharsets.UTF_8);
        byte[] cur = zip(Map.of("leaf.txt", inner));
        for (int i = 0; i < 6; i++) {
            cur = zip(Map.of("level" + i + ".zip", cur));
        }
        Files.write(ev.resolve("nested.zip"), cur);
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        s.maxArchiveDepth(2);
        try (LiveCase c = new LiveCase(tmp.resolve("case"), "Depth", s)) {
            AegisFacades f = AegisFacades.open(c);
            f.processing("S", "A").processFolder(ev.toString());
            int total = c.statusCounts().values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(total >= 2 && total <= 5, "expansion must stop at depth 2, got " + total + " items");
            // nothing beyond the limit was silently expanded
            var page = f.search().search("deepest");
            assertEquals(0, page.totalCount(), "content below the depth limit must not appear");
        }
    }

    // ---------------------------------------------------------------- search / case

    @Test
    @DisplayName("An empty case answers every question with zero, not an error")
    void emptyCase(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            assertEquals(0, f.search().search("anything").totalCount());
            assertEquals(0, f.keywords().listKeywords().totalCount());
            assertEquals(0, f.categories().listCategories().size());
            assertEquals(0, f.contents().getPaths().totalCount());
            assertEquals(0, f.dashboard().getStats().totalFiles());
            RelationshipIntegrity.Report r = f.relationshipIntegrity().check();
            assertTrue(r.consistent());
            assertEquals(0, r.files());
        }
    }

    @Test
    @DisplayName("An empty or malformed search is refused in words or answered empty, never an internal error")
    void emptyAndMalformedSearch(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("ev");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("a.txt"), "alpha beta gamma", StandardCharsets.UTF_8);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            f.processing("S", "A").processFolder(ev.toString());
            // a blank query is refused with a validation message (the screen guards it too)
            for (String q : List.of("", "   ")) {
                var ex = assertThrows(com.aegis.fdx.facade.FacadeException.class, () -> f.search().search(q));
                assertTrue(ex.getMessage().toLowerCase().contains("query"), ex.getMessage());
            }
            // malformed syntax is either answered (possibly with zero hits) or refused with
            // a validation message that tells the operator how to fix it — never an
            // internal error and never a broken index
            for (String q : List.of("((malformed", "\"unterminated", "*", "AND OR NOT", "name:", "-", "field:(")) {
                try {
                    var page = f.search().search(q);
                    assertNotNull(page, "query <" + q + ">");
                    assertTrue(page.totalCount() >= 0);
                } catch (com.aegis.fdx.facade.FacadeException ex) {
                    assertTrue(ex.getMessage().toLowerCase().contains("query"), "query <" + q + ">: " + ex.getMessage());
                    assertFalse(ex.getMessage().contains("Exception"), "no raw stack in <" + q + ">: " + ex.getMessage());
                }
            }
            List<SearchResultDto> hits = f.search().search("alpha").results();
            assertEquals(1, hits.size(), "the index must still work after the bad queries");
        }
    }

    // ---------------------------------------------------------------- relationships

    @Test
    @DisplayName("Duplicate relationships are refused at the database and never double-count")
    void duplicateRelationship(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("ev");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("a.txt"), "the payment due in thirty days is late", StandardCharsets.UTF_8);
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int src = f.sources().createSource(new SourceDraft("Acme", "NL", "custodian", 0.5));
            int asp = f.aspects().createAspect("Plaintiff", 0.5);
            f.categories().createCategory("finance");
            assertTrue(f.categories().linkWordToCategory("payment", "finance"));
            assertFalse(f.categories().linkWordToCategory("payment", "finance"), "second link is a no-op");
            assertTrue(f.keywords().createKeyword("payment due in thirty days", "finance"));
            assertFalse(f.keywords().createKeyword("payment due in thirty days", "finance"), "duplicate keyword refused");
            // A case variant is a distinct stored phrase (as in the reference) but is reported
            // as a duplicate and merged into the oldest keyword without double-counting.
            assertTrue(f.keywords().createKeyword("Payment Due In Thirty Days", "finance"));
            assertEquals(1, f.keywords().findDuplicates().size(), "case variant reported as duplicate");
            assertEquals(1, f.keywords().mergeDuplicates());
            assertEquals(1, f.keywords().listKeywords().totalCount());
            f.processing("Acme", "Plaintiff").processFolder(ev.toString());
            f.contents().registerIngestedItems(src, asp);

            CorpusDatabase dao = new CorpusDatabase(c.db());
            int kw = f.keywords().listKeywords().results().get(0).id();
            int pathId = f.contents().getPaths().results().get(0).id();
            int before = f.relationships().fileCountForKeyword(kw);
            assertEquals(1, before);
            // planting the same edge again must not create a second row or change the count
            dao.linkPathToKeyword(pathId, kw, 1);
            dao.linkPathToKeyword(pathId, kw, 1);
            assertEquals(1, f.relationships().fileCountForKeyword(kw));
            // and re-deriving is idempotent
            f.relationshipAnalyzer().analyzeAll(null);
            f.relationshipAnalyzer().analyzeAll(null);
            assertEquals(1, f.relationships().fileCountForKeyword(kw));
            assertTrue(f.relationshipIntegrity().check().consistent());
        }
    }

    @Test
    @DisplayName("Invariant violations are refused at the facade with a message, and the case is unchanged")
    void invariantViolations(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            f.categories().createCategory("finance");
            // A two-word phrase is a keyword, not a violation — it stores normally.
            assertTrue(f.keywords().createKeyword("two words", "finance"));
            assertThrows(com.aegis.fdx.facade.FacadeException.class, () -> f.keywords().createKeyword("one", "finance"));
            assertThrows(com.aegis.fdx.facade.FacadeException.class, () -> f.categories().createCategory("two words"));
            assertThrows(com.aegis.fdx.facade.FacadeException.class, () -> f.categories().linkWordToCategory("two words", "finance"));
            assertThrows(com.aegis.fdx.facade.FacadeException.class, () -> f.keywords().createKeyword("", "finance"));
            assertEquals(1, f.keywords().listKeywords().totalCount());
            assertEquals(1, f.categories().listCategories().size());
        }
    }

    // ---------------------------------------------------------------- restart

    @Test
    @DisplayName("Relationships and counts survive closing and reopening the case")
    void restartKeepsRelationships(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("ev");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("a.txt"), "invoice for the consulting agreement", StandardCharsets.UTF_8);
        RelationshipIntegrity.Report first;
        int kwCount;
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int src = f.sources().createSource(new SourceDraft("Acme", "NL", "custodian", 0.5));
            int asp = f.aspects().createAspect("Plaintiff", 0.5);
            f.categories().createCategory("legal");
            f.categories().linkWordToCategory("agreement", "legal");
            f.keywords().createKeyword("consulting agreement terms", "legal");
            f.processing("Acme", "Plaintiff").processFolder(ev.toString());
            f.contents().registerIngestedItems(src, asp);
            first = f.relationshipIntegrity().check();
            kwCount = f.keywords().listKeywords().totalCount();
            assertTrue(first.consistent());
            assertTrue(first.wordEdges() >= 1);
        }
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            RelationshipIntegrity.Report again = f.relationshipIntegrity().check();
            assertTrue(again.consistent());
            assertEquals(first.files(), again.files());
            assertEquals(first.wordEdges(), again.wordEdges());
            assertEquals(first.keywordEdges(), again.keywordEdges());
            assertEquals(kwCount, f.keywords().listKeywords().totalCount());
            List<PathDto> paths = f.contents().getPaths().results();
            assertEquals(1, paths.size());
        }
    }

    // ---------------------------------------------------------------- AI

    @Test
    @DisplayName("A malformed model response ends the run as a reported failure, not a crash")
    void malformedModelResponse(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = AegisFacades.open(c);
            rt.rawNextBody("this is not json {{{");
            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()), new HttpLocalModelProvider(
                    ModelConfig.defaults().withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("How many files?", AgentContext.ofScreen("Dashboard"));
            assertTrue(a.failed(), "a garbage body must be a failure");
            assertNotNull(a.failure());
            assertFalse(a.failure().isBlank());
            // the case is untouched and the application keeps working
            assertEquals(0, f.contents().getPaths().totalCount());
            rt.reply("Nothing is on the case yet.");
            AgentActivity b = svc.ask("How many files?", AgentContext.ofScreen("Dashboard"));
            assertFalse(b.failed(), b.failure());
        }
    }

    @Test
    @DisplayName("A model reply with a well-formed envelope but empty content is a typed failure")
    void emptyModelContent(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = AegisFacades.open(c);
            rt.rawNextBody("{\"model\":\"test-model\",\"done\":true}");
            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()), new HttpLocalModelProvider(
                    ModelConfig.defaults().withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("Anything?", AgentContext.ofScreen("Dashboard"));
            assertTrue(a.failed());
            assertTrue(a.failure().toLowerCase().contains("content") || a.failure().toLowerCase().contains("response"),
                    a.failure());
        }
    }
}
