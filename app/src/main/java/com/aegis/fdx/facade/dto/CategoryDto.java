package com.aegis.fdx.facade.dto;

/**
 * A category: a named grouping of vocabulary, identified by the word that names it.
 * Carries both the surrogate id and the resolved word so callers need no second lookup.
 */
public record CategoryDto(int id, int wordId, String word) {
}
