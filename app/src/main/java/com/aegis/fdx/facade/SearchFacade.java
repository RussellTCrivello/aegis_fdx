package com.aegis.fdx.facade;

import com.aegis.fdx.engine.Filters;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.engine.SearchHit;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.index.QuerySyntaxException;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Full-text search over everything the pipeline has indexed.
 *
 * <p>Accepts the complete query grammar the engine supports: bare terms, quoted
 * phrases, wildcards, {@code term~} fuzzy matching, {@code "a b"~5} proximity,
 * boolean operators with parentheses, field-scoped terms and {@code /regex/}. An
 * unparseable query raises {@link FacadeException.Kind#VALIDATION} rather than
 * quietly returning nothing.
 *
 * <p>Source and aspect filters are given as ids and resolved to the index fields the
 * engine partitions by. The engine itself is untouched: this class composes
 * {@link Filters} and calls the existing {@link LiveCase#searchNow}.
 */
public final class SearchFacade {


    /**
     * Upper bound on how many hits are pulled into memory to satisfy a non-relevance
     * ordering. Beyond this the ordering is over the top {@code MAX_SORT_POOL} matches,
     * which keeps a 10M-item case from materialising every hit for one page.
     */
    private static final int MAX_SORT_POOL = 10_000;

    private final LiveCase liveCase;
    private final SourceFacade sources;
    private final AspectFacade aspects;

    public SearchFacade(LiveCase liveCase) {
        this(liveCase, null, null);
    }

    /**
     * @param sources used to resolve a source id to its name; may be null if the
     *                caller never filters by source
     * @param sides   likewise for {@code aspect_id}
     */
    public SearchFacade(LiveCase liveCase, SourceFacade sources, AspectFacade aspects) {
        this.liveCase = liveCase;
        this.sources = sources;
        this.aspects = aspects;
    }

    // ---------------------------------------------------------------- search

    /**
     * Runs a search described by {@link SearchCriteria}.
     *
     * <p>This is the primary entry point. Filters, sorting and paging all travel in the
     * criteria object, so adding a filter later does not change this signature.
     *
     * @return one page of results plus the total match count
     * @throws FacadeException with {@link FacadeException.Kind#VALIDATION} if the query
     *         cannot be parsed or a filter is invalid
     */
    public Page<SearchResultDto> search(SearchCriteria criteria) {
        if (criteria == null) {
            throw FacadeException.validation("criteria is required");
        }
        return execute(criteria);
    }

    /** Convenience for a plain keyword search with defaults. */
    public Page<SearchResultDto> search(String query) {
        return execute(SearchCriteria.of(query));
    }

    /** Convenience for a plain keyword search with explicit paging. */
    public Page<SearchResultDto> search(String query, int pageIndex, int pageSize) {
        return execute(SearchCriteria.of(query).page(pageIndex, pageSize));
    }

    // ------------------------------------------------------------ suggestions

    /** Completions for a partial term. */
    public List<String> autocomplete(String prefix) {
        return autocomplete(prefix, 10);
    }

    public List<String> autocomplete(String prefix, int limit) {
        String p = Validate.required(prefix, "prefix");
        int lim = Validate.limit(limit);
        Page<SearchResultDto> page = execute(SearchCriteria.of(p + "*").limit(lim));
        List<String> out = new ArrayList<>();
        for (SearchResultDto r : page.results()) {
            if (!out.contains(r.fileName())) {
                out.add(r.fileName());
            }
        }
        return out;
    }

    /** Near-miss suggestions for a query that found little. */
    public List<String> getSearchSuggestions(String query) {
        return getSearchSuggestions(query, 5);
    }

    public List<String> getSearchSuggestions(String query, int limit) {
        String q = Validate.required(query, "query");
        int lim = Validate.limit(limit);
        List<String> out = new ArrayList<>();
        // Fuzzy variant gives the "did you mean" behaviour.
        try {
            Page<SearchResultDto> fuzzy = execute(SearchCriteria.of(q + "~").limit(lim));
            for (SearchResultDto r : fuzzy.results()) {
                if (!out.contains(r.fileName())) {
                    out.add(r.fileName());
                }
            }
        } catch (FacadeException ignored) {
            // a term that cannot be fuzzed simply yields no suggestions
        }
        return out.size() > lim ? out.subList(0, lim) : out;
    }

    // ---------------------------------------------------------------- engine

    private Page<SearchResultDto> execute(SearchCriteria c) {
        Filters filters = new Filters();
        if (c.fileType() != null) {
            filters.types.add(c.fileType());
        }
        if (c.sourceId() != null) {
            filters.custodians.add(resolveSourceName(c.sourceId()));
        }
        if (c.aspectId() != null) {
            filters.container = resolveAspectName(c.aspectId());
        }
        if (c.dateFrom() != null) {
            filters.dateFrom = c.dateFrom().atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (c.dateTo() != null) {
            filters.dateTo = c.dateTo().plusDays(1).atStartOfDay()
                    .toInstant(ZoneOffset.UTC).minusMillis(1);
        }
        if (c.categoryId() != null) {
            // A category filter narrows to the elements classified under it.
            filters.tags.add(String.valueOf(c.categoryId()));
        }

        int lim = c.pageSize();
        int off = c.offset();

        try {
            // The engine's sortMode parameter takes UI labels and silently falls back to
            // relevance otherwise, so ordering is applied here instead. Passing null asks
            // for plain relevance order.
            //
            // Sorting must see every match, not just the requested window: ordering a
            // truncated set would make offset paging return overlapping rows.
            LiveCase.SearchPage page =
                    liveCase.searchNow(c.query(), filters, c.hiddenIncluded(), null, lim + off);
            int total = (int) page.totalHits();

            List<SearchHit> hits = new ArrayList<>(page.hits());
            boolean needsFullPool = c.sortField() != SortField.RELEVANCE
                    || c.sortOrder() == SortOrder.ASCENDING
                    || off > 0;
            if (needsFullPool && total > hits.size()) {
                int pool = Math.min(total, MAX_SORT_POOL);
                hits = new ArrayList<>(liveCase
                        .searchNow(c.query(), filters, c.hiddenIncluded(), null, pool).hits());
            }
            sort(hits, c.sortField(), c.sortOrder());

            List<SearchResultDto> window = new ArrayList<>();
            for (int i = off; i < hits.size() && window.size() < lim; i++) {
                window.add(toDto(hits.get(i)));
            }
            return new Page<>(window, total);
        } catch (QuerySyntaxException e) {
            throw FacadeException.validation("invalid query: " + e.getMessage());
        } catch (Exception e) {
            if (e.getCause() instanceof QuerySyntaxException qse) {
                throw FacadeException.validation("invalid query: " + qse.getMessage());
            }
            throw FacadeException.internal("search failed", e);
        }
    }

    private static void sort(List<SearchHit> hits, SortField field, SortOrder order) {
        Comparator<SearchHit> cmp = switch (field) {
            case DATE -> Comparator.comparing(
                    h -> h.item().modified() == null ? Instant.EPOCH : h.item().modified());
            case NAME -> Comparator.comparing(
                    h -> h.item().name() == null ? "" : h.item().name(),
                    String.CASE_INSENSITIVE_ORDER);
            case TYPE -> Comparator.comparing(
                    h -> h.item().extension() == null ? "" : h.item().extension(),
                    String.CASE_INSENSITIVE_ORDER);
            case SIZE -> Comparator.comparingLong(h -> h.item().size());
            case RELEVANCE -> Comparator.comparingDouble(SearchHit::score);
        };
        // Deterministic secondary sort so equal keys never reorder between calls.
        cmp = cmp.thenComparing(h -> h.item().id() == null ? "" : h.item().id());
        if (order == SortOrder.DESCENDING) {
            cmp = cmp.reversed();
        }
        hits.sort(cmp);
    }

    private String resolveSourceName(Integer sourceId) {
        Validate.positiveId(sourceId, "sourceId");
        if (sources == null) {
            throw FacadeException.unsupported(
                    "source_id filtering requires a SourceFacade; construct SearchFacade "
                            + "with the three-argument constructor");
        }
        return sources.getSource(sourceId).name();
    }

    private String resolveAspectName(Integer aspectId) {
        Validate.positiveId(aspectId, "aspectId");
        if (aspects == null) {
            throw FacadeException.unsupported(
                    "aspect_id filtering requires a SideFacade; construct SearchFacade "
                            + "with the three-argument constructor");
        }
        return aspects.getAspect(aspectId).name();
    }

    private static SearchResultDto toDto(SearchHit hit) {
        Item it = hit.item();
        ItemStatus st = it.status();
        return new SearchResultDto(
                it.id(),
                it.name(),
                it.sourcePath(),
                it.extension(),
                it.size(),
                it.custodian(),
                it.containerPath(),
                hit.score(),
                hit.hitCount(),
                hit.fragments(),
                st == null ? null : st.label(),
                it.custodian(),
                it.md5(),
                it.sha256());
    }
}
