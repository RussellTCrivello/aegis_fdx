package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.WordDto;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages categories: named groupings of vocabulary.
 *
 * <p>A category is identified by the word that names it, so {@link #createCategory}
 * takes that word and creates it if needed. Words are then linked to the category to
 * build up its membership.
 */
public final class CategoryFacade {

    private final CorpusDatabase db;

    public CategoryFacade(CorpusDatabase db) {
        this.db = db;
    }

    /** Creates a category named by the given word, creating the word if needed. */
    /**
     * Creates a category.
     *
     * <p>A category is exactly one word — it is a point in the relationship graph, and a
     * phrase cannot be one. A phrase of three or more words is a keyword; see
     * {@link Terms}.
     */
    public int createCategory(String categoryWord) {
        String w = Terms.requireCategory(categoryWord, "categoryWord");
        try {
            int wordId = db.insertWord(w);
            return db.insertCategory(wordId);
        } catch (SQLException e) {
            String m = e.getMessage();
            if (m != null && m.contains("UNIQUE")) {
                throw FacadeException.conflict("category \"" + w + "\" already exists");
            }
            throw FacadeException.internal("failed to create category", e);
        }
    }

    /** First page of categories, default size 100. */
    public Page<CategoryDto> listCategories() {
        return listCategories(100, 0);
    }

    public Page<CategoryDto> listCategories(int limit, int offset) {
        int lim = Validate.limit(limit);
        int off = Validate.offset(offset);
        try {
            List<CorpusDatabase.Row> rows = db.selectAllCategories(lim, off);
            List<CategoryDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(toDto(r));
            }
            return new Page<>(out, db.countCategories());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list categories", e);
        }
    }

    /** @throws FacadeException NOT_FOUND if no such category exists */
    public CategoryDto getCategory(int categoryId) {
        Validate.positiveId(categoryId, "categoryId");
        try {
            CorpusDatabase.Row r = db.selectCategoryById(categoryId);
            if (r == null) {
                throw FacadeException.notFound("category", categoryId);
            }
            return toDto(r);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load category", e);
        }
    }

    /** @return true if a category with this name exists */
    public boolean categoryExists(String categoryWord) {
        String w = Validate.required(categoryWord, "categoryWord");
        try {
            return db.selectCategoryByWord(w) != null;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to check category", e);
        }
    }

    /** @return true if a row was removed */
    public boolean deleteCategory(int categoryId) {
        Validate.positiveId(categoryId, "categoryId");
        try {
            return db.deleteCategory(categoryId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to delete category", e);
        }
    }

    /** Adds a word to a category, creating the word if needed. A category word is one word. */
    public boolean linkWordToCategory(String word, String categoryWord) {
        String w = Terms.requireSingleWord(word, "word");
        String cw = Terms.requireCategory(categoryWord, "categoryWord");
        try {
            int wordId = db.insertWord(w);
            CorpusDatabase.Row cat = db.selectCategoryByWord(cw);
            if (cat == null) {
                throw FacadeException.notFound("category", cw);
            }
            return db.linkWordToCategory(wordId, cat.i("id"));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to link word to category", e);
        }
    }

    /** Words belonging to a category. */
    public Page<WordDto> getCategoryWords(int categoryId, int limit, int offset) {
        Validate.positiveId(categoryId, "categoryId");
        int lim = Validate.limit(limit);
        int off = Validate.offset(offset);
        try {
            List<CorpusDatabase.Row> rows = db.selectCategoryWords(categoryId, lim, off);
            List<WordDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(new WordDto(r.i("id"), r.str("word")));
            }
            return new Page<>(out, out.size());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list category words", e);
        }
    }

    /** @return true if the link was removed */
    public boolean removeWordFromCategory(int categoryId, int wordId) {
        Validate.positiveId(categoryId, "categoryId");
        Validate.positiveId(wordId, "wordId");
        try {
            return db.unlinkWordFromCategory(categoryId, wordId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to unlink word", e);
        }
    }

    private static CategoryDto toDto(CorpusDatabase.Row r) {
        return new CategoryDto(r.i("id"), r.i("word_id"), r.str("word"));
    }

    /** Categories whose word differs only by case, mapped to how many there are. */
    public Map<String, Integer> findDuplicates() {
        try {
            Map<String, Integer> out = new java.util.LinkedHashMap<>();
            for (CorpusDatabase.Row r : db.findDuplicateCategories()) {
                out.put(r.str("normalized"), r.i("occurrences"));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to find duplicate categories", e);
        }
    }

    /** Merges every duplicate group into its oldest category. Returns categories removed. */
    public int mergeDuplicates() {
        try {
            int removed = 0;
            for (CorpusDatabase.Row g : db.findDuplicateCategories()) {
                int keep = g.i("keep_id");
                for (String id : g.str("ids").split(",")) {
                    int from = Integer.parseInt(id.trim());
                    if (from != keep) {
                        db.mergeCategory(from, keep);
                        removed++;
                    }
                }
            }
            return removed;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to merge duplicate categories", e);
        }
    }
}
