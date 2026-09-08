package com.aegis.fdx.facade.dto;

import java.util.List;

/**
 * One search result.
 */
public record SearchResultDto(
        /* The element id, e.g. E-000001-E1. */
        String id,
        String fileName,
        String filePath,
        String fileType,
        long fileSize,
        String sourceName,
        String aspectName,
        /* Relevance score. */
        double rank,
        /* Number of matches within the document. */
        int matchCount,
        /* Highlighted excerpts around the matches. */
        List<String> snippets,
        String status,
        String custodian,
        String md5,
        String sha256) {

    public SearchResultDto {
        snippets = snippets == null ? List.of() : List.copyOf(snippets);
    }
}
