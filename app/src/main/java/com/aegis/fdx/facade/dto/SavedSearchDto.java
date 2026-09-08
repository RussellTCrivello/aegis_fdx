package com.aegis.fdx.facade.dto;

import java.time.Instant;

/** A named search stored for reuse. */
public record SavedSearchDto(
        int id,
        String name,
        String query,
        String filters,
        String userId,
        Instant createdAt,
        Instant lastUsed,
        int useCount) {
}
