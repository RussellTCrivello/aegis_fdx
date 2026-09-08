package com.aegis.fdx.facade.dto;

/**
 * A renderable preview of an element.
 */
public record PreviewDto(
        PreviewType previewType,
        String fileName,
        String filePath,
        String mediaType,
        long fileSize,
        /* Text/document preview payload; null for binary preview types. */
        String content,
        /* Populated for image/pdf previews when a rendered byte payload exists. */
        byte[] data,
        int width,
        int height,
        boolean truncated,
        String error) {

    /** What kind of preview this is. */
    public enum PreviewType {
        IMAGE("image"),
        PDF("pdf"),
        DOCUMENT("document"),
        TEXT("text"),
        UNSUPPORTED("unsupported");

        private final String pythonValue;

        PreviewType(String pythonValue) {
            this.pythonValue = pythonValue;
        }

        /** Lowercase wire form. */
        public String pythonValue() {
            return pythonValue;
        }
    }
}
