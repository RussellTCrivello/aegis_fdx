package com.aegis.fdx.facade.dto;

import java.time.LocalDate;

/**
 * An extracted text payload held against a registered file.
 */
public record ContentDto(
        int id,
        String contentData,
        LocalDate contentDate,
        int pathId) {
}
