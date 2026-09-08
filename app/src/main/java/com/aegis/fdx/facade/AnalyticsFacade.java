package com.aegis.fdx.facade;

import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.dto.CategoryUsage;
import com.aegis.fdx.facade.dto.EntityStatistics;
import com.aegis.fdx.facade.dto.ErrorReport;
import com.aegis.fdx.facade.dto.KeywordUsage;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.PathNode;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregations behind the detail, relationship and analytics destinations.
 *
 * <p>Everything here derives from data the engine and registry already hold; this
 * facade adds no storage. It exists so screens ask for a rollup once rather than
 * assembling one from several calls.
 */
public final class AnalyticsFacade {

    private final CorpusDatabase db;
    private final LiveCase liveCase;

    public AnalyticsFacade(CorpusDatabase db, LiveCase liveCase) {
        this.db = db;
        this.liveCase = liveCase;
    }

    // ------------------------------------------------------------ statistics

    /** Rollup for one source: files, bytes, types, review progress, date span. */
    public EntityStatistics sourceStatistics(int sourceId) {
        Validate.positiveId(sourceId, "sourceId");
        try {
            return toStats(db.selectSourceStatistics(sourceId),
                    db.countTypesForSource(sourceId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to compute source statistics", e);
        }
    }

    /** Rollup for one aspect. */
    public EntityStatistics aspectStatistics(int aspectId) {
        Validate.positiveId(aspectId, "aspectId");
        try {
            return toStats(db.selectAspectStatistics(aspectId),
                    db.countTypesForAspect(aspectId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to compute aspect statistics", e);
        }
    }

    private static EntityStatistics toStats(CorpusDatabase.Row r, Map<String, Integer> types) {
        if (r == null) {
            return new EntityStatistics(0, 0, 0, 0, null, null, Map.of());
        }
        return new EntityStatistics(
                r.i("files"), r.l("bytes"), r.i("types"), r.i("read_files"),
                parseDate(r.str("earliest")), parseDate(r.str("latest")), types);
    }

    private static LocalDate parseDate(String s) {
        try {
            return (s == null || s.isBlank()) ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --------------------------------------------------------- relationships

    /** Categories appearing in material from one source. */
    public List<CategoryUsage> categoriesForSource(int sourceId) {
        Validate.positiveId(sourceId, "sourceId");
        try {
            return toCategoryUsage(db.selectCategoriesForSource(sourceId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load source categories", e);
        }
    }

    /** Keywords found in material from one source. */
    public List<KeywordUsage> keywordsForSource(int sourceId) {
        Validate.positiveId(sourceId, "sourceId");
        try {
            return toKeywordUsage(db.selectKeywordsForSource(sourceId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load source keywords", e);
        }
    }

    public List<CategoryUsage> categoriesForAspect(int aspectId) {
        Validate.positiveId(aspectId, "aspectId");
        try {
            return toCategoryUsage(db.selectCategoriesForAspect(aspectId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load aspect categories", e);
        }
    }

    public List<KeywordUsage> keywordsForAspect(int aspectId) {
        Validate.positiveId(aspectId, "aspectId");
        try {
            return toKeywordUsage(db.selectKeywordsForAspect(aspectId));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load aspect keywords", e);
        }
    }

    /** Categories a word belongs to. */
    public List<CategoryUsage> categoriesForWord(int wordId) {
        Validate.positiveId(wordId, "wordId");
        try {
            List<CategoryUsage> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectCategoriesForWord(wordId)) {
                out.add(new CategoryUsage(r.i("id"), r.str("word"), 0));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load word categories", e);
        }
    }

    /** Files classified under one category. */
    public List<PathDto> filesForCategory(int categoryId, int limit) {
        Validate.positiveId(categoryId, "categoryId");
        try {
            List<PathDto> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectPathsForCategory(categoryId,
                    Validate.limit(limit))) {
                out.add(ContentFacade.rowToPath(r));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load category files", e);
        }
    }

    /** Files containing one keyword, with per-file hit counts. */
    public List<KeywordUsage.FileHit> filesForKeyword(int keywordId, int limit) {
        Validate.positiveId(keywordId, "keywordId");
        try {
            List<KeywordUsage.FileHit> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectPathsForKeyword(keywordId,
                    Validate.limit(limit))) {
                out.add(new KeywordUsage.FileHit(r.i("id"), r.str("file_name"),
                        r.str("source_name"), r.i("hits")));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load keyword files", e);
        }
    }

    /**
     * §15: every keyword with its case-wide count of distinct files, largest first.
     *
     * <p>The count is global to the case, not scoped to whatever the caller is looking
     * at, and it is a count of distinct files rather than of occurrences. This replaces
     * the pattern of listing keywords and then querying each one's files — see
     * {@link CorpusDatabase#selectKeywordUsage} for why that mattered.
     */
    public List<KeywordUsage> keywordUsage(int limit, int offset) {
        try {
            List<KeywordUsage> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectKeywordUsage(
                    Validate.limit(limit), Math.max(0, offset))) {
                out.add(new KeywordUsage(r.i("id"), r.str("keyword"), r.str("category_word"),
                        r.i("hits"), r.i("files"), List.of()));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load keyword usage", e);
        }
    }

    /** §15: every category with its case-wide count of distinct related files. */
    public List<CategoryUsage> categoryUsage(int limit, int offset) {
        try {
            return toCategoryUsage(db.selectCategoryUsage(
                    Validate.limit(limit), Math.max(0, offset)));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load category usage", e);
        }
    }

    private static List<CategoryUsage> toCategoryUsage(List<CorpusDatabase.Row> rows) {
        List<CategoryUsage> out = new ArrayList<>();
        for (CorpusDatabase.Row r : rows) {
            out.add(new CategoryUsage(r.i("id"), r.str("word"), r.i("files")));
        }
        return out;
    }

    private static List<KeywordUsage> toKeywordUsage(List<CorpusDatabase.Row> rows) {
        List<KeywordUsage> out = new ArrayList<>();
        for (CorpusDatabase.Row r : rows) {
            out.add(new KeywordUsage(r.i("id"), r.str("keyword"), r.str("category_word"),
                    r.i("hits"), r.i("files"), List.of()));
        }
        return out;
    }

    // ------------------------------------------------------------ path tree

    /**
     * Builds a directory tree from the registry, aggregating counts and bytes upward.
     *
     * @param sourceId optional source restriction
     * @param aspectId optional aspect restriction
     */
    public PathNode directoryTree(Integer sourceId, Integer aspectId) {
        try {
            PathNode root = PathNode.folder("All material", "");
            for (CorpusDatabase.Row r : db.selectAllPathsForTree(sourceId, aspectId)) {
                String full = r.str("file_path");
                if (full == null || full.isBlank()) {
                    full = r.str("file_name");
                }
                insert(root, full, r);
            }
            root.recomputeTotals();
            return root;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to build the directory tree", e);
        }
    }

    private static void insert(PathNode root, String fullPath, CorpusDatabase.Row r) {
        String normalised = fullPath.replace('\\', '/');
        int lastSlash = normalised.lastIndexOf('/');
        String dir = lastSlash > 0 ? normalised.substring(0, lastSlash) : "";
        String[] parts = dir.isEmpty() ? new String[0] : dir.split("/");

        PathNode cur = root;
        StringBuilder acc = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            acc.append('/').append(part);
            cur = cur.childFolder(part, acc.toString());
        }
        cur.addFile(new PathNode.FileEntry(
                r.i("id"), r.str("file_name"), r.str("file_type"),
                r.l("file_size"), r.str("file_status"), r.str("element_id"),
                r.str("source_name"), r.str("aspect_name")));
    }

    /**
     * Builds a container tree from the engine's own nesting model.
     *
     * <p>Uses {@code parentId} and {@code depth}, which the engine records when it
     * expands archives and mailboxes. This is a capability the reference application
     * does not have, surfaced here rather than discarded.
     */
    public PathNode archiveTree() {
        try {
            Map<String, List<Item>> byParent = new LinkedHashMap<>();
            List<Item> roots = new ArrayList<>();
            for (Item it : liveCase.allItems()) {
                String parent = it.parentId();
                if (parent == null || parent.isBlank()) {
                    roots.add(it);
                } else {
                    byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(it);
                }
            }
            PathNode root = PathNode.folder("Containers", "");
            for (Item it : roots) {
                addItemNode(root, it, byParent);
            }
            root.recomputeTotals();
            return root;
        } catch (Exception e) {
            throw FacadeException.internal("failed to build the container tree", e);
        }
    }

    private static void addItemNode(PathNode parent, Item it, Map<String, List<Item>> byParent) {
        List<Item> children = byParent.get(it.id());
        if (children == null || children.isEmpty()) {
            parent.addFile(new PathNode.FileEntry(0, it.name(), it.extension(),
                    it.size(), String.valueOf(it.status()), it.id(), it.custodian(), null));
            return;
        }
        PathNode node = parent.childFolder(it.name(), it.id());
        node.markContainer(it.id(), it.extension(), it.size());
        for (Item child : children) {
            addItemNode(node, child, byParent);
        }
    }

    // -------------------------------------------------------------- errors

    /**
     * Processing failures grouped by cause.
     *
     * <p>Surfaces {@code Item.errors()}, which the engine has always recorded but which
     * no screen previously displayed.
     */
    public ErrorReport errorReport() {
        try {
            Map<String, Integer> byStatus = new LinkedHashMap<>();
            Map<String, Integer> byCause = new TreeMap<>();
            List<ErrorReport.Entry> entries = new ArrayList<>();

            for (Item it : liveCase.allItems()) {
                ItemStatus st = it.status();
                if (st == ItemStatus.INDEXED || st == null) {
                    continue;
                }
                byStatus.merge(st.label(), 1, Integer::sum);
                String cause = causeOf(it);
                byCause.merge(cause, 1, Integer::sum);
                entries.add(new ErrorReport.Entry(it.id(), it.name(), it.extension(),
                        st.label(), cause, it.sourcePath()));
            }
            return new ErrorReport(byStatus, byCause, entries);
        } catch (Exception e) {
            throw FacadeException.internal("failed to build the error report", e);
        }
    }

    /** Normalises an error message into a recurring-pattern key. */
    static String causeOf(Item it) {
        List<String> errs = it.errors();
        if (errs == null || errs.isEmpty()) {
            return "(no message)";
        }
        String first = errs.get(0);
        int colon = first.indexOf(':');
        String head = colon > 0 ? first.substring(0, colon) : first;
        return head.length() > 80 ? head.substring(0, 80) : head;
    }

    // ------------------------------------------------- combined dashboard

    /** Counts under a combined filter, for the comprehensive dashboard. */
    public FilteredCounts filteredCounts(Integer sourceId, Integer aspectId,
                                         Integer categoryId, String fileType) {
        try {
            CorpusDatabase.Row r = db.countFiltered(sourceId, aspectId, categoryId, fileType);
            return new FilteredCounts(r == null ? 0 : r.i("files"),
                    r == null ? 0L : r.l("bytes"));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to apply dashboard filters", e);
        }
    }

    /** Result of a combined-filter query. */
    public record FilteredCounts(int files, long bytes) {
    }

    /** Read/unread split across the registry. */
    public Map<String, Integer> reviewProgress() {
        try {
            return db.countByReviewState();
        } catch (SQLException e) {
            throw FacadeException.internal("failed to read review progress", e);
        }
    }
}
