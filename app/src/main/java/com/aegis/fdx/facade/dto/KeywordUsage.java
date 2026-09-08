package com.aegis.fdx.facade.dto;

import java.util.List;

/**
 * A keyword together with where it was found.
 *
 * @param hits  total occurrences across all matching files
 * @param files number of distinct files containing it
 */
public record KeywordUsage(int keywordId, String phrase, String categoryWord,
                           int hits, int files, List<FileHit> fileHits) {

    /** One file containing the keyword. */
    public record FileHit(int pathId, String fileName, String sourceName, int hits) {
    }

    public KeywordUsage {
        fileHits = fileHits == null ? List.of() : List.copyOf(fileHits);
    }

    public KeywordUsage withFileHits(List<FileHit> hits) {
        return new KeywordUsage(keywordId, phrase, categoryWord, this.hits, files, hits);
    }
}
