package com.aegis.fdx.facade.dto;

import java.util.List;

/**
 * One page of results plus the total number of matches before paging.
 */
public record Page<T>(List<T> results, int totalCount) {

    public Page {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static <T> Page<T> empty() {
        return new Page<>(List.of(), 0);
    }

    /** Convenience mirroring {@code len(results)} at the call site. */
    public int size() {
        return results.size();
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }
}
