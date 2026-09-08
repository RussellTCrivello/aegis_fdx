package com.aegis.fdx.facade.dto;

import java.time.LocalDate;

/** @deprecated superseded by {@link AspectDto}; retained for source compatibility. */
public record SideDto(
        int id,
        String name,
        double importance,
        LocalDate dateCreation) {
}
