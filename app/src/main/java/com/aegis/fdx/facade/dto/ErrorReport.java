package com.aegis.fdx.facade.dto;

import java.util.List;
import java.util.Map;

/**
 * Processing failures, grouped for the error destination.
 *
 * @param byStatus  counts per non-indexed status (Error, Locked, Unsupported)
 * @param byCause   counts per normalised failure cause, to expose recurring patterns
 * @param entries   the individual failures
 */
public record ErrorReport(Map<String, Integer> byStatus, Map<String, Integer> byCause,
                          List<Entry> entries) {

    /** One item that did not index cleanly. */
    public record Entry(String itemId, String name, String extension, String status,
                        String cause, String path) {
    }

    public ErrorReport {
        byStatus = byStatus == null ? Map.of() : Map.copyOf(byStatus);
        byCause = byCause == null ? Map.of() : Map.copyOf(byCause);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public int total() {
        return entries.size();
    }

    public boolean isClean() {
        return entries.isEmpty();
    }
}
