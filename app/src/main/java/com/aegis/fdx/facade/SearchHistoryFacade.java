package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.SavedSearchDto;
import com.aegis.fdx.facade.dto.SearchHistoryDto;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Records executed searches and stores named searches for reuse.
 */
public final class SearchHistoryFacade {

    private final CorpusDatabase db;

    public SearchHistoryFacade(CorpusDatabase db) {
        this.db = db;
    }

    // ---- history ---------------------------------------------------------

    /** Records that a search ran. */
    public int addSearch(String query, int resultCount) {
        return addSearch(query, resultCount, null);
    }

    public int addSearch(String query, int resultCount, String userId) {
        String q = Validate.required(query, "query");
        if (resultCount < 0) {
            throw FacadeException.validation("result_count must not be negative");
        }
        try {
            return db.insertHistory(q, resultCount, userId, Instant.now());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to record search", e);
        }
    }

    /** The 20 most recent searches. */
    public List<SearchHistoryDto> getHistory() {
        return getHistory(20, null);
    }

    public List<SearchHistoryDto> getHistory(int limit, String userId) {
        int lim = Validate.limit(limit);
        try {
            List<CorpusDatabase.Row> rows = db.selectHistory(lim, userId);
            List<SearchHistoryDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(new SearchHistoryDto(r.i("id"), r.str("query"),
                        r.i("result_count"), r.str("user_id"), r.instant("searched_at")));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load search history", e);
        }
    }

    /** Clears all recorded history. */
    public void clearHistory() {
        clearHistory(null);
    }

    public void clearHistory(String userId) {
        try {
            db.clearHistory(userId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to clear search history", e);
        }
    }

    // ---- saved searches --------------------------------------------------

    /** Stores a named search. */
    public int saveSearch(String name, String query) {
        return saveSearch(name, query, null, null);
    }

    public int saveSearch(String name, String query, String filters, String userId) {
        String n = Validate.required(name, "name");
        String q = Validate.required(query, "query");
        try {
            return db.insertSavedSearch(n, q, filters, userId, Instant.now());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to save search", e);
        }
    }

    /** All saved searches. */
    public List<SavedSearchDto> getSavedSearches() {
        return getSavedSearches(null);
    }

    public List<SavedSearchDto> getSavedSearches(String userId) {
        try {
            List<CorpusDatabase.Row> rows = db.selectSavedSearches(userId);
            List<SavedSearchDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(toDto(r));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list saved searches", e);
        }
    }

    /** @throws FacadeException NOT_FOUND if no such saved search exists */
    public SavedSearchDto getSavedSearch(int searchId) {
        Validate.positiveId(searchId, "searchId");
        try {
            CorpusDatabase.Row r = db.selectSavedSearchById(searchId);
            if (r == null) {
                throw FacadeException.notFound("saved search", searchId);
            }
            return toDto(r);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load saved search", e);
        }
    }

    /** Updates the supplied fields only; nulls are left unchanged. */
    public boolean updateSavedSearch(int searchId, String name, String query, String filters) {
        Validate.positiveId(searchId, "searchId");
        if (name == null && query == null && filters == null) {
            throw FacadeException.validation("at least one field must be supplied");
        }
        try {
            return db.updateSavedSearch(searchId, name, query, filters);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to update saved search", e);
        }
    }

    /** @return true if a row was removed */
    public boolean deleteSavedSearch(int searchId) {
        Validate.positiveId(searchId, "searchId");
        try {
            return db.deleteSavedSearch(searchId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to delete saved search", e);
        }
    }

    /** Records a use, bumping the counter and last-used timestamp. */
    public void markUsed(int searchId) {
        Validate.positiveId(searchId, "searchId");
        try {
            db.markSavedSearchUsed(searchId, Instant.now());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to mark saved search used", e);
        }
    }

    private static SavedSearchDto toDto(CorpusDatabase.Row r) {
        return new SavedSearchDto(r.i("id"), r.str("name"), r.str("query"),
                r.str("filters"), r.str("user_id"),
                r.instant("created_at"), r.instant("last_used"), r.i("use_count"));
    }
}
