package com.aegis.fdx.facade.dto;

/**
 * One row of a search that looked everywhere.
 *
 * <p>Ordinary search answers "which files match". This answers "which files match, and
 * <em>where</em>" — in the name, in the path, in the metadata, in the extracted content,
 * or through a keyword, a category or a category word the file is related to. Knowing
 * which of those it was is the difference between a result an examiner can act on and a
 * list they have to re-check by hand.
 */
public record MatchRow(
        int pathId,
        String elementId,
        String fileName,
        String filePath,
        String fileType,
        long fileSize,
        String sourceName,
        String aspectName,
        /* Where the match happened. */
        MatchType matchType,
        /* The exact term that matched: the keyword phrase, the category, the word typed. */
        String matchedTerm,
        /* The keyword this row was reached through, if any. */
        String keyword,
        Integer keywordId,
        /* The category this row was reached through, if any. */
        String category,
        Integer categoryId,
        /* The category word this row was reached through, if any. */
        String categoryWord,
        Integer categoryWordId,
        /* A short piece of evidence: the matching line, or the field that matched. */
        String snippet,
        /* Occurrences, where the relation or the index can count them. */
        int hits) {

    /** Where a match was found. */
    public enum MatchType {
        FILE_NAME("file name"),
        PATH("path"),
        METADATA("metadata"),
        CONTENT("content"),
        KEYWORD("keyword"),
        CATEGORY("category"),
        CATEGORY_WORD("category word");

        private final String label;

        MatchType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
