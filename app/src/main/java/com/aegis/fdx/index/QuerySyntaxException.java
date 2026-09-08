package com.aegis.fdx.index;

import java.util.List;

/**
 * Raised when a user query cannot be compiled to a well-defined search.
 *
 * <p>Forensic search must never guess. A query the compiler does not fully understand is
 * rejected with an explanation rather than silently reinterpreted, because a reviewer who
 * mistypes {@code custodain:Smith} would otherwise receive a plausible-looking full-text
 * result set for "smith" and could reasonably conclude the custodian has only those items.
 * Both over- and under-collection are defensible only if the reviewer knows they happened.
 */
public final class QuerySyntaxException extends RuntimeException {

    private final String query;
    private final List<String> suggestions;

    public QuerySyntaxException(String message, String query) {
        this(message, query, List.of());
    }

    public QuerySyntaxException(String message, String query, List<String> suggestions) {
        super(message);
        this.query = query == null ? "" : query;
        this.suggestions = List.copyOf(suggestions);
    }

    /** The original, unmodified user query. */
    public String query() { return query; }

    /** Near-miss field names, when the failure was an unrecognised field. */
    public List<String> suggestions() { return suggestions; }

    /** Single-line message suitable for a UI toast or a CLI stderr line. */
    public String displayMessage() {
        if (suggestions.isEmpty()) return getMessage();
        return getMessage() + " Did you mean: " + String.join(", ", suggestions) + "?";
    }
}
