package com.aegis.fdx.facade;

import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.dto.PreviewDto;
import com.aegis.fdx.model.Item;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Produces a preview of an element for display.
 *
 * <p>Elements are addressed by their string id ({@code E-000001-E1}); an int overload
 * is accepted for convenience. Text and document previews return the stored extracted
 * text, image previews return the native bytes plus the stored extracted text,
 * PDF previews return the native bytes, and anything else reports
 * {@link PreviewDto.PreviewType#UNSUPPORTED}.
 */
public final class PreviewFacade {

    /** Default preview width in pixels. */
    public static final int DEFAULT_MAX_WIDTH = 1200;
    /** Default preview height in pixels. */
    public static final int DEFAULT_MAX_HEIGHT = 800;

    private static final Set<String> IMAGE_EXT =
            Set.of("jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp");
    private static final Set<String> TEXT_EXT =
            Set.of("txt", "log", "csv", "tsv", "json", "xml", "html", "htm", "md", "eml");
    private static final Set<String> DOC_EXT =
            Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "msg");

    /** Text previews are capped so a huge extraction cannot be returned whole. */
    private static final int MAX_TEXT_CHARS = 200_000;

    private final LiveCase liveCase;

    public PreviewFacade(LiveCase liveCase) {
        this.liveCase = liveCase;
    }

    /** Preview at the default size. */
    public PreviewDto getPreview(int fileId) {
        return getPreview(fileId, DEFAULT_MAX_WIDTH, DEFAULT_MAX_HEIGHT);
    }

    /** Preview constrained to the given box. */
    public PreviewDto getPreview(int fileId, int maxWidth, int maxHeight) {
        Validate.positiveId(fileId, "elementId");
        return getPreview(String.format("E-%06d", fileId), maxWidth, maxHeight);
    }

    /** Preview of an element addressed by id. */
    public PreviewDto getPreview(String fileId) {
        return getPreview(fileId, DEFAULT_MAX_WIDTH, DEFAULT_MAX_HEIGHT);
    }

    public PreviewDto getPreview(String fileId, int maxWidth, int maxHeight) {
        String id = Validate.required(fileId, "elementId");
        if (maxWidth <= 0 || maxHeight <= 0) {
            throw FacadeException.validation("max_width and max_height must be positive");
        }

        Item it;
        try {
            it = liveCase.byId(id);
        } catch (Exception e) {
            throw FacadeException.internal("failed to load element", e);
        }
        if (it == null) {
            throw FacadeException.notFound("file", id);
        }

        String ext = it.extension() == null ? "" : it.extension().toLowerCase();
        PreviewDto.PreviewType type = classify(ext);

        // Text-like content comes from the case's stored extracted text.
        if (type == PreviewDto.PreviewType.TEXT || type == PreviewDto.PreviewType.DOCUMENT) {
            String text = readStoredText(it.id());
            boolean truncated = false;
            if (text != null && text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS);
                truncated = true;
            }
            return new PreviewDto(type, it.name(), it.sourcePath(), it.mediaType(),
                    it.size(), text, null, 0, 0, truncated,
                    text == null ? "no extracted text available" : null);
        }

        if (type == PreviewDto.PreviewType.IMAGE || type == PreviewDto.PreviewType.PDF) {
            byte[] data = readNative(it);
            // Images also carry their stored text (dimensions/EXIF/OCR/filename
            // fallback) so callers can show the picture and its words together.
            String text = null;
            boolean truncated = false;
            if (type == PreviewDto.PreviewType.IMAGE) {
                text = readStoredText(it.id());
                if (text != null && text.length() > MAX_TEXT_CHARS) {
                    text = text.substring(0, MAX_TEXT_CHARS);
                    truncated = true;
                }
            }
            return new PreviewDto(type, it.name(), it.sourcePath(), it.mediaType(),
                    it.size(), text, data, maxWidth, maxHeight, truncated,
                    data == null ? "native file not available" : null);
        }

        return new PreviewDto(PreviewDto.PreviewType.UNSUPPORTED, it.name(), it.sourcePath(),
                it.mediaType(), it.size(), null, null, 0, 0, false,
                "preview not supported for ." + ext);
    }

    /** Whether an extension denotes a displayable image (case-insensitive). */
    public static boolean isImageExtension(String ext) {
        return ext != null && IMAGE_EXT.contains(ext.toLowerCase(Locale.ROOT));
    }

    private static PreviewDto.PreviewType classify(String ext) {
        if ("pdf".equals(ext)) {
            return PreviewDto.PreviewType.PDF;
        }
        if (isImageExtension(ext)) {
            return PreviewDto.PreviewType.IMAGE;
        }
        if (TEXT_EXT.contains(ext)) {
            return PreviewDto.PreviewType.TEXT;
        }
        if (DOC_EXT.contains(ext)) {
            return PreviewDto.PreviewType.DOCUMENT;
        }
        return PreviewDto.PreviewType.UNSUPPORTED;
    }

    private String readStoredText(String itemId) {
        try {
            String s = liveCase.folder().readText(itemId);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readNative(Item it) {
        try {
            if (it.sourcePath() == null) {
                return null;
            }
            Path p = Path.of(it.sourcePath());
            if (!Files.isReadable(p)) {
                return null;
            }
            return Files.readAllBytes(p);
        } catch (Exception e) {
            return null;
        }
    }
}
