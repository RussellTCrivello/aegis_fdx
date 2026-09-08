package com.aegis.fdx.facade.dto;

import java.time.Instant;

/** A record that a search was executed. */
public record SearchHistoryDto(
        int id,
        String query,
        int resultCount,
        String userId,
        Instant searchedAt) {
}
