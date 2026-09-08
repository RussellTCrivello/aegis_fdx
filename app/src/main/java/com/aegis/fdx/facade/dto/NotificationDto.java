package com.aegis.fdx.facade.dto;

import java.time.Instant;

/** A notification raised by the system. */
public record NotificationDto(
        int id,
        String alertType,
        String priority,
        String title,
        String message,
        String fileId,
        boolean read,
        boolean dismissed,
        Instant createdAt,
        Instant eventDate) {
}
