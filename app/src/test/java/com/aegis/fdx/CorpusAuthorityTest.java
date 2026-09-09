package com.aegis.fdx;

import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §22. Resolves, by execution rather than by reading, whether {@code CorpusDatabase} is
 * a second authoritative database.
 *
 * <p>The architecture states there is exactly one authoritative case database. If the
 * corpus tables were a second persistence layer they would show it in three observable
 * ways: their own file on disk, their own connection, or case state that exists only
 * there. These tests look for all three.
 *
 * <p>They also pin the term semantics (§16) at the layer that actually persists them,
 * and record where that enforcement currently stops.
 */
class CorpusAuthorityTest {

    private interface Body {
        void run(CaseDatabase db, CorpusDatabase corpus) throws Exception;
    }

    private void withCase(Body body) throws Exception {
        Path dir = Files.createTempDirectory("corpus-authority");
        Path file = dir.resolve("case.db");
        try (CaseDatabase db = new CaseDatabase(file)) {
            body.run(db, new CorpusDatabase(db));
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }

    // ---- is it a second database? -------------------------------------------

    @Test
    void corpusSharesTheCaseConnectionRatherThanOpeningItsOwn() throws Exception {
        withCase((db, corpus) -> {
            // The only honest test of "same connection" is a transaction: work done
            // through one handle must be visible to the other before any commit, and
            // must vanish together on rollback.
            boolean auto = db.connection().getAutoCommit();
            db.connection().setAutoCommit(false);
            try {
                int wordId = corpus.insertWord("uncommitted");
                assertNotNull(corpus.findWordId("uncommitted"));

                try (Statement st = db.connection().createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT COUNT(*) FROM word WHERE id=" + wordId)) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1),
                            "CaseDatabase cannot see CorpusDatabase's uncommitted write, "
                                    + "so they are not the same connection");
                }

                db.connection().rollback();
                assertEquals(null, corpus.findWordId("uncommitted"),
                        "rollback on the case connection did not undo the corpus write");
            } finally {
                db.connection().setAutoCommit(auto);
            }
        });
    }

