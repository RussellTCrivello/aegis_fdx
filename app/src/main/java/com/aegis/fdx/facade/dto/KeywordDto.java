package com.aegis.fdx.facade.dto;

/**
 * A keyword: a phrase of interest belonging to a category.
 */
public record KeywordDto(int id, String keyword, int categoryId, String categoryWord) {
}
