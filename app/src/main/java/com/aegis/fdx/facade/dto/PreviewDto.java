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

        private final String label;

        PreviewType(String label) {
            this.label = label;
        }

        /**
         * The lowercase name of this preview kind, for logs, exports and interface copy.
         *
         * <p>The wording matches the behaviour the reference implementation shows an
         * examiner; the value is produced and consumed entirely within this application.
         */
        public String label() {
            return label;
        }
    }
}
