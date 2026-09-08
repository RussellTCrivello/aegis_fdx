package com.aegis.fdx.facade.dto;

import java.time.LocalDate;

/**
 * An aspect: a party, grouping or viewpoint that ingested material belongs to.
 *
 * <p>Referred to as a "side" in the reference interface; "aspect" is the name used
 * throughout this application.
 */
public record AspectDto(
        int id,
        String name,
        double importance,
        LocalDate dateCreation) {
}
