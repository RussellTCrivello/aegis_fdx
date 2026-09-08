package com.aegis.fdx.facade.dto;

import java.util.List;

/**
 * One term in the relationship graph — a keyword, a category or a category word —
 * together with everything reachable from it.
 *
 * <p>The counts are the point. A row that shows a term without saying how many files it
 * reaches is decoration; every count here is {@code COUNT(DISTINCT path_id)} over the
 * whole case, not over the page being displayed, the current search or the screen that
 * happens to be open.
 */
public record TermSummary(
        Kind kind,
        int id,
        /* The term as it is displayed: the spelling the operator used. */
        String text,
        /* Lower-case, accent-folded, whitespace-collapsed form used for comparison. */
        String normalized,
        int wordCount,
        /* Files this term reaches, across the whole case. */
        int fileCount,
        /* Total occurrences where the relation records them; 0 where it does not. */
        int hits,
        List<RelatedTerm> categories,
        List<RelatedTerm> keywords,
        List<RelatedTerm> categoryWords,
        List<FileRef> files) {

    public TermSummary {
        categories = categories == null ? List.of() : List.copyOf(categories);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        categoryWords = categoryWords == null ? List.of() : List.copyOf(categoryWords);
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** What sort of node this is. */
    public enum Kind {
        KEYWORD("Keyword"),
        CATEGORY("Category"),
        CATEGORY_WORD("Category word");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A neighbouring term, with its own file count so a list never lies about reach. */
    public record RelatedTerm(Kind kind, int id, String text, int fileCount) {
    }

    /** A file reached from a term. */
    public record FileRef(
            int pathId,
            String elementId,
            String fileName,
            String filePath,
            long fileSize,
            String fileType,
            String status,
            String sourceName,
            String aspectName,
            /* Occurrences of the term in this file, where the relation counts them. */
            int hits) {
    }
}
