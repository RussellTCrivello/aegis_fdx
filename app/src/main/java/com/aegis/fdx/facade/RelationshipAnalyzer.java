package com.aegis.fdx.facade;

import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Populates the relationship edges from the stored text of each file.
 *
 * <p>This is the Java equivalent of two things the reference does when a document is
 * processed ({@code contents_db_service.py::link_words_to_path} and
 * {@code process_keywords_for_path}) and of the "Update keyword associations" button on
 * its keyword list ({@code POST /api/keywords/update-associations}), which re-runs the
 * keyword matching over every file that has content. The observable behaviour is the
 * same:
 *
 * <ul>
 *   <li>a <strong>category word</strong> is related to a file when the word occurs in
 *       the file's extracted text — {@code path_word(hits)}, the reference's
 *       {@code words_paths(word_count)};</li>
 *   <li>a <strong>keyword</strong> is related to a file when its words occur
 *       consecutively, in order, in the file's extracted text —
 *       {@code path_keyword(hits)}, the reference's {@code keywords_paths(word_count)},
 *       which the reference computes by sliding the keyword's word-id sequence over the
 *       document's word-id sequence ({@code extract_keywords_fast}).</li>
 * </ul>
 *
 * <p>Both are matched on <em>whole words</em> after {@link Terms#normalize}: "account"
 * does not match "accountancy", and "Financial Transaction Report" matches
 * "financial  transaction report" across case and spacing. That is exactly what
 * matching on word ids gives the reference, reproduced here on text.
 *
 * <p>The reference indexes every token of every document. This implementation records
 * only the vocabulary the case holds — the words that are categories or belong to one,
 * and the keyword phrases — so a row in {@code path_word} always means a word the
 * examiner cares about really occurs in that file. Rows are replaced per file, so a
 * re-run after the vocabulary changed leaves no stale edge behind.
 */
public final class RelationshipAnalyzer {

    /** What a run did. */
    public record Result(int filesScanned, int filesWithText, int wordLinks, int keywordLinks,
                         int wordsChecked, int keywordsChecked) {
    }

    /** Progress, so the interface can show the operator where a long run is. */
    public record Progress(int done, int total, String fileName) {
    }

    private final CorpusDatabase db;
    private final ContentFacade contents;

    public RelationshipAnalyzer(CorpusDatabase db, ContentFacade contents) {
        this.db = db;
        this.contents = contents;
    }

    /** Re-derives {@code path_word} and {@code path_keyword} for every registered file. */
    public Result analyzeAll(Consumer<Progress> onProgress) {
        try {
            List<CorpusDatabase.Row> paths = db.selectPaths(null, null, null, null, 1_000_000, 0);
            List<Integer> ids = new ArrayList<>(paths.size());
            for (CorpusDatabase.Row r : paths) {
                ids.add(r.i("id"));
            }
            return analyze(ids, onProgress);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list files for relationship analysis", e);
        }
    }

    /** Re-derives the edges for one file, for example right after it was registered. */
    public Result analyzeFile(int pathId) {
        return analyze(List.of(Validate.positiveId(pathId, "pathId")), null);
    }

    /** Re-derives the edges for a set of files. */
    public Result analyze(List<Integer> pathIds, Consumer<Progress> onProgress) {
        if (pathIds == null) {
            throw FacadeException.validation("pathIds is required");
        }
        try {
            Vocabulary vocab = Vocabulary.load(db);
            int withText = 0;
            int wordLinks = 0;
            int keywordLinks = 0;
            int done = 0;
            for (int pathId : pathIds) {
                CorpusDatabase.Row p = db.selectPathById(pathId);
                if (p == null) {
                    continue;
                }
                String text = contents.getContentAsText(pathId);
                // Every derived edge for this file is recomputed from scratch.
                db.clearDerivedRelations(pathId);
                if (text != null && !text.isBlank()) {
                    withText++;
                    List<String> tokens = Terms.words(text);
                    Map<Integer, Integer> wordHits = vocab.countWords(tokens);
                    for (Map.Entry<Integer, Integer> e : wordHits.entrySet()) {
                        db.linkPathToWord(pathId, e.getKey(), e.getValue());
                        wordLinks++;
                    }
                    Map<Integer, Integer> keywordHits = vocab.countKeywords(tokens);
                    for (Map.Entry<Integer, Integer> e : keywordHits.entrySet()) {
                        db.linkPathToKeyword(pathId, e.getKey(), e.getValue());
                        keywordLinks++;
                    }
                }
                done++;
                if (onProgress != null) {
                    onProgress.accept(new Progress(done, pathIds.size(), p.str("file_name")));
                }
            }
            return new Result(done, withText, wordLinks, keywordLinks,
                    vocab.words.size(), vocab.keywords.size());
        } catch (SQLException e) {
            throw FacadeException.internal("relationship analysis failed", e);
        }
    }

    /**
     * Counts consecutive, in-order occurrences of {@code phrase} in {@code tokens}.
     *
     * <p>Non-overlapping, like the reference's keyword counting; both are word
     * sequences so "report report report" contains "report report" once, not twice.
     */
    public static int countPhrase(List<String> tokens, List<String> phrase) {
        if (tokens == null || phrase == null || phrase.isEmpty() || tokens.size() < phrase.size()) {
            return 0;
        }
        int n = 0;
        int i = 0;
        while (i <= tokens.size() - phrase.size()) {
            boolean match = true;
            for (int j = 0; j < phrase.size(); j++) {
                if (!tokens.get(i + j).equals(phrase.get(j))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                n++;
                i += phrase.size();
            } else {
                i++;
            }
        }
        return n;
    }

    /** The vocabulary a case holds, normalised once per run. */
    private static final class Vocabulary {
        /** normalised word → word id, for words that are a category or belong to one. */
        final Map<String, Integer> words = new LinkedHashMap<>();
        /** keyword id → normalised words of the phrase. */
        final Map<Integer, List<String>> keywords = new LinkedHashMap<>();

        static Vocabulary load(CorpusDatabase db) throws SQLException {
            Vocabulary v = new Vocabulary();
            for (CorpusDatabase.Row r : db.selectVocabularyWords()) {
                String n = Terms.normalize(r.str("word"));
                if (!n.isEmpty() && !n.contains(" ")) {
                    v.words.putIfAbsent(n, r.i("id"));
                }
            }
            for (CorpusDatabase.Row r : db.selectAllKeywords(1_000_000, 0)) {
                List<String> phrase = Terms.words(r.str("keyword"));
                if (!phrase.isEmpty()) {
                    v.keywords.put(r.i("id"), phrase);
                }
            }
            return v;
        }

        Map<Integer, Integer> countWords(List<String> tokens) {
            Map<Integer, Integer> hits = new LinkedHashMap<>();
            if (words.isEmpty()) {
                return hits;
            }
            for (String t : tokens) {
                Integer id = words.get(t);
                if (id != null) {
                    hits.merge(id, 1, Integer::sum);
                }
            }
            return hits;
        }

        Map<Integer, Integer> countKeywords(List<String> tokens) {
            Map<Integer, Integer> hits = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<String>> e : keywords.entrySet()) {
                int n = countPhrase(tokens, e.getValue());
                if (n > 0) {
                    hits.put(e.getKey(), n);
                }
            }
            return hits;
        }
    }
}
