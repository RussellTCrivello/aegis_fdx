package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.KeywordDto;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages keywords: phrases of interest, each belonging to a category.
 *
 * <p>Keywords are the searchable vocabulary a reviewer curates; categories give them
 * structure.
 */
public final class KeywordFacade {

    private final CorpusDatabase db;
    private final RelationshipAnalyzer analyzer;

    public KeywordFacade(CorpusDatabase db) {
        this(db, null);
    }

    public KeywordFacade(CorpusDatabase db, RelationshipAnalyzer analyzer) {
        this.db = db;
        this.analyzer = analyzer;
    }

    /** Attaches a phrase to an existing category.
     *
     * @throws FacadeException NOT_FOUND if the category does not exist */
    /**
     * Adds a keyword to a category.
     *
     * <p>A keyword is a phrase of two or more words and a category is a single word:
     * both are checked here, at the boundary, so no path into the case can store a term
     * that contradicts the vocabulary. See {@link Terms}.
     */
    public boolean createKeyword(String keywordPhrase, String categoryWord) {
        String k = Terms.requireKeyword(keywordPhrase, "phrase");
        String cw = Terms.requireCategory(categoryWord, "categoryWord");
        try {
            CorpusDatabase.Row cat = db.selectCategoryByWord(cw);
            if (cat == null) {
                throw FacadeException.notFound("category", cw);
            }
            int keywordId = db.insertKeyword(k, cat.i("id"));
            if (analyzer != null && keywordId > 0) {
                analyzer.analyzeKeyword(keywordId);
            }
            return true;
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                // The same phrase (case-insensitively) already exists: a refusal the
                // operator can act on, not a failure. Duplicate = same relationship twice.
                return false;
            }
            throw FacadeException.internal("failed to create keyword", e);
        }
    }

    /** First page of keywords, default size 100. */
    public Page<KeywordDto> listKeywords() {
        return listKeywords(100, 0);
    }

    public Page<KeywordDto> listKeywords(int limit, int offset) {
        int lim = Validate.limit(limit);
        int off = Validate.offset(offset);
        try {
            List<CorpusDatabase.Row> rows = db.selectAllKeywords(lim, off);
            List<KeywordDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(toDto(r));
            }
            return new Page<>(out, db.countKeywords());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list keywords", e);
        }
    }

    /** @throws FacadeException NOT_FOUND if no such keyword exists */
    public KeywordDto getKeyword(int keywordId) {
        Validate.positiveId(keywordId, "keywordId");
        try {
            CorpusDatabase.Row r = db.selectKeywordById(keywordId);
            if (r == null) {
                throw FacadeException.notFound("keyword", keywordId);
            }
            return toDto(r);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load keyword", e);
        }
    }

    /** @return true if the row was updated */
    public boolean updateKeyword(int keywordId, String keywordPhrase) {
        Validate.positiveId(keywordId, "keywordId");
        String k = Terms.requireKeyword(keywordPhrase, "phrase");
        try {
            boolean updated = db.updateKeyword(keywordId, k);
            if (updated && analyzer != null) {
                analyzer.analyzeKeyword(keywordId);
            }
            return updated;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to update keyword", e);
        }
    }

    /** @return true if a row was removed */
    public boolean deleteKeyword(int keywordId) {
        Validate.positiveId(keywordId, "keywordId");
        try {
            return db.deleteKeyword(keywordId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to delete keyword", e);
        }
    }

    /** @return how many of the given keywords were actually removed */
    public int bulkDeleteKeywords(List<Integer> keywordIds) {
        if (keywordIds == null || keywordIds.isEmpty()) {
            throw FacadeException.validation("keyword_ids is required");
        }
        int removed = 0;
        for (Integer id : keywordIds) {
            Validate.positiveId(id, "keywordId");
            if (deleteKeyword(id)) {
                removed++;
            }
        }
        return removed;
    }

    /** @return true if this exact phrase is already registered */
    public boolean keywordExists(String keywordPhrase) {
        String k = Validate.required(keywordPhrase, "phrase");
        try {
            for (CorpusDatabase.Row r : db.selectAllKeywords(10_000, 0)) {
                if (k.equals(r.str("keyword"))) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to check keyword", e);
        }
    }

    /** Keyword ids grouped by the category that owns them. */
    public Map<Integer, List<Integer>> keywordIdsByCategory() {
        try {
            Map<Integer, List<Integer>> out = new LinkedHashMap<>();
            for (CorpusDatabase.Row r : db.selectAllKeywords(10_000, 0)) {
                out.computeIfAbsent(r.i("category_id"), k -> new ArrayList<>()).add(r.i("id"));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to group keywords", e);
        }
    }

    /** Phrases registered more than once, mapped to their occurrence count. */
    public Map<String, Integer> findDuplicates() {
        try {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (CorpusDatabase.Row r : db.findDuplicateKeywords()) {
                out.put(r.str("keyword"), r.i("occurrences"));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to find duplicate keywords", e);
        }
    }

    /**
     * Merges every case-insensitive duplicate group into its oldest keyword, moving the
     * file edges across. Returns the number of keywords removed.
     */
    public int mergeDuplicates() {
        try {
            int removed = 0;
            for (CorpusDatabase.Row g : db.findDuplicateKeywords()) {
                int keep = g.i("keep_id");
                for (CorpusDatabase.Row k : db.selectKeywordsByNormalizedPhrase(g.str("normalized"))) {
                    if (k.i("id") != keep) {
                        db.mergeKeyword(k.i("id"), keep);
                        removed++;
                    }
                }
            }
            return removed;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to merge duplicate keywords", e);
        }
    }

    private static KeywordDto toDto(CorpusDatabase.Row r) {
        return new KeywordDto(r.i("id"), r.str("keyword"),
                r.i("category_id"), r.str("category_word"));
    }

    private static boolean isUniqueViolation(SQLException e) {
        String m = e.getMessage();
        return m != null && m.contains("UNIQUE");
    }
}
