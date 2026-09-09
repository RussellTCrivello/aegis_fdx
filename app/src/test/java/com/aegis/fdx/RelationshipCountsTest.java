package com.aegis.fdx;

import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §15 / §30: the grouped relationship counts used by the dashboard and the term
 * listings must equal the per-term counts used by the detail screens.
 *
 * <p>The two are computed by different SQL — one grouped query for a listing, one
 * scalar query per term for a detail view — and a listing that disagrees with the
 * detail screen it links to is precisely the defect this suite exists to prevent. The
 * counts must also be counts of <em>distinct files</em>: a keyword occurring forty times
 * in one file relates to one file.
 */
final class RelationshipCountsTest {

    /** A small case: three files, a keyword in two of them, one of those twice over. */
    private record Fixture(CorpusDatabase dao, int keywordId, int categoryId,
                           int wordId, int[] pathIds) { }

    private static Fixture build(CaseDatabase db) throws Exception {
        CorpusDatabase dao = new CorpusDatabase(db);
        int sourceId = dao.insertSource("Volume 1", "GB", "custodian", 1.0, "London",
                "", "", "", "", "", "", LocalDate.now(), null);
        int wordId = dao.insertWord("finance");
        int categoryId = dao.insertCategory(wordId);
        // §14: a keyword is two or more words.
        int keywordId = dao.insertKeyword("financial transaction report", categoryId);

        int[] paths = new int[3];
        for (int i = 0; i < 3; i++) {
            paths[i] = dao.insertPath("file" + i + ".pdf", "/evidence/file" + i + ".pdf",
                    1000L, "pdf", "Unread", LocalDate.now(), LocalDate.now(),
                    null, null, sourceId, null, null);
        }
        return new Fixture(dao, keywordId, categoryId, wordId, paths);
    }

    @Test
    @DisplayName("grouped keyword usage equals the per-keyword count")
    void keywordUsageMatchesPerKeywordCount(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Fixture f = build(db);
            // Forty occurrences in one file, one in another, none in the third.
            f.dao().linkPathToKeyword(f.pathIds()[0], f.keywordId(), 40);
            f.dao().linkPathToKeyword(f.pathIds()[1], f.keywordId(), 1);

            var rows = f.dao().selectKeywordUsage(100, 0);
            assertEquals(1, rows.size());
            var row = rows.get(0);

            assertEquals(2, row.i("files"),
                    "forty occurrences in one file is still one file");
            assertEquals(41, row.i("hits"), "occurrences are summed separately");
            assertEquals(f.dao().countFilesForKeyword(f.keywordId()), row.i("files"),
                    "the listing must agree with the detail screen");
        }
    }

    @Test
    @DisplayName("a keyword with no files is listed with a zero count, not omitted")
    void unusedKeywordIsListed(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Fixture f = build(db);
            var rows = f.dao().selectKeywordUsage(100, 0);
            assertEquals(1, rows.size(), "an unused keyword must still appear");
            assertEquals(0, rows.get(0).i("files"));
            assertEquals(0, rows.get(0).i("hits"));
        }
    }

    @Test
    @DisplayName("counts follow associations as they are added and removed")
    void countsTrackAssociationChanges(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Fixture f = build(db);
            assertEquals(0, files(f, f.keywordId()));

            f.dao().linkPathToKeyword(f.pathIds()[0], f.keywordId(), 3);
            assertEquals(1, files(f, f.keywordId()));

            f.dao().linkPathToKeyword(f.pathIds()[1], f.keywordId(), 3);
            assertEquals(2, files(f, f.keywordId()));

            // Re-linking the same file must not inflate the count.
            f.dao().linkPathToKeyword(f.pathIds()[1], f.keywordId(), 9);
            assertEquals(2, files(f, f.keywordId()), "re-linking is not a new file");

            f.dao().unlinkPathFromKeyword(f.pathIds()[0], f.keywordId());
            assertEquals(1, files(f, f.keywordId()), "removing an association lowers the count");
        }
    }

    @Test
    @DisplayName("category counts unite direct assignment and vocabulary routes without double counting")
    void categoryCountsUnionBothRoutes(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Fixture f = build(db);

            // file0 reached by direct assignment only
            f.dao().linkPathToCategory(f.pathIds()[0], f.categoryId());
            // file1 reached by containing the category's word only
            f.dao().linkPathToWord(f.pathIds()[1], f.wordId(), 5);
            // file2 reached by BOTH routes: it must be counted once, not twice
            f.dao().linkPathToCategory(f.pathIds()[2], f.categoryId());
            f.dao().linkPathToWord(f.pathIds()[2], f.wordId(), 2);

            var rows = f.dao().selectCategoryUsage(100, 0);
            assertEquals(1, rows.size());
            assertEquals(3, rows.get(0).i("files"),
                    "a file reachable by both routes is still one file");
            assertEquals(f.dao().countFilesForCategory(f.categoryId()), rows.get(0).i("files"),
                    "the listing must agree with the detail screen");
        }
    }

    @Test
    @DisplayName("counts are case-wide, not scoped to the caller's page")
    void countsAreGlobal(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Fixture f = build(db);
            for (int p : f.pathIds()) {
                f.dao().linkPathToKeyword(p, f.keywordId(), 1);
            }
            // Asking for a single-row page must not change what the count means.
            var page = f.dao().selectKeywordUsage(1, 0);
            assertEquals(3, page.get(0).i("files"),
                    "a paged listing still reports the case-wide count");
        }
    }

    @Test
    @DisplayName("the relationship is reversible: term to file and file to term agree")
    void relationshipIsBidirectional(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Fixture f = build(db);
            f.dao().linkPathToKeyword(f.pathIds()[0], f.keywordId(), 2);
            f.dao().linkPathToKeyword(f.pathIds()[2], f.keywordId(), 7);

            // term -> files
            Map<Integer, Boolean> forward = new HashMap<>();
            for (var r : f.dao().selectFilesForKeyword(f.keywordId(), 100)) {
                forward.put(r.i("id"), true);
            }
            assertEquals(2, forward.size());
            assertTrue(forward.containsKey(f.pathIds()[0]));
            assertTrue(forward.containsKey(f.pathIds()[2]));

            // file -> terms, for each file the forward direction named
            for (int pathId : forward.keySet()) {
                boolean found = f.dao().selectPathKeywords(pathId).stream()
                        .anyMatch(r -> r.i("id") == f.keywordId());
                assertTrue(found, "file " + pathId + " must name the keyword back");
            }

            // and a file the keyword does not relate to must not name it
            assertTrue(f.dao().selectPathKeywords(f.pathIds()[1]).stream()
                    .noneMatch(r -> r.i("id") == f.keywordId()));
        }
    }

    private static int files(Fixture f, int keywordId) throws Exception {
        for (var r : f.dao().selectKeywordUsage(100, 0)) {
            if (r.i("id") == keywordId) {
                return r.i("files");
            }
        }
        return -1;
    }
}
