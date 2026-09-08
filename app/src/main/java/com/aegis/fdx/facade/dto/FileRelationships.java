package com.aegis.fdx.facade.dto;

import java.util.List;

/**
 * Everything a file is related to: its terms, and the files that share them.
 *
 * <p>The reverse of {@link TermSummary}, and deliberately built from the same joins, so
 * "the categories of this file" and "the files of this category" cannot drift apart.
 */
public record FileRelationships(
        int pathId,
        String elementId,
        String fileName,
        String filePath,
        List<TermSummary.RelatedTerm> keywords,
        List<TermSummary.RelatedTerm> categories,
        List<TermSummary.RelatedTerm> categoryWords,
        List<TermSummary.FileRef> relatedFiles) {

    public FileRelationships {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        categories = categories == null ? List.of() : List.copyOf(categories);
        categoryWords = categoryWords == null ? List.of() : List.copyOf(categoryWords);
        relatedFiles = relatedFiles == null ? List.of() : List.copyOf(relatedFiles);
    }

    public boolean isEmpty() {
        return keywords.isEmpty() && categories.isEmpty() && categoryWords.isEmpty();
    }
}
