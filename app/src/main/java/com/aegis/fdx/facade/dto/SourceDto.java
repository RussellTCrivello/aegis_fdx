package com.aegis.fdx.facade.dto;

import java.time.LocalDate;

/**
 * A source: a person, organisation or system that material originates from.
 */
public record SourceDto(
        int id,
        String name,
        String country,
        String job,
        double importance,
        String city,
        String description,
        String accounts,
        String note,
        String attachments,
        String ownership,
        String accessStatus,
        LocalDate entryDate,
        Integer categoryId) {
}
