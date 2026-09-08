package com.aegis.fdx.facade;

/**
 * Boundary checks shared by the facade layer.
 *
 * <p>Intentionally the <em>only</em> validation the facades perform: non-empty names,
 * ranges, and sane paging. Deeper rules stay in the engine where they already live.
 */
public final class Validate {

    private Validate() {
    }

    /** @throws FacadeException VALIDATION if null or blank */
    public static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw FacadeException.validation(field + " is required");
        }
        return value.trim();
    }

    /** Optional string; null or blank collapses to empty. */
    public static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    /** Importance is a 0.0-1.0 weighting. */
    public static double importance(double value) {
        if (Double.isNaN(value) || value < 0.0d || value > 1.0d) {
            throw FacadeException.validation("importance must be between 0.0 and 1.0");
        }
        return value;
    }

    /** Page size, clamped to a sane maximum. */
    public static int limit(int value) {
        if (value <= 0) {
            throw FacadeException.validation("limit must be greater than 0");
        }
        return Math.min(value, 10_000);
    }

    public static int offset(int value) {
        if (value < 0) {
            throw FacadeException.validation("offset must not be negative");
        }
        return value;
    }

    public static int positiveId(Integer value, String field) {
        if (value == null) {
            throw FacadeException.validation(field + " is required");
        }
        if (value <= 0) {
            throw FacadeException.validation(field + " must be a positive integer");
        }
        return value;
    }
}
