package com.aegis.fdx.facade.dto;

import java.time.LocalDate;

/**
 * A registry entry tying a processed file to its source, aspect, hash and the
 * pipeline element it came from.
 */
public record PathDto(
        int id,
        String fileName,
        String filePath,
        long fileSize,
        String fileType,
        /* Review state: 'Read' or 'Unread'. */
        String fileStatus,
        LocalDate fileDate,
        LocalDate dateCreation,
        Integer hashId,
        String hashValue,
        String coordinates,
        Integer sourceId,
        String sourceName,
        Integer aspectId,
        String aspectName,
        /* AEGIS link: the forensic element this registry row was derived from. */
        String elementId) {
}
