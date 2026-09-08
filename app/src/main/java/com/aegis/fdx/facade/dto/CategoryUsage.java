package com.aegis.fdx.facade.dto;

/** A category together with how many files it covers in the current scope. */
public record CategoryUsage(int categoryId, String word, int fileCount) {
}
