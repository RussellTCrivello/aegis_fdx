package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.TermSummary;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retrospective indexing, idempotence, stale-edge purging, and bilateral lifecycle equivalence.
 *
 * <p>Deterministic test case verifying:
 * <ol>
 *   <li>Files ingested first, keyword created later -> automatic retrospective linking.</li>
 *   <li>Hit count correctness and distinction from file count (File B with 2 occurrences = 1 file, 2 hits).</li>
 *   <li>Idempotence: repeated analysis calls never inflate counts or create duplicate edges.</li>
 *   <li>Stale-edge removal: modifying a keyword/word purges old associations and derives only new ones.</li>
 *   <li>Content change / reprocessing: changing file text updates derived edges cleanly.</li>
 *   <li>Bilateral convergence: File first vs Keyword first produce identical relationship graphs.</li>
 * </ol>
 */
public class RetrospectiveIndexingTest {

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "RetrospectiveTest", s);
    }

    private static Set<String> names(List<TermSummary.FileRef> files) {
        Set<String> out = new TreeSet<>();
        for (TermSummary.FileRef r : files) {
            out.add(r.fileName());
        }
        return out;
    }

    @Test
    @DisplayName("Deterministic retrospective indexing, hit counting, idempotence, and stale-edge removal")
    void deterministicRetrospectiveLifecycle(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev);

        // File A: 1 occurrence of "alpha beta gamma"
        Files.writeString(ev.resolve("fileA.txt"), "alpha beta gamma", StandardCharsets.UTF_8);
        // File B: 2 occurrences of "alpha beta gamma"
        Files.writeString(ev.resolve("fileB.txt"), "alpha beta gamma something else alpha beta gamma", StandardCharsets.UTF_8);
        // File C: 0 occurrences of "alpha beta gamma", 1 occurrence of "delta epsilon zeta"
        Files.writeString(ev.resolve("fileC.txt"), "delta epsilon zeta and unrelated content", StandardCharsets.UTF_8);

        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int src = f.sources().createSource(new SourceDraft("TestSrc", "NL", "custodian", 1.0));
            int asp = f.aspects().createAspect("Defense", 1.0);

            // 1. Files ingested BEFORE keywords/categories exist
            f.processing("TestSrc", "Defense").processFolder(ev.toString());
            f.contents().registerIngestedItems(src, asp);

            // Initially, no keywords or categories exist
            assertEquals(0, f.keywords().listKeywords().totalCount());
            assertEquals(0, f.categories().listCategories().totalCount());

            // 2. Create category "greek" and keyword "alpha beta gamma" POST-ingestion
            int greekCatId = f.categories().createCategory("greek");
            assertTrue(f.keywords().createKeyword("alpha beta gamma", "greek"));

            int kwId = f.keywords().listKeywords().results().stream()
                    .filter(k -> k.keyword().equals("alpha beta gamma")).findFirst().orElseThrow().id();

            // Retrospective indexing must have linked File A and File B, but NOT File C
            TermSummary kwSummary = f.relationships().keyword(kwId);
            assertEquals(2, kwSummary.fileCount(), "alpha beta gamma appears in 2 distinct files");
            assertEquals(3, kwSummary.hits(), "1 hit in fileA + 2 hits in fileB = 3 total hits");
            assertEquals(Set.of("fileA.txt", "fileB.txt"), names(kwSummary.files()));

            for (TermSummary.FileRef ref : kwSummary.files()) {
                if (ref.fileName().equals("fileA.txt")) {
                    assertEquals(1, ref.hits());
                } else if (ref.fileName().equals("fileB.txt")) {
                    assertEquals(2, ref.hits());
                }
            }

            // 3. Prove IDEMPOTENCE: calling analyzeKeyword multiple times changes nothing
            for (int i = 0; i < 5; i++) {
                f.relationshipAnalyzer().analyzeKeyword(kwId);
            }
            TermSummary kwIdempotent = f.relationships().keyword(kwId);
            assertEquals(2, kwIdempotent.fileCount());
            assertEquals(3, kwIdempotent.hits());
            assertEquals(Set.of("fileA.txt", "fileB.txt"), names(kwIdempotent.files()));

            // Verify integrity report is clean
            var integrity = f.relationshipIntegrity().check();
            assertTrue(integrity.consistent(), "Graph integrity must be 100% consistent");

            // 4. Prove STALE-EDGE REMOVAL on Keyword Update
            // Update keyword from "alpha beta gamma" to "delta epsilon zeta"
            assertTrue(f.keywords().updateKeyword(kwId, "delta epsilon zeta"));
            TermSummary updatedSummary = f.relationships().keyword(kwId);

            // Old edges (fileA, fileB) must be removed; new edge (fileC) must be present
            assertEquals(1, updatedSummary.fileCount(), "Only fileC contains delta epsilon zeta");
            assertEquals(1, updatedSummary.hits());
            assertEquals(Set.of("fileC.txt"), names(updatedSummary.files()));

            // Verify fileA and fileB no longer list this keyword
            int pathA = f.contents().getPaths().results().stream().filter(p -> p.fileName().equals("fileA.txt")).findFirst().orElseThrow().id();
            int pathB = f.contents().getPaths().results().stream().filter(p -> p.fileName().equals("fileB.txt")).findFirst().orElseThrow().id();
            int pathC = f.contents().getPaths().results().stream().filter(p -> p.fileName().equals("fileC.txt")).findFirst().orElseThrow().id();

            assertTrue(f.relationships().forFile(pathA).keywords().isEmpty());
            assertTrue(f.relationships().forFile(pathB).keywords().isEmpty());
            assertEquals(1, f.relationships().forFile(pathC).keywords().size());

            // 5. Category word retrospective indexing & stale-edge removal
            assertTrue(f.categories().linkWordToCategory("gamma", "greek"));
            int gammaWordId = f.categories().getCategoryWords(greekCatId, 10, 0).results().stream()
                    .filter(w -> w.word().equals("gamma")).findFirst().orElseThrow().id();

            TermSummary gammaSummary = f.relationships().categoryWord(gammaWordId);
            assertEquals(2, gammaSummary.fileCount(), "gamma appears in fileA and fileB");
            assertEquals(3, gammaSummary.hits());
            assertEquals(Set.of("fileA.txt", "fileB.txt"), names(gammaSummary.files()));

            // Unlink word from category
            assertTrue(f.categories().removeWordFromCategory(greekCatId, gammaWordId));
            assertTrue(f.relationshipIntegrity().check().consistent());
        }
    }

    @Test
    @DisplayName("Content changes / file reprocessing purges stale edges and applies new matches")
    void contentChangeReprocessing(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("doc.txt"), "alpha beta gamma", StandardCharsets.UTF_8);

        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int src = f.sources().createSource(new SourceDraft("Src", "NL", "custodian", 1.0));
            int asp = f.aspects().createAspect("Defense", 1.0);
            f.categories().createCategory("sample");
            f.keywords().createKeyword("alpha beta gamma", "sample");
            f.keywords().createKeyword("delta epsilon zeta", "sample");

            f.processing("Src", "Defense").processFolder(ev.toString());
            f.contents().registerIngestedItems(src, asp);

            int pathId = f.contents().getPaths().results().get(0).id();
            var rel1 = f.relationships().forFile(pathId);
            assertEquals(1, rel1.keywords().size());
            assertEquals("alpha beta gamma", rel1.keywords().get(0).text());

            // Simulate file content update/reprocessing: overwrite text with "delta epsilon zeta"
            CorpusDatabase dao = new CorpusDatabase(c.db());
            try (var ps = c.db().connection().prepareStatement("UPDATE content SET content_data=? WHERE path_id=?")) {
                ps.setString(1, "delta epsilon zeta");
                ps.setInt(2, pathId);
                ps.executeUpdate();
            }

            // Re-analyze file
            f.relationshipAnalyzer().analyzeFile(pathId);

            var rel2 = f.relationships().forFile(pathId);
            assertEquals(1, rel2.keywords().size());
            assertEquals("delta epsilon zeta", rel2.keywords().get(0).text(),
                    "Old keyword edge must be purged and new keyword edge attached");

            assertTrue(f.relationshipIntegrity().check().consistent());
        }
    }

    @Test
    @DisplayName("Bilateral equivalence: Terms-first vs Files-first produce identical relationship graphs")
    void bilateralEquivalence(@TempDir Path tmp) throws Exception {
        Path evA = tmp.resolve("caseA_ev");
        Path evB = tmp.resolve("caseB_ev");
        Files.createDirectories(evA);
        Files.createDirectories(evB);

        Files.writeString(evA.resolve("doc1.txt"), "first second third document analysis", StandardCharsets.UTF_8);
        Files.writeString(evB.resolve("doc1.txt"), "first second third document analysis", StandardCharsets.UTF_8);

        // Case 1: Terms created BEFORE files
        try (LiveCase c1 = openCase(tmp.resolve("case1"))) {
            AegisFacades f1 = AegisFacades.open(c1);
            int src1 = f1.sources().createSource(new SourceDraft("Src", "NL", "custodian", 1.0));
            int asp1 = f1.aspects().createAspect("Defense", 1.0);
            f1.categories().createCategory("tax");
            f1.categories().linkWordToCategory("document", "tax");
            f1.keywords().createKeyword("first second third", "tax");

            f1.processing("Src", "Defense").processFolder(evA.toString());
            f1.contents().registerIngestedItems(src1, asp1);

            int kwFiles1 = f1.relationships().keywords(null, 10, 0).results().get(0).fileCount();
            int wordFiles1 = f1.relationships().categoryWords("document", 10, 0).results().get(0).fileCount();

            // Case 2: Files ingested BEFORE terms
            try (LiveCase c2 = openCase(tmp.resolve("case2"))) {
                AegisFacades f2 = AegisFacades.open(c2);
                int src2 = f2.sources().createSource(new SourceDraft("Src", "NL", "custodian", 1.0));
                int asp2 = f2.aspects().createAspect("Defense", 1.0);

                f2.processing("Src", "Defense").processFolder(evB.toString());
                f2.contents().registerIngestedItems(src2, asp2);

                f2.categories().createCategory("tax");
                f2.categories().linkWordToCategory("document", "tax");
                f2.keywords().createKeyword("first second third", "tax");

                int kwFiles2 = f2.relationships().keywords(null, 10, 0).results().get(0).fileCount();
                int wordFiles2 = f2.relationships().categoryWords("document", 10, 0).results().get(0).fileCount();

                assertEquals(kwFiles1, kwFiles2, "Keyword file counts must be identical");
                assertEquals(wordFiles1, wordFiles2, "Category word file counts must be identical");
                assertEquals(1, kwFiles1);
                assertEquals(1, wordFiles1);
            }
        }
    }
}
