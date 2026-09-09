package com.aegis.fdx;

import com.aegis.fdx.facade.FileProcessingFacade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A result message must name the failures, not just count them, while staying
 * short enough for the results table.
 */
class FacadeErrorSummaryTest {

    @Test
    @DisplayName("No samples leaves the bare count")
    void bareCountWithoutSamples() {
        assertEquals("processing reported 2 error(s)",
                FileProcessingFacade.errorSummary(2, List.of()));
        assertEquals("processing reported 2 error(s)",
                FileProcessingFacade.errorSummary(2, null));
        assertEquals("processing reported 1 error(s)",
                FileProcessingFacade.errorSummary(1, Arrays.asList("  ", null)));
    }

    @Test
    @DisplayName("The first failures ride along with the count")
    void namesFirstFailures() {
        String message = FileProcessingFacade.errorSummary(2, List.of(
                "a.msg -- Index failed: disk full",
                "b.msg -- Text store failed: disk full"));
        assertEquals("processing reported 2 error(s): "
                + "a.msg -- Index failed: disk full; b.msg -- Text store failed: disk full",
                message);
    }

    @Test
    @DisplayName("Samples are deduplicated, capped at two, and truncated")
    void capsAndTruncates() {
        assertEquals("processing reported 3 error(s): one; two",
                FileProcessingFacade.errorSummary(3, List.of("one", "two", "three")));
        assertEquals("processing reported 2 error(s): same",
                FileProcessingFacade.errorSummary(2, List.of("same", "same")));

        String message = FileProcessingFacade.errorSummary(1, List.of("x".repeat(200)));
        assertTrue(message.startsWith("processing reported 1 error(s): " + "x".repeat(160)),
                message);
        assertTrue(message.endsWith("..."), message);
    }
}
