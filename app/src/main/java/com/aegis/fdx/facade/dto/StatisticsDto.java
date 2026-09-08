package com.aegis.fdx.facade.dto;

import java.util.Map;

/**
 * Processing counters.
 */
public record StatisticsDto(
        long total,
        long completed,
        long failed,
        long duplicates,
        long pending,
        /* Remaining engine-specific counters, keyed by name. */
        Map<String, Long> extra) {

    public StatisticsDto {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** Looks a counter up by name. */
    public long get(String key) {
        return switch (key) {
            case "total" -> total;
            case "completed" -> completed;
            case "failed" -> failed;
            case "duplicates" -> duplicates;
            case "pending" -> pending;
            default -> extra.getOrDefault(key, 0L);
        };
    }
}
