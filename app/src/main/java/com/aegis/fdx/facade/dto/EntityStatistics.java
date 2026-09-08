package com.aegis.fdx.facade.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * Rollup for one source or aspect: how much material is attributed to it and how far
 * review has progressed.
 */
public record EntityStatistics(
        int files,
        long bytes,
        int distinctTypes,
        int readFiles,
        LocalDate earliest,
        LocalDate latest,
        Map<String, Integer> typeBreakdown) {

    public EntityStatistics {
        typeBreakdown = typeBreakdown == null ? Map.of() : Map.copyOf(typeBreakdown);
    }

    /** Review completion, 0.0 to 1.0. */
    public double reviewProgress() {
        return files == 0 ? 0d : (double) readFiles / files;
    }

    public int unreadFiles() {
        return Math.max(0, files - readFiles);
    }

    public boolean isEmpty() {
        return files == 0;
    }
}
