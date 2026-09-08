package com.aegis.fdx.facade;

import java.time.LocalDate;

/**
 * A search request.
 *
 * <p>Java-native replacement for a long positional parameter list: the same filters
 * the search screen offers, expressed as a fluent immutable-by-convention value object
 * so a call site reads as its own documentation.
 *
 * <pre>{@code
 * SearchCriteria c = SearchCriteria.of("invoice")
 *         .fileType("pdf")
 *         .source(sourceId)
 *         .between(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31))
 *         .sortBy(SortField.DATE, SortOrder.DESCENDING)
 *         .page(0, 50);
 * }</pre>
 */
public final class SearchCriteria {

    /** Default page size when none is given. */
    public static final int DEFAULT_PAGE_SIZE = 100;

    private final String query;
    private String fileType;
    private Integer sourceId;
    private Integer aspectId;
    private Integer categoryId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private SortField sortField = SortField.RELEVANCE;
    private SortOrder sortOrder = SortOrder.DESCENDING;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private int offset;
    private boolean includeHidden;

    private SearchCriteria(String query) {
        this.query = Validate.required(query, "query");
    }

    /** Starts a criteria for a query string. */
    public static SearchCriteria of(String query) {
        return new SearchCriteria(query);
    }

    public SearchCriteria fileType(String type) {
        this.fileType = (type == null || type.isBlank()) ? null : type.trim().toLowerCase();
        return this;
    }

    public SearchCriteria source(Integer sourceId) {
        this.sourceId = sourceId;
        return this;
    }

    public SearchCriteria aspect(Integer aspectId) {
        this.aspectId = aspectId;
        return this;
    }

    public SearchCriteria category(Integer categoryId) {
        this.categoryId = categoryId;
        return this;
    }

    public SearchCriteria from(LocalDate date) {
        this.dateFrom = date;
        return this;
    }

    public SearchCriteria to(LocalDate date) {
        this.dateTo = date;
        return this;
    }

    public SearchCriteria between(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw FacadeException.validation("date range ends before it starts");
        }
        this.dateFrom = from;
        this.dateTo = to;
        return this;
    }

    public SearchCriteria sortBy(SortField field) {
        return sortBy(field, this.sortOrder);
    }

    public SearchCriteria sortBy(SortField field, SortOrder order) {
        if (field == null || order == null) {
            throw FacadeException.validation("sort field and order are required");
        }
        this.sortField = field;
        this.sortOrder = order;
        return this;
    }

    /** @param pageIndex zero-based page number */
    public SearchCriteria page(int pageIndex, int pageSize) {
        if (pageIndex < 0) {
            throw FacadeException.validation("page index must not be negative");
        }
        this.pageSize = Validate.limit(pageSize);
        this.offset = pageIndex * this.pageSize;
        return this;
    }

    public SearchCriteria limit(int pageSize) {
        this.pageSize = Validate.limit(pageSize);
        return this;
    }

    public SearchCriteria offset(int offset) {
        this.offset = Validate.offset(offset);
        return this;
    }

    /** Hidden elements are excluded by default. */
    public SearchCriteria includeHidden(boolean include) {
        this.includeHidden = include;
        return this;
    }

    public String query() {
        return query;
    }

    public String fileType() {
        return fileType;
    }

    public Integer sourceId() {
        return sourceId;
    }

    public Integer aspectId() {
        return aspectId;
    }

    public Integer categoryId() {
        return categoryId;
    }

    public LocalDate dateFrom() {
        return dateFrom;
    }

    public LocalDate dateTo() {
        return dateTo;
    }

    public SortField sortField() {
        return sortField;
    }

    public SortOrder sortOrder() {
        return sortOrder;
    }

    public int pageSize() {
        return pageSize;
    }

    public int offset() {
        return offset;
    }

    public boolean hiddenIncluded() {
        return includeHidden;
    }

    @Override
    public String toString() {
        return "SearchCriteria[" + query + ", type=" + fileType + ", source=" + sourceId
                + ", aspect=" + aspectId + ", sort=" + sortField + " " + sortOrder
                + ", offset=" + offset + ", size=" + pageSize + "]";
    }
}