    @Test
    void noSecondDatabaseFileIsEverCreated() throws Exception {
        Path dir = Files.createTempDirectory("corpus-single-file");
        Path file = dir.resolve("case.db");
        try (CaseDatabase db = new CaseDatabase(file)) {
            CorpusDatabase corpus = new CorpusDatabase(db);
            int wordId = corpus.insertWord("evidence");
            corpus.insertCategory(wordId);
            corpus.insertSource("acme", "NL", "vendor", 1.0, "Amsterdam",
                    null, null, null, null, null, null, null, null);

            Set<String> dbFiles = new HashSet<>();
            try (var list = Files.list(dir)) {
                list.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".db") || n.endsWith(".sqlite")
                                || n.endsWith(".duckdb"))
                        .forEach(dbFiles::add);
            }
            assertEquals(Set.of("case.db"), dbFiles,
                    "a database file other than case.db appeared: " + dbFiles);
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void corpusTablesLiveInTheSameFileAsTheForensicSchema() throws Exception {
        withCase((db, corpus) -> {
            // sqlite_master in the main schema must contain both sets of tables. If the
            // corpus tables were attached from elsewhere they would not be in 'main'.
            Set<String> tables = new HashSet<>();
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name FROM main.sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String forensic : List.of("item", "queue", "audit")) {
                assertTrue(tables.contains(forensic), "missing forensic table " + forensic);
            }
            for (String corpusTable : List.of("path", "word", "category", "keyword",
                    "path_word", "path_keyword", "path_category")) {
                assertTrue(tables.contains(corpusTable),
                        "corpus table " + corpusTable + " is not in the main schema");
            }

            // And nothing else is attached.
            int attached = 0;
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA database_list")) {
                while (rs.next()) {
                    attached++;
                    String name = rs.getString("name");
                    assertTrue(name.equals("main") || name.equals("temp"),
                            "unexpected attached database: " + name);
                }
            }
            assertTrue(attached <= 2, "more than main+temp are attached: " + attached);
        });
    }

    @Test
    void pathIsASatelliteOfItemAndIsDeletedWithIt() throws Exception {
        withCase((db, corpus) -> {
            // path.element_id is a FK to item(id) ON DELETE CASCADE. That is what makes
            // path a projection rather than a rival record of what exists in the case.
            try (Statement st = db.connection().createStatement()) {
                st.executeUpdate("INSERT INTO item (id,name,ext,size,status) "
                        + "VALUES ('it-1','report.pdf','pdf',10,'NEW')");
            }
            int pathId = corpus.insertPath("report.pdf", "/ev/report.pdf", 10, "pdf",
                    "Unread", java.time.LocalDate.now(), java.time.LocalDate.now(),
                    null, null, null, null, "it-1");
            assertNotNull(corpus.findPathIdByElement("it-1"));

            try (Statement st = db.connection().createStatement()) {
                st.executeUpdate("DELETE FROM item WHERE id='it-1'");
            }
            assertEquals(null, corpus.findPathIdByElement("it-1"),
                    "deleting the item left an orphaned path row, which would make the "
                            + "corpus tables an independent record of case membership");
            assertFalse(rowExists(db, "SELECT 1 FROM path WHERE id=" + pathId));
        });
    }

    @Test
    void everyPathRowCarriesAnElementIdInPracticeSoNothingIsCaseStateOnlyHere()
            throws Exception {
        withCase((db, corpus) -> {
            // element_id is nullable in the schema, which would in principle allow a
            // path row that no item backs — case state existing only in the corpus
            // tables. This records that the production write path never does that.
            try (Statement st = db.connection().createStatement()) {
                st.executeUpdate("INSERT INTO item (id,name,ext,size,status) "
                        + "VALUES ('it-2','a.txt','txt',3,'NEW')");
            }
            corpus.insertPath("a.txt", "/ev/a.txt", 3, "txt", "Unread",
                    java.time.LocalDate.now(), java.time.LocalDate.now(),
                    null, null, null, null, "it-2");

            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM path WHERE element_id IS NULL")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1),
                        "a path row exists with no backing item; the corpus tables would "
                                + "then hold case state that case.db's item table does not");
            }
        });
    }

    // ---- §16 term semantics, at the persistence layer ------------------------

    @Test
    void keywordMustHaveTwoOrMoreWords() throws Exception {
        withCase((db, corpus) -> {
            int w = corpus.insertWord("finance");
            int cat = corpus.insertCategory(w);

            assertThrows(SQLException.class, () -> corpus.insertKeyword("finance", cat),
                    "a one-word keyword was accepted");

            int two = corpus.insertKeyword("offshore account", cat);
            assertTrue(two > 0, "a two-word keyword was refused");
            int ok = corpus.insertKeyword("offshore account transfer", cat);
            assertTrue(ok > 0);
        });
    }

    @Test
    void categoryAndCategoryWordAreExactlyOneWord() throws Exception {
        withCase((db, corpus) -> {
            assertThrows(SQLException.class, () -> corpus.insertWord("two words"),
                    "a two-word category word was accepted");
            int w = corpus.insertWord("finance");
            assertTrue(corpus.insertCategory(w) > 0);
        });
    }

    @Test
    void keywordWhitespaceIsNormalisedRatherThanInflatingTheWordCount() throws Exception {
        withCase((db, corpus) -> {
            int cat = corpus.insertCategory(corpus.insertWord("finance"));
            // Ragged spacing must not inflate the word count past the two-word rule,
            // nor store two spellings of the same phrase.
            assertThrows(SQLException.class,
                    () -> corpus.insertKeyword("  padded  ", cat));

            corpus.insertKeyword("  offshore   account  ", cat);
            List<String> phrases = new ArrayList<>();
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT phrase FROM keyword")) {
                while (rs.next()) {
                    phrases.add(rs.getString(1));
                }
            }
            assertEquals(List.of("offshore account"), phrases);
        });
    }

    /**
     * The two-word and one-word rules used to be enforced only in Java, so raw SQL
     * against the file could create a malformed term — a one-word "keyword" that the
     * relationship model cannot tell apart from a category word. Schema triggers now
     * close that, for old cases as well as new ones, because migration adds them.
     */
    @Test
    void rawSqlCannotBypassTheKeywordTwoWordRule() throws Exception {
        withCase((db, corpus) -> {
            int cat = corpus.insertCategory(corpus.insertWord("finance"));
            try (Statement st = db.connection().createStatement()) {
                for (String bad : new String[]{"single", "   ", "  padded  "}) {
                    SQLException e = assertThrows(SQLException.class,
                            () -> st.executeUpdate("INSERT INTO keyword (phrase, category_id) "
                                    + "VALUES ('" + bad + "', " + cat + ")"),
                            "raw SQL inserted a malformed keyword: \"" + bad + "\"");
                    assertTrue(e.getMessage().contains("two words"), e.getMessage());
                }
                // The rule is a floor, not a straitjacket: valid phrases still insert.
                st.executeUpdate("INSERT INTO keyword (phrase, category_id) "
                        + "VALUES ('offshore account', " + cat + ")");
                st.executeUpdate("INSERT INTO keyword (phrase, category_id) "
                        + "VALUES ('offshore account transfer', " + cat + ")");
                st.executeUpdate("INSERT INTO keyword (phrase, category_id) "
                        + "VALUES ('four word key phrase', " + cat + ")");
            }
        });
    }

    @Test
    void rawSqlCannotBypassTheOneWordRuleForCategoryWords() throws Exception {
        withCase((db, corpus) -> {
            try (Statement st = db.connection().createStatement()) {
                for (String bad : new String[]{"two words", "a b c", ""}) {
                    assertThrows(SQLException.class,
                            () -> st.executeUpdate(
                                    "INSERT INTO word (word) VALUES ('" + bad + "')"),
                            "raw SQL inserted a multi-word category word: \"" + bad + "\"");
                }
                st.executeUpdate("INSERT INTO word (word) VALUES ('finance')");
            }
        });
    }

    @Test
    void theRulesAlsoApplyToUpdatesNotJustInserts() throws Exception {
        withCase((db, corpus) -> {
            // An UPDATE is the sneakier route: insert something valid, then degrade it.
            int cat = corpus.insertCategory(corpus.insertWord("finance"));
            corpus.insertKeyword("offshore account transfer", cat);

            try (Statement st = db.connection().createStatement()) {
                assertThrows(SQLException.class, () -> st.executeUpdate(
                        "UPDATE keyword SET phrase='single' WHERE phrase='offshore account transfer'"),
                        "an UPDATE degraded a keyword to one word");
                assertEquals(1, st.executeUpdate(
                        "UPDATE keyword SET phrase='offshore account' WHERE phrase='offshore account transfer'"),
                        "an UPDATE to a two-word phrase was refused");
                assertThrows(SQLException.class, () -> st.executeUpdate(
                        "UPDATE word SET word='two words' WHERE word='finance'"),
                        "an UPDATE degraded a category word to two words");
            }
            // And the originals are intact.
            assertNotNull(corpus.findWordId("finance"));
        });
    }

    @Test
    void schemaEnforcementAgreesWithTheJavaEnforcement() throws Exception {
        withCase((db, corpus) -> {
            // The two layers must not disagree: anything the DAO accepts the schema must
            // accept, and anything the DAO rejects the schema must reject. A divergence
            // here would mean a valid term the application cannot save, or an invalid one
            // it can.
            int cat = corpus.insertCategory(corpus.insertWord("finance"));
            String[] candidates = {
                    "single", "two words", "three word phrase",
                    "  ragged   spacing   here  ", "a b c d e", "", "   "
            };
            for (String c : candidates) {
                // Each candidate is probed on its own connection state, and the two
                // layers are compared on *why* they refused, not merely that they did.
                // A UNIQUE violation is not a semantics rejection, and conflating the
                // two is how a test like this quietly stops testing anything.
                boolean daoRejectsOnSemantics = false;
                try {
                    corpus.insertKeyword(c, cat);
                } catch (SQLException e) {
                    daoRejectsOnSemantics = e.getMessage().contains("two words");
                }

                boolean schemaRejectsOnSemantics = false;
                try (Statement st = db.connection().createStatement()) {
                    st.executeUpdate("INSERT INTO keyword (phrase, category_id) VALUES ('"
                            + c.replace("'", "''") + "', " + cat + ")");
                } catch (SQLException e) {
                    schemaRejectsOnSemantics = e.getMessage().contains("two words");
                }

                assertEquals(daoRejectsOnSemantics, schemaRejectsOnSemantics,
                        "DAO and schema disagree about the validity of \"" + c + "\"");
            }
        });
    }

    private static boolean rowExists(CaseDatabase db, String sql) throws SQLException {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }
}
