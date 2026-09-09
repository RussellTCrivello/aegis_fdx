package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.WordDto;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the vocabulary: individual words that can be grouped into categories.
 *
 * <p>Word creation is idempotent — a word already present returns its existing id
 * rather than failing, because the same term is frequently submitted from several
 * places.
 */
public final class WordFacade {

    private final CorpusDatabase db;
    private final RelationshipAnalyzer analyzer;

    public WordFacade(CorpusDatabase db) {
        this(db, null);
    }

    public WordFacade(CorpusDatabase db, RelationshipAnalyzer analyzer) {
        this.db = db;
        this.analyzer = analyzer;
    }

    /** First page of words, default size 100. */
    public Page<WordDto> getWords() {
        return getWords(100, 0);
    }

    public Page<WordDto> getWords(int limit, int offset) {
        return searchWords(null, limit, offset);
    }

    /** Substring search; a null or blank term lists everything. */
    public Page<WordDto> searchWords(String query, int limit, int offset) {
        int lim = Validate.limit(limit);
        int off = Validate.offset(offset);
        try {
            List<CorpusDatabase.Row> rows = db.searchWords(query, lim, off);
            List<WordDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(new WordDto(r.i("id"), r.str("word")));
            }
            return new Page<>(out, db.countWords(query));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to search words", e);
        }
    }

    /** @throws FacadeException NOT_FOUND if no such word exists */
    public WordDto getWord(int wordId) {
        Validate.positiveId(wordId, "wordId");
        try {
            CorpusDatabase.Row r = db.selectWordById(wordId);
            if (r == null) {
                throw FacadeException.notFound("word", wordId);
            }
            return new WordDto(r.i("id"), r.str("word"));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load word", e);
        }
    }

    /**
     * Creates a word, or returns the id of the existing one.
     *
     * <p>Idempotent by design: the table has a UNIQUE constraint and callers routinely
     * submit terms that already exist.
     */
    /** Adds a word to the vocabulary. A word is one word; a phrase is a keyword. */
    public int createWord(String word) {
        String w = Terms.requireSingleWord(word, "word");
        try {
            int wordId = db.insertWord(w);
            if (analyzer != null && wordId > 0) {
                analyzer.analyzeWord(wordId);
            }
            return wordId;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to create word", e);
        }
    }

    /** @return true if the row was updated */
    public boolean updateWord(int wordId, String word) {
        Validate.positiveId(wordId, "wordId");
        String w = Terms.requireSingleWord(word, "word");
        try {
            boolean updated = db.updateWord(wordId, w);
            if (updated && analyzer != null) {
                analyzer.analyzeWord(wordId);
            }
            return updated;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to update word", e);
        }
    }

    /** @return true if a row was removed */
    public boolean deleteWord(int wordId) {
        Validate.positiveId(wordId, "wordId");
        try {
            return db.deleteWord(wordId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to delete word", e);
        }
    }

    /** @return how many of the given words were actually removed */
    public int bulkDeleteWords(List<Integer> wordIds) {
        if (wordIds == null || wordIds.isEmpty()) {
            throw FacadeException.validation("word_ids is required");
        }
        int removed = 0;
        for (Integer id : wordIds) {
            Validate.positiveId(id, "wordId");
            if (deleteWord(id)) {
                removed++;
            }
        }
        return removed;
    }
}
