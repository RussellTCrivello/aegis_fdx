package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.RelationshipAnalyzer;
import com.aegis.fdx.facade.RelationshipFacade;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.Terms;
import com.aegis.fdx.facade.dto.FileRelationships;
import com.aegis.fdx.facade.dto.MatchRow;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.TermSummary;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relationship model, checked from both ends.
 *
 * <p>FILE ↔ KEYWORD ↔ CATEGORY ↔ CATEGORY WORD. Every assertion here reads the
 * relationships the analyzer derived from the processed text of a real case; nothing
 * is linked by hand, so a count can only be right if the edges are.
 */
class RelationshipModelTest {

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Relationships", s);
    }

    /**
     * Four files, one keyword, two categories, three category words.
     *
     * <pre>
     *  invoice.txt   "Invoice 2024 ... Payment due in 30 days. Payment due in 30 days."
     *  memo.txt      "Memo about the Acme consulting agreement and payment terms."
     *  ledger.txt    "Ledger of consulting fees. Payment due in 30 days."
     *  notes/plain.txt "Nothing of interest here."
     *
     *  category finance  ← words: invoice, payment
     *  category legal    ← words: agreement
     *  keyword  "payment due in 30 days" → finance
     * </pre>
     */
    private Seeded seed(LiveCase c, Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev.resolve("notes"));
        Files.writeString(ev.resolve("invoice.txt"),
                "Invoice 2024 from Acme. Payment due in 30 days. Payment due in 30 days.",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("memo.txt"),
                "Memo about the Acme consulting agreement and payment terms.",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("ledger.txt"),
                "Ledger of consulting fees. PAYMENT DUE IN 30 DAYS.",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("notes").resolve("plain.txt"),
                "Nothing of interest here.", StandardCharsets.UTF_8);

        AegisFacades f = AegisFacades.open(c);
        int src = f.sources().createSource(
                new SourceDraft("Acme", "NL", "custodian", 0.8).city("Amsterdam"));
        int asp = f.aspects().createAspect("Plaintiff", 0.9);
        int finance = f.categories().createCategory("finance");
        int legal = f.categories().createCategory("legal");
        f.categories().linkWordToCategory("invoice", "finance");
        f.categories().linkWordToCategory("payment", "finance");
        f.categories().linkWordToCategory("agreement", "legal");
        f.keywords().createKeyword("payment due in 30 days", "finance");
        int kw = f.keywords().listKeywords().results().get(0).id();

        f.processing("Acme", "Plaintiff").processFolder(ev.toString());
        f.contents().registerIngestedItems(src, asp);
        return new Seeded(f, f.relationships(), new CorpusDatabase(c.db()), finance, legal, kw);
    }

    private record Seeded(AegisFacades f, RelationshipFacade rel, CorpusDatabase dao,
                          int financeId, int legalId, int keywordId) {
        int pathOf(String fileName) {
            for (PathDto p : f.contents().getPaths().results()) {
                if (p.fileName().equals(fileName)) {
                    return p.id();
                }
            }
            throw new AssertionError("no such file registered: " + fileName);
        }

        int wordId(String word) {
            for (TermSummary t : rel.categoryWords(word, 50, 0).results()) {
                if (t.normalized().equals(Terms.normalize(word))) {
                    return t.id();
                }
            }
            throw new AssertionError("no such category word: " + word);
        }
    }

    private static Set<String> names(List<TermSummary.FileRef> files) {
        Set<String> out = new TreeSet<>();
        for (TermSummary.FileRef r : files) {
            out.add(r.fileName());
        }
        return out;
    }

    private static Set<String> fileNames(List<MatchRow> rows) {
        Set<String> out = new TreeSet<>();
        for (MatchRow r : rows) {
            out.add(r.fileName());
        }
        return out;
    }

    private static Set<MatchRow.MatchType> types(List<MatchRow> rows) {
        Set<MatchRow.MatchType> out = EnumSet.noneOf(MatchRow.MatchType.class);
        for (MatchRow r : rows) {
            out.add(r.matchType());
        }
        return out;
    }

    // ============================================================= invariants

    @Test
    @DisplayName("Keyword must have at least three words; category exactly one")
    void invariants(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            assertThrows(FacadeException.class,
                    () -> s.f().keywords().createKeyword("two words", "finance"));
            assertThrows(FacadeException.class,
                    () -> s.f().keywords().createKeyword("single", "finance"));
            assertThrows(FacadeException.class,
                    () -> s.f().categories().createCategory("two words"));
            assertThrows(FacadeException.class,
                    () -> s.f().categories().linkWordToCategory("two words", "finance"));
            assertEquals(Terms.Kind.KEYWORD, Terms.classify("payment due in 30 days"));
            assertEquals(Terms.Kind.REJECTED, Terms.classify("payment due"));
            assertEquals(Terms.Kind.CATEGORY_WORD, Terms.classify("Payment"));
            // normalisation is for matching; the display form survives
            TermSummary k = s.rel().keyword(s.keywordId());
            assertEquals("payment due in 30 days", k.text());
            assertEquals(5, k.wordCount());
        }
    }

    // ========================================================= bidirectional

    @Test
    @DisplayName("Keyword → files equals files → keyword")
    void keywordBidirectional(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            TermSummary k = s.rel().keyword(s.keywordId());
            assertEquals(Set.of("invoice.txt", "ledger.txt"), names(k.files()));
            assertEquals(2, k.fileCount());
            assertEquals(k.files().size(), s.rel().fileCountForKeyword(s.keywordId()));

            Set<String> reverse = new HashSet<>();
            for (PathDto p : s.f().contents().getPaths().results()) {
                FileRelationships fr = s.rel().forFile(p.id());
                boolean has = fr.keywords().stream().anyMatch(t -> t.id() == s.keywordId());
                if (has) {
                    reverse.add(p.fileName());
                }
            }
            assertEquals(names(k.files()), new TreeSet<>(reverse));
            // occurrence counts are real: two in the invoice, one in the ledger
            for (TermSummary.FileRef r : k.files()) {
                assertEquals(r.fileName().startsWith("invoice") ? 2 : 1, r.hits(), r.fileName());
            }
        }
    }

    @Test
    @DisplayName("Category word → files equals files → category word")
    void categoryWordBidirectional(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            int payment = s.wordId("payment");
            TermSummary w = s.rel().categoryWord(payment);
            assertEquals(Set.of("invoice.txt", "ledger.txt", "memo.txt"), names(w.files()));
            assertEquals(3, w.fileCount());
            for (String name : names(w.files())) {
                FileRelationships fr = s.rel().forFile(s.pathOf(name));
                assertTrue(fr.categoryWords().stream().anyMatch(t -> t.id() == payment),
                        name + " must list 'payment'");
            }
            assertFalse(s.rel().forFile(s.pathOf("plain.txt")).categoryWords().stream()
                    .anyMatch(t -> t.id() == payment));
        }
    }

    @Test
    @DisplayName("Category → files (through its words) equals files → category")
    void categoryBidirectional(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            TermSummary fin = s.rel().category(s.financeId());
            assertEquals(Set.of("invoice.txt", "ledger.txt", "memo.txt"), names(fin.files()));
            assertEquals(3, fin.fileCount());
            TermSummary legal = s.rel().category(s.legalId());
            assertEquals(Set.of("memo.txt"), names(legal.files()));

            for (PathDto p : s.f().contents().getPaths().results()) {
                FileRelationships fr = s.rel().forFile(p.id());
                boolean inFinance = fr.categories().stream().anyMatch(t -> t.id() == s.financeId());
                assertEquals(names(fin.files()).contains(p.fileName()), inFinance, p.fileName());
            }
            // and the list counts agree with the detail counts across the whole case
            for (TermSummary t : s.rel().categories(null, 100, 0).results()) {
                assertEquals(s.rel().category(t.id()).fileCount(), t.fileCount(), t.text());
            }
            for (TermSummary t : s.rel().keywords(null, 100, 0).results()) {
                assertEquals(s.rel().keyword(t.id()).fileCount(), t.fileCount(), t.text());
            }
            for (TermSummary t : s.rel().categoryWords(null, 100, 0).results()) {
                assertEquals(s.rel().categoryWord(t.id()).fileCount(), t.fileCount(), t.text());
            }
        }
    }

    @Test
    @DisplayName("Keyword ↔ category ↔ category word links are the ones the schema records")
    void termToTerm(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            TermSummary k = s.rel().keyword(s.keywordId());
            assertEquals(1, k.categories().size());
            assertEquals("finance", k.categories().get(0).text());

            TermSummary fin = s.rel().category(s.financeId());
            assertTrue(fin.keywords().stream().anyMatch(t -> t.id() == s.keywordId()));
            Set<String> words = new TreeSet<>();
            for (TermSummary.RelatedTerm t : fin.categoryWords()) {
                words.add(t.text());
            }
            // the category's own word is one of its words, as in the reference schema
            assertEquals(Set.of("finance", "invoice", "payment"), words);

            TermSummary w = s.rel().categoryWord(s.wordId("agreement"));
            assertEquals(1, w.categories().size());
            assertEquals("legal", w.categories().get(0).text());
        }
    }

    @Test
    @DisplayName("Re-running analysis or linking twice never duplicates a relationship")
    void duplicateRelationship(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            int invoice = s.pathOf("invoice.txt");
            s.dao().linkPathToKeyword(invoice, s.keywordId(), 2);
            s.dao().linkPathToKeyword(invoice, s.keywordId(), 2);
            s.dao().linkPathToWord(invoice, s.wordId("payment"), 2);
            assertEquals(2, s.rel().fileCountForKeyword(s.keywordId()));
            assertEquals(3, s.rel().fileCountForWord(s.wordId("payment")));

            RelationshipAnalyzer.Result r = s.f().relationshipAnalyzer().analyzeAll(null);
            assertEquals(4, r.filesScanned());
            assertEquals(4, r.filesWithText());
            assertEquals(2, s.rel().fileCountForKeyword(s.keywordId()));
            assertEquals(3, s.rel().fileCountForWord(s.wordId("payment")));
            assertEquals(2, s.rel().keyword(s.keywordId()).files().size());
            assertEquals(2, s.rel().totals().get("keyword_edges"));
        }
    }

    // ================================================================= search

    @Test
    @DisplayName("Search by a three-word keyword, a two-word fragment and a single word")
    void searchByWordCount(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            List<MatchRow> three = s.rel().search("payment due in", 100);
            assertTrue(types(three).contains(MatchRow.MatchType.KEYWORD));
            assertEquals(Set.of("invoice.txt", "ledger.txt"), fileNames(
                    s.rel().search("payment due in", EnumSet.of(RelationshipFacade.Scope.KEYWORD), 100)));

            // two words: not a keyword and not a category word — content only
            List<MatchRow> two = s.rel().search("payment due", 100);
            assertEquals(Set.of("invoice.txt", "ledger.txt"), fileNames(two));
            assertTrue(types(two).contains(MatchRow.MatchType.KEYWORD),
                    "a partial keyword match still reaches the keyword");
            assertTrue(types(two).contains(MatchRow.MatchType.CONTENT));

            // one word: category word relation plus content
            List<MatchRow> one = s.rel().search("payment", 100);
            assertEquals(Set.of("invoice.txt", "ledger.txt", "memo.txt"), fileNames(one));
            assertTrue(types(one).contains(MatchRow.MatchType.CATEGORY_WORD));
        }
    }

    @Test
    @DisplayName("Search by category and by category word report their own match type")
    void searchByCategoryAndWord(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            List<MatchRow> cat = s.rel().search("finance",
                    EnumSet.of(RelationshipFacade.Scope.CATEGORY), 100);
            assertEquals(Set.of("invoice.txt", "ledger.txt", "memo.txt"), fileNames(cat));
            for (MatchRow r : cat) {
                assertEquals(MatchRow.MatchType.CATEGORY, r.matchType());
                assertEquals(s.financeId(), r.categoryId());
                assertEquals("finance", r.category());
            }
            // "finance" is not in any file's text: no content match is fabricated
            assertTrue(s.rel().search("finance",
                    EnumSet.of(RelationshipFacade.Scope.CONTENT), 100).isEmpty());

            List<MatchRow> word = s.rel().search("agreement",
                    EnumSet.of(RelationshipFacade.Scope.CATEGORY_WORD), 100);
            assertEquals(Set.of("memo.txt"), fileNames(word));
            assertEquals("agreement", word.get(0).categoryWord());
            assertEquals(1, word.get(0).hits());
        }
    }

    @Test
    @DisplayName("Exact and partial keyword, case-insensitive")
    void searchKeywordForms(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            Set<RelationshipFacade.Scope> kw = EnumSet.of(RelationshipFacade.Scope.KEYWORD);
            Set<String> expect = Set.of("invoice.txt", "ledger.txt");
            assertEquals(expect, fileNames(s.rel().search("payment due in 30 days", kw, 100)));
            assertEquals(expect, fileNames(s.rel().search("due in 30", kw, 100)));
            assertEquals(expect, fileNames(s.rel().search("PAYMENT DUE IN 30 DAYS", kw, 100)));
            assertEquals(expect, fileNames(s.rel().search("Payment Due", kw, 100)));
            for (MatchRow r : s.rel().search("due in 30", kw, 100)) {
                assertEquals(s.keywordId(), r.keywordId());
                assertEquals("payment due in 30 days", r.matchedTerm());
            }
        }
    }

    @Test
    @DisplayName("Path, file name, content and metadata matches are told apart")
    void searchByLocation(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            List<MatchRow> byName = s.rel().search("ledger", 100);
            assertTrue(byName.stream().anyMatch(r -> r.matchType() == MatchRow.MatchType.FILE_NAME
                    && r.fileName().equals("ledger.txt")));
            assertTrue(byName.stream().anyMatch(r -> r.matchType() == MatchRow.MatchType.CONTENT
                    && r.fileName().equals("ledger.txt")));

            // "notes" is only in plain.txt's path, never in its name or text
            List<MatchRow> byPath = s.rel().search("notes", 100);
            assertEquals(Set.of("plain.txt"), fileNames(byPath));
            assertEquals(EnumSet.of(MatchRow.MatchType.PATH), types(byPath));

            List<MatchRow> byContent = s.rel().search("nothing of interest",
                    EnumSet.of(RelationshipFacade.Scope.CONTENT), 100);
            assertEquals(Set.of("plain.txt"), fileNames(byContent));

            // metadata: file type and source name
            List<MatchRow> byType = s.rel().search("txt",
                    EnumSet.of(RelationshipFacade.Scope.METADATA), 100);
            assertEquals(4, byType.size());
            assertEquals("type", byType.get(0).snippet());
            List<MatchRow> bySource = s.rel().search("Acme",
                    EnumSet.of(RelationshipFacade.Scope.METADATA), 100);
            assertEquals(4, bySource.size());
            assertEquals("source", bySource.get(0).snippet());
            assertEquals(MatchRow.MatchType.METADATA, bySource.get(0).matchType());
        }
    }

    @Test
    @DisplayName("Empty result, multiple matches per file, and blank query")
    void searchEdges(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            assertTrue(s.rel().search("zzz-not-anywhere", 100).isEmpty());
            assertTrue(s.rel().search("   ", 100).isEmpty());

            // "invoice" reaches invoice.txt by name, by category word and by content
            List<MatchRow> inv = s.rel().search("invoice", 100);
            Set<MatchRow.MatchType> forInvoice = EnumSet.noneOf(MatchRow.MatchType.class);
            for (MatchRow r : inv) {
                if (r.fileName().equals("invoice.txt")) {
                    forInvoice.add(r.matchType());
                }
            }
            assertEquals(EnumSet.of(MatchRow.MatchType.FILE_NAME, MatchRow.MatchType.CATEGORY_WORD,
                    MatchRow.MatchType.CONTENT), forInvoice);
            // one row per (file, match type): never the same evidence twice
            Set<String> keys = new HashSet<>();
            for (MatchRow r : inv) {
                assertTrue(keys.add(r.pathId() + "/" + r.matchType()));
            }
            assertNotNull(inv.get(0).elementId());
        }
    }

    // ============================================================ file detail

    @Test
    @DisplayName("File detail lists exactly the terms the analyzer related to it")
    void fileDetail(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            FileRelationships memo = s.rel().forFile(s.pathOf("memo.txt"));
            assertTrue(memo.keywords().isEmpty(), "memo has no keyword phrase");
            Set<String> cats = new TreeSet<>();
            for (TermSummary.RelatedTerm t : memo.categories()) {
                cats.add(t.text());
            }
            assertEquals(Set.of("finance", "legal"), cats);
            Set<String> words = new TreeSet<>();
            for (TermSummary.RelatedTerm t : memo.categoryWords()) {
                words.add(t.text());
            }
            assertEquals(Set.of("agreement", "payment"), words);

            FileRelationships plain = s.rel().forFile(s.pathOf("plain.txt"));
            assertTrue(plain.keywords().isEmpty());
            assertTrue(plain.categories().isEmpty());
            assertTrue(plain.categoryWords().isEmpty());
        }
    }

    // ============================================================= integrity

    @Test
    @DisplayName("Integrity checker traverses every edge both ways and finds no disagreement")
    void integrityConsistent(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            var report = s.f().relationshipIntegrity().check();
            assertTrue(report.consistent(), com.aegis.fdx.facade.RelationshipIntegrity.render(report));
            System.out.println(com.aegis.fdx.facade.RelationshipIntegrity.render(report));
            assertEquals(4, report.files());
            assertEquals(1, report.keywords());
            assertEquals(2, report.categories());
            assertEquals(5, report.categoryWords(), "finance, legal, invoice, payment, agreement");
            assertEquals(2, report.keywordEdges());
            assertTrue(report.traversalsChecked() > 10);
            // analysing three more times changes nothing
            for (int i = 0; i < 3; i++) {
                s.f().relationshipAnalyzer().analyzeAll(null);
            }
            var again = s.f().relationshipIntegrity().check();
            assertTrue(again.consistent());
            assertEquals(report.keywordEdges(), again.keywordEdges());
            assertEquals(report.wordEdges(), again.wordEdges());
        }
    }

    @Test
    @DisplayName("Integrity checker reports a planted inconsistency instead of hiding it")
    void integrityDetectsDamage(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            // plant an edge to a keyword that does not exist, bypassing the facade
            try (var st = c.db().connection().createStatement()) {
                st.execute("PRAGMA foreign_keys=OFF");
                st.execute("INSERT INTO path_keyword (path_id, keyword_id, hits) VALUES ("
                        + s.pathOf("plain.txt") + ", 9999, 1)");
            }
            var report = s.f().relationshipIntegrity().check();
            System.out.println(com.aegis.fdx.facade.RelationshipIntegrity.render(report));
            assertFalse(report.consistent());
            assertTrue(report.findings().stream().anyMatch(f -> f.rule().equals("orphan-edge")),
                    com.aegis.fdx.facade.RelationshipIntegrity.render(report));
        }
    }

    // ===================================================== storage-level invariants

    @Test
    @DisplayName("The database layer refuses invalid terms even when the facade is bypassed")
    void storageInvariants(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            assertThrows(java.sql.SQLException.class, () -> s.dao().insertWord("two words"));
            assertThrows(java.sql.SQLException.class, () -> s.dao().updateWord(s.wordId("payment"), "a b"));
            assertThrows(java.sql.SQLException.class,
                    () -> s.dao().insertKeyword("only two", s.financeId()));
            assertThrows(java.sql.SQLException.class,
                    () -> s.dao().updateKeyword(s.keywordId(), "too short"));
            // and the valid forms still work
            assertTrue(s.dao().insertWord("ledger") > 0);
            assertTrue(s.dao().insertKeyword("three word phrase", s.financeId()) > 0);
        }
    }

    // ============================================================ duplicates

    @Test
    @DisplayName("Duplicate keywords and categories are found by normalised text and merged without losing edges")
    void mergeDuplicates(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            // a case-variant duplicate keyword, with its own edge to the memo
            s.f().keywords().createKeyword("Payment Due In 30 Days", "finance");
            int dup = s.f().keywords().listKeywords().results().stream()
                    .filter(k -> k.keyword().equals("Payment Due In 30 Days")).findFirst().orElseThrow().id();
            s.dao().linkPathToKeyword(s.pathOf("memo.txt"), dup, 1);
            assertEquals(1, s.f().keywords().findDuplicates().size());

            int removed = s.f().keywords().mergeDuplicates();
            assertEquals(1, removed);
            assertTrue(s.f().keywords().findDuplicates().isEmpty());
            // the survivor now reaches all three files; hits preserved
            assertEquals(Set.of("invoice.txt", "ledger.txt", "memo.txt"),
                    names(s.rel().keyword(s.keywordId()).files()));
            assertTrue(s.f().relationshipIntegrity().check().consistent());

            // duplicate category
            s.f().categories().createCategory("Finance");
            assertEquals(1, s.f().categories().findDuplicates().size());
            s.f().categories().linkWordToCategory("ledger", "Finance");
            assertEquals(1, s.f().categories().mergeDuplicates());
            assertTrue(s.f().categories().findDuplicates().isEmpty());
            Set<String> words = new TreeSet<>();
            for (var w : s.rel().category(s.financeId()).categoryWords()) {
                words.add(w.text());
            }
            assertTrue(words.contains("ledger"), "merged category keeps the moved word");
            assertTrue(s.f().relationshipIntegrity().check().consistent());
        }
    }

    // ============================================================ retrospective indexing

    @Test
    @DisplayName("Creating or updating terms after ingestion immediately indexes existing file content")
    void retrospectiveIndexingOnTermCreation(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);

            // Adding a keyword after files have already been ingested
            // "memo.txt" contains: "Memo about the Acme consulting agreement and payment terms."
            assertTrue(s.f().keywords().createKeyword("consulting agreement and", "legal"));
            int newKwId = s.f().keywords().listKeywords().results().stream()
                    .filter(k -> k.keyword().equals("consulting agreement and")).findFirst().orElseThrow().id();

            // The keyword must immediately relate to memo.txt without manual analyzeAll
            TermSummary kwSummary = s.rel().keyword(newKwId);
            assertEquals(1, kwSummary.fileCount());
            assertEquals(Set.of("memo.txt"), names(kwSummary.files()));

            // Adding a category word after ingestion
            // "ledger.txt" contains: "Ledger of consulting fees. PAYMENT DUE IN 30 DAYS."
            assertTrue(s.f().categories().linkWordToCategory("fees", "finance"));
            int feesWordId = s.wordId("fees");
            TermSummary wordSummary = s.rel().categoryWord(feesWordId);
            assertEquals(1, wordSummary.fileCount());
            assertEquals(Set.of("ledger.txt"), names(wordSummary.files()));

            // Updating the keyword phrase
            // "ledger.txt" contains: "Ledger of consulting fees. PAYMENT DUE IN 30 DAYS."
            assertTrue(s.f().keywords().updateKeyword(newKwId, "of consulting fees"));
            TermSummary updatedKw = s.rel().keyword(newKwId);
            assertEquals(1, updatedKw.fileCount());
            assertEquals(Set.of("ledger.txt"), names(updatedKw.files()));

            assertTrue(s.f().relationshipIntegrity().check().consistent());
        }
    }
}
