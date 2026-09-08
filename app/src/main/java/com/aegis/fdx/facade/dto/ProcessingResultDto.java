package com.aegis.fdx.facade.dto;

/**
 * What happened to one file during processing.
 *
 * <p><em>Every</em> submitted path yields one of these, so {@code success == false}
 * rows with a populated {@code error} are a normal part of the contract, not an
 * exceptional case.
 */
public record ProcessingResultDto(
        String filePath,
        String fileName,
        String extension,
        long fileSize,
        boolean success,
        String error,
        boolean stored,
        boolean duplicate,
        String hash,
        int contentLength) {
}
