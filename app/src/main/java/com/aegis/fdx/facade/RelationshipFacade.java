package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.FileRelationships;
import com.aegis.fdx.facade.dto.MatchRow;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.TermSummary;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;

/**
 * The relationship graph, in both directions.
 *
 * <p>Files, keywords, categories and category words are not four lists that happen to
 * sit next to each other; they are one graph, and this facade is the only way the
 * interface walks it. Every edge is read from {@code case.db} — the authoritative store
 * — through a query written once and used from both ends:
 *
 * <pre>
 *   FILE ── path_keyword ──▶ KEYWORD ── keyword.category_id ──▶ CATEGORY
 *     │                                                             │
 *     ├── path_word ─────▶ CATEGORY WORD ── word_category ──────────┘
 *     └── path_category ─────────────────────────────────────────────▶
 * </pre>
 *
 * <p>A file relates to a category either because a reviewer or an analysis run attributed
 * it ({@code path_category}), or because the file contains one of that category's words
 * ({@code path_word} ⨝ {@code word_category}) — the route the reference model uses
 * ({@code words_paths} ⨝ {@code words_categorys}). Both count, and the count a table
 * shows is over the whole case: not the page, not the current filter, not one screen.
 *
 * <p>Nothing here invents an edge. Keyword → category exists because the schema records
 * it; category word → keyword is computed from the words of the phrase, which is what
 * makes a keyword a phrase in the first place. Where the reference establishes no
 * relation, none is offered.
 */
public final class RelationshipFacade {

    /** How many files a detail view lists before paging. */
    public static final int FILE_LIMIT = 500;

    private final CorpusDatabase db;

    public RelationshipFacade(CorpusDatabase db) {
        this.db = db;
    }

    // ================================================== lists with real counts

    /** Keywords with the number of files each one appears in. */
    public Page<TermSummary> keywords(String search, int limit, int offset) {
        try {
            List<TermSummary> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectKeywordsWithFileCounts(
                    search, Validate.limit(limit), Validate.offset(offset))) {
                String phrase = r.str("keyword");
                out.add(new TermSummary(TermSummary.Kind.KEYWORD, r.i("id"), phrase,
                        Terms.normalize(phrase), Terms.wordCount(phrase),
                        r.i("files"), r.i("hits"),
                        List.of(new TermSummary.RelatedTerm(TermSummary.Kind.CATEGORY,
                                r.i("category_id"), r.str("category_word"), -1)),
                        List.of(), List.of(), List.of()));
            }
            return new Page<>(out, db.countKeywords());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list keywords with counts", e);
        }
    }

    /** Categories with the number of files each one reaches. */
    public Page<TermSummary> categories(String search, int limit, int offset) {
        try {
            List<TermSummary> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectCategoriesWithFileCounts(
                    search, Validate.limit(limit), Validate.offset(offset))) {
                String word = r.str("word");
                out.add(new TermSummary(TermSummary.Kind.CATEGORY, r.i("id"), word,
                        Terms.normalize(word), Terms.wordCount(word),
                        r.i("files"), 0,
                        List.of(), List.of(), List.of(), List.of()));
            }
            return new Page<>(out, db.countCategories());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list categories with counts", e);
        }
    }

    /** Category words with the number of files each one occurs in. */
    public Page<TermSummary> categoryWords(String search, int limit, int offset) {
        try {
            List<TermSummary> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectCategoryWordsWithFileCounts(
                    search, Validate.limit(limit), Validate.offset(offset))) {
                String word = r.str("word");
                out.add(new TermSummary(TermSummary.Kind.CATEGORY_WORD, r.i("id"), word,
                        Terms.normalize(word), 1, r.i("files"), r.i("hits"),
                        List.of(), List.of(), List.of(), List.of()));
            }
            return new Page<>(out, out.size());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list category words with counts", e);
        }
    }

    // ============================================================ term details

    /** A keyword with its category, the words of its phrase, and the files carrying it. */
    public TermSummary keyword(int keywordId) {
        Validate.positiveId(keywordId, "keywordId");
        try {
            CorpusDatabase.Row k = db.selectKeywordById(keywordId);
            if (k == null) {
                throw FacadeException.notFound("keyword", keywordId);
            }
            String phrase = k.str("keyword");
            int categoryId = k.i("category_id");

            TermSummary parent = category(categoryId, false);
            List<TermSummary.RelatedTerm> cats = new ArrayList<>();
            cats.add(new TermSummary.RelatedTerm(TermSummary.Kind.CATEGORY,
                    parent.id(), parent.text(), parent.fileCount()));

            // The words of the phrase that are category words in their own right: this
            // is what lets an examiner go keyword → word → other files.
            List<TermSummary.RelatedTerm> words = new ArrayList<>();
            for (String w : Terms.words(phrase)) {
                for (CorpusDatabase.Row row : db.selectCategoryWordsMatching(w)) {
                    if (Terms.normalize(row.str("word")).equals(w)) {
                        words.add(new TermSummary.RelatedTerm(TermSummary.Kind.CATEGORY_WORD,
                                row.i("id"), row.str("word"), db.countFilesForWord(row.i("id"))));
                    }
                }
            }

            return new TermSummary(TermSummary.Kind.KEYWORD, keywordId, phrase,
                    Terms.normalize(phrase), Terms.wordCount(phrase),
                    db.countFilesForKeyword(keywordId), totalHitsForKeyword(keywordId),
                    cats, List.of(), words, filesForKeyword(keywordId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load keyword relationships", e);
        }
    }

    /** A category with its keywords, its words, and the files it reaches. */
    public TermSummary category(int categoryId) {
        return category(categoryId, true);
    }

    private TermSummary category(int categoryId, boolean withNeighbours) {
        Validate.positiveId(categoryId, "categoryId");
        try {
            CorpusDatabase.Row c = db.selectCategoryById(categoryId);
            if (c == null) {
                throw FacadeException.notFound("category", categoryId);
            }
            String word = c.str("word");
            int files = db.countFilesForCategory(categoryId);
            if (!withNeighbours) {
                return new TermSummary(TermSummary.Kind.CATEGORY, categoryId, word,
                        Terms.normalize(word), 1, files, 0,
                        List.of(), List.of(), List.of(), List.of());
            }

            List<TermSummary.RelatedTerm> keywords = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectKeywordsOfCategory(categoryId)) {
                keywords.add(new TermSummary.RelatedTerm(TermSummary.Kind.KEYWORD,
                        r.i("id"), r.str("keyword"), db.countFilesForKeyword(r.i("id"))));
            }
            List<TermSummary.RelatedTerm> words = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectWordsOfCategory(categoryId)) {
                words.add(new TermSummary.RelatedTerm(TermSummary.Kind.CATEGORY_WORD,
                        r.i("id"), r.str("word"), db.countFilesForWord(r.i("id"))));
            }

            return new TermSummary(TermSummary.Kind.CATEGORY, categoryId, word,
                    Terms.normalize(word), 1, files, 0,
                    List.of(), keywords, words, filesForCategory(categoryId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load category relationships", e);
        }
    }

    /** A category word with its categories, the keywords using it, and its files. */
    public TermSummary categoryWord(int wordId) {
        Validate.positiveId(wordId, "wordId");
        try {
            CorpusDatabase.Row w = db.selectWordById(wordId);
            if (w == null) {
                throw FacadeException.notFound("word", wordId);
            }
            String word = w.str("word");

            List<TermSummary.RelatedTerm> cats = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectCategoriesForWord(wordId)) {
                cats.add(new TermSummary.RelatedTerm(TermSummary.Kind.CATEGORY,
                        r.i("id"), r.str("word"), db.countFilesForCategory(r.i("id"))));
            }
            List<TermSummary.RelatedTerm> keywords = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectKeywordsContainingWord(word)) {
                keywords.add(new TermSummary.RelatedTerm(TermSummary.Kind.KEYWORD,
                        r.i("id"), r.str("keyword"), db.countFilesForKeyword(r.i("id"))));
            }

            return new TermSummary(TermSummary.Kind.CATEGORY_WORD, wordId, word,
                    Terms.normalize(word), 1,
                    db.countFilesForWord(wordId), totalHitsForWord(wordId),
                    cats, keywords, List.of(), filesForWord(wordId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load category word relationships", e);
        }
    }

    // ============================================================ file details

    /** Everything a file is related to, and the files it shares terms with. */
    public FileRelationships forFile(int pathId) {
        Validate.positiveId(pathId, "pathId");
        try {
            CorpusDatabase.Row p = db.selectPathById(pathId);
            if (p == null) {
                throw FacadeException.notFound("file", pathId);
            }

            List<TermSummary.RelatedTerm> keywords = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectPathKeywords(pathId)) {
                keywords.add(new TermSummary.RelatedTerm(TermSummary.Kind.KEYWORD,
                        r.i("id"), r.str("keyword"), db.countFilesForKeyword(r.i("id"))));
            }
            List<TermSummary.RelatedTerm> categories = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectCategoriesForPath(pathId)) {
                categories.add(new TermSummary.RelatedTerm(TermSummary.Kind.CATEGORY,
                        r.i("id"), r.str("word"), db.countFilesForCategory(r.i("id"))));
            }
            List<TermSummary.RelatedTerm> words = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectWordsForPath(pathId)) {
                words.add(new TermSummary.RelatedTerm(TermSummary.Kind.CATEGORY_WORD,
                        r.i("id"), r.str("word"), db.countFilesForWord(r.i("id"))));
            }

            List<TermSummary.FileRef> related = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectRelatedFiles(pathId, 50)) {
                related.add(toFileRef(r));
            }

            return new FileRelationships(pathId, p.str("element_id"), p.str("file_name"),
                    p.str("file_path"), keywords, categories, words, related);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load file relationships", e);
        }
    }

    // ================================================================== counts

    /** Files carrying a keyword, across the whole case. */
    public int fileCountForKeyword(int keywordId) {
        try {
            return db.countFilesForKeyword(Validate.positiveId(keywordId, "keywordId"));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to count files for keyword", e);
        }
    }

    /** Files a category reaches, across the whole case. */
    public int fileCountForCategory(int categoryId) {
        try {
            return db.countFilesForCategory(Validate.positiveId(categoryId, "categoryId"));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to count files for category", e);
        }
    }

    /** Files a category word occurs in, across the whole case. */
    public int fileCountForWord(int wordId) {
        try {
            return db.countFilesForWord(Validate.positiveId(wordId, "wordId"));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to count files for word", e);
        }
    }

    // ================================================================ file sets

    public List<TermSummary.FileRef> filesForKeyword(int keywordId) {
        try {
            return refs(db.selectFilesForKeyword(keywordId, FILE_LIMIT));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list files for keyword", e);
        }
    }

    public List<TermSummary.FileRef> filesForCategory(int categoryId) {
        try {
            return refs(db.selectFilesForCategory(categoryId, FILE_LIMIT));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list files for category", e);
        }
    }

    public List<TermSummary.FileRef> filesForWord(int wordId) {
        try {
            return refs(db.selectFilesForWord(wordId, FILE_LIMIT));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list files for category word", e);
        }
    }

    /** The whole vocabulary a case knows, for tests and for the checker. */
    public Map<String, Integer> vocabularyCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (TermSummary t : keywords(null, 1000, 0).results()) {
            out.put("keyword:" + t.normalized(), t.fileCount());
        }
        for (TermSummary t : categories(null, 1000, 0).results()) {
            out.put("category:" + t.normalized(), t.fileCount());
        }
        for (TermSummary t : categoryWords(null, 1000, 0).results()) {
            out.put("word:" + t.normalized(), t.fileCount());
        }
        return out;
    }

    // ============================================ whole-case counts for list rows

    /** File count per keyword id, for every keyword in the case. */
    public Map<Integer, Integer> keywordFileCounts() {
        return counts(keywords(null, 100_000, 0).results());
    }

    /** File count per category id, for every category in the case. */
    public Map<Integer, Integer> categoryFileCounts() {
        return counts(categories(null, 100_000, 0).results());
    }

    /** File count per word id, for every category word in the case. */
    public Map<Integer, Integer> wordFileCounts() {
        return counts(categoryWords(null, 100_000, 0).results());
    }

    private static Map<Integer, Integer> counts(List<TermSummary> terms) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (TermSummary t : terms) {
            out.put(t.id(), t.fileCount());
        }
        return out;
    }

    // ================================================================== search

    /** Search scope: which places a text is looked for. */
    public enum Scope { EVERYWHERE, KEYWORD, CATEGORY, CATEGORY_WORD, CONTENT, FILE_NAME, PATH, METADATA }

    /** Looks for the text everywhere and reports where each file matched. */
    public List<MatchRow> search(String text, int limit) {
        return search(text, EnumSet.of(Scope.EVERYWHERE), limit);
    }

    /**
     * Search restricted to the given scopes. A file appears once per distinct match type
     * so an examiner can see every way it was reached; order is name, path, keyword,
     * category, category word, content, metadata — most specific evidence first.
     */
    public List<MatchRow> search(String text, Set<Scope> scopes, int limit) {
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        int lim = Validate.limit(limit);
        boolean all = scopes == null || scopes.isEmpty() || scopes.contains(Scope.EVERYWHERE);
        List<MatchRow> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            if (all || scopes.contains(Scope.FILE_NAME) || scopes.contains(Scope.PATH)) {
                for (CorpusDatabase.Row r : db.selectFilesByNameOrPath(q, lim)) {
                    boolean name = r.i("name_match") == 1;
                    if (name && (all || scopes.contains(Scope.FILE_NAME))) {
                        add(out, seen, row(r, MatchRow.MatchType.FILE_NAME, q, null, null, null,
                                r.str("file_name"), 0));
                    } else if (!name && (all || scopes.contains(Scope.PATH))) {
                        add(out, seen, row(r, MatchRow.MatchType.PATH, q, null, null, null,
                                r.str("file_path"), 0));
                    }
                }
            }
            if (all || scopes.contains(Scope.KEYWORD)) {
                for (CorpusDatabase.Row k : db.selectKeywordsMatching(q)) {
                    for (CorpusDatabase.Row r : db.selectFilesForKeyword(k.i("id"), lim)) {
                        add(out, seen, row(r, MatchRow.MatchType.KEYWORD, k.str("keyword"),
                                k, null, null, k.str("keyword"), r.i("hits")));
                    }
                }
            }
            if (all || scopes.contains(Scope.CATEGORY)) {
                for (CorpusDatabase.Row c : db.selectCategoriesMatching(q)) {
                    for (CorpusDatabase.Row r : db.selectFilesForCategory(c.i("id"), lim)) {
                        add(out, seen, row(r, MatchRow.MatchType.CATEGORY, c.str("word"),
                                null, c, null, c.str("word"), 0));
                    }
                }
            }
            if (all || scopes.contains(Scope.CATEGORY_WORD)) {
                for (CorpusDatabase.Row w : db.selectCategoryWordsMatching(q)) {
                    for (CorpusDatabase.Row r : db.selectFilesForWord(w.i("id"), lim)) {
                        add(out, seen, row(r, MatchRow.MatchType.CATEGORY_WORD, w.str("word"),
                                null, null, w, w.str("word"), r.i("hits")));
                    }
                }
            }
            if (all || scopes.contains(Scope.CONTENT)) {
                for (CorpusDatabase.Row r : db.selectFilesByContent(q, lim)) {
                    add(out, seen, row(r, MatchRow.MatchType.CONTENT, q, null, null, null,
                            "content contains \"" + q + "\"", 0));
                }
            }
            if (all || scopes.contains(Scope.METADATA)) {
                for (CorpusDatabase.Row r : db.selectFilesByMetadata(q, lim)) {
                    add(out, seen, row(r, MatchRow.MatchType.METADATA, q, null, null, null,
                            r.str("field"), 0));
                }
            }
        } catch (SQLException e) {
            throw FacadeException.internal("search failed", e);
        }
        return out.size() > lim ? new ArrayList<>(out.subList(0, lim)) : out;
    }

    /** Whole-case relationship totals, straight from the tables. */
    public Map<String, Integer> totals() {
        try {
            CorpusDatabase.Row r = db.selectRelationshipTotals();
            Map<String, Integer> out = new LinkedHashMap<>();
            for (String k : List.of("files", "keywords", "categories", "category_words",
                    "keyword_edges", "word_edges", "category_edges")) {
                out.put(k, r.i(k));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to read relationship totals", e);
        }
    }

    private static void add(List<MatchRow> out, Set<String> seen, MatchRow row) {
        String key = row.pathId() + "|" + row.matchType() + "|" + row.matchedTerm().toLowerCase();
        if (seen.add(key)) {
            out.add(row);
        }
    }

    private static MatchRow row(CorpusDatabase.Row r, MatchRow.MatchType type, String term,
                                CorpusDatabase.Row keyword, CorpusDatabase.Row category,
                                CorpusDatabase.Row word, String snippet, int hits) {
        return new MatchRow(
                r.i("id"), r.str("element_id"), r.str("file_name"), r.str("file_path"),
                r.str("file_type"), r.l("file_size"), r.str("source_name"), r.str("aspect_name"),
                type, term,
                keyword == null ? null : keyword.str("keyword"),
                keyword == null ? null : keyword.i("id"),
                category == null ? null : category.str("word"),
                category == null ? null : category.i("id"),
                word == null ? null : word.str("word"),
                word == null ? null : word.i("id"),
                snippet, hits);
    }

    // =============================================================== internals

    private int totalHitsForKeyword(int keywordId) throws SQLException {
        int total = 0;
        for (CorpusDatabase.Row r : db.selectFilesForKeyword(keywordId, FILE_LIMIT)) {
            total += r.i("hits");
        }
        return total;
    }

    private int totalHitsForWord(int wordId) throws SQLException {
        int total = 0;
        for (CorpusDatabase.Row r : db.selectFilesForWord(wordId, FILE_LIMIT)) {
            total += r.i("hits");
        }
        return total;
    }

    private static List<TermSummary.FileRef> refs(List<CorpusDatabase.Row> rows) {
        List<TermSummary.FileRef> out = new ArrayList<>(rows.size());
        for (CorpusDatabase.Row r : rows) {
            out.add(toFileRef(r));
        }
        return out;
    }

    private static TermSummary.FileRef toFileRef(CorpusDatabase.Row r) {
        return new TermSummary.FileRef(
                r.i("id"),
                r.str("element_id"),
                r.str("file_name"),
                r.str("file_path"),
                r.i("file_size"),
                r.str("file_type"),
                r.str("file_status"),
                r.str("source_name"),
                r.str("aspect_name"),
                r.i("hits"));
    }
}
