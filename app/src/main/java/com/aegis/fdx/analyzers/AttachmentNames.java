package com.aegis.fdx.analyzers;

import java.util.Locale;
import java.util.Map;

/**
 * Real names for attachments, shared by the mail analyzers.
 *
 * <p>Attachment filename properties are absent more often than not: attached
 * messages, inline images and provider-generated parts carry none, so every mail
 * analyzer used to fall back to {@code attachment_N}. Each analyzer now walks its
 * own ladder - filename properties, pathnames, embedded subjects, Content-IDs,
 * MIME types - and these are the rungs they share: recovering a filename from a
 * Content-ID, mapping a MIME type to an extension, and making any of it safe to
 * store and export.
 *
 * <p>All functions are pure and null-tolerant; unusable input yields {@code ""}
 * (or {@code null} where the caller needs tri-state), never an exception.
 */
public final class AttachmentNames {

    /** Filenames are capped so exports stay within path limits. */
    private static final int MAX_NAME = 80;

    private static final Map<String, String> MIME_EXTENSIONS = Map.ofEntries(
            Map.entry("application/pdf", ".pdf"),
            Map.entry("message/rfc822", ".eml"),
            Map.entry("application/vnd.ms-outlook", ".msg"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.ms-excel", ".xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("application/vnd.ms-powerpoint", ".ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
            Map.entry("application/rtf", ".rtf"),
            Map.entry("text/rtf", ".rtf"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/html", ".html"),
            Map.entry("text/csv", ".csv"),
            Map.entry("text/xml", ".xml"),
            Map.entry("application/xml", ".xml"),
            Map.entry("application/json", ".json"),
            Map.entry("image/png", ".png"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/bmp", ".bmp"),
            Map.entry("image/x-ms-bmp", ".bmp"),
            Map.entry("image/tiff", ".tif"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/svg+xml", ".svg"),
            Map.entry("application/zip", ".zip"),
            Map.entry("application/gzip", ".gz"),
            Map.entry("application/x-gzip", ".gz"),
            Map.entry("application/x-bzip2", ".bz2"),
            Map.entry("application/x-xz", ".xz"),
            Map.entry("application/x-tar", ".tar"),
            Map.entry("application/x-rar-compressed", ".rar"),
            Map.entry("application/x-7z-compressed", ".7z"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("audio/wav", ".wav"),
            Map.entry("audio/x-wav", ".wav"),
            Map.entry("audio/ogg", ".ogg"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("video/x-msvideo", ".avi"),
            Map.entry("video/quicktime", ".mov"));

    private AttachmentNames() { }

    /**
     * Makes a filename safe to store and export: path separators, wildcards and
     * control characters become underscores, and over-long names are truncated
     * with a trailing extension preserved, so a long {@code ...name.pdf} keeps
     * routing to the PDF analyzer.
     */
    public static String sanitize(String name) {
        if (name == null) return "";
        String t = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (t.length() <= MAX_NAME) return t;
        String ext = "";
        int dot = t.lastIndexOf('.');
        if (dot > 0 && dot < t.length() - 1 && dot >= t.length() - 7
                && t.substring(dot + 1).chars().allMatch(Character::isLetterOrDigit)) {
            ext = t.substring(dot);
        }
        String base = (ext.isEmpty() ? t : t.substring(0, dot))
                .substring(0, MAX_NAME - ext.length()).strip();
        return base + ext;
    }

    /** Drops any directory part, tolerating both separator styles. */
    public static String basename(String path) {
        if (path == null) return "";
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (cut < 0 ? path : path.substring(cut + 1)).strip();
    }

    /**
     * Recovers a filename from a Content-ID. Inline images usually carry IDs like
     * {@code <image001.png@01DA2B3C>}, whose local part is the original filename;
     * anything without a plausible {@code name.ext} shape is rejected.
     */
    public static String fromContentId(String contentId) {
        if (contentId == null) return "";
        String c = contentId.strip();
        if (c.startsWith("<") && c.endsWith(">") && c.length() > 2) {
            c = c.substring(1, c.length() - 1);
        }
        int at = c.indexOf('@');
        String local = (at < 0 ? c : c.substring(0, at)).strip();
        if (local.isEmpty()) return "";
        if (local.chars().anyMatch(ch -> ch <= ' ' || ch == '/' || ch == '\\')) return "";
        int dot = local.lastIndexOf('.');
        if (dot <= 0 || dot == local.length() - 1) return "";
        String tail = local.substring(dot + 1);
        if (tail.length() > 6 || !tail.chars().allMatch(Character::isLetterOrDigit)) return "";
        return local;
    }

    /**
     * Maps a MIME type to a filename extension, e.g. {@code image/png} to
     * {@code .png}. Parameters ({@code ; charset=...}) and case are ignored; a
     * short alphanumeric subtype is used as a last resort ({@code model/stl} to
     * {@code .stl}); anything unusable yields {@code null}.
     */
    public static String extensionFor(String mimeTag) {
        if (mimeTag == null) return null;
        String m = mimeTag.strip().toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi).strip();
        if (m.isEmpty() || !m.contains("/")) return null;
        String hit = MIME_EXTENSIONS.get(m);
        if (hit != null) return hit;
        String sub = m.substring(m.indexOf('/') + 1);
        if (sub.matches("[a-z0-9]{2,5}")) return "." + sub;
        return null;
    }

    /**
     * Appends {@code ext} unless the name already ends in an extension.
     * Malformed extensions are ignored rather than appended.
     */
    public static String ensureExtension(String name, String ext) {
        if (name == null) return "";
        if (ext == null || !ext.matches("\\.[A-Za-z0-9]{1,6}")) return name;
        String n = name.strip().replaceAll("\\.+$", "");
        int dot = n.lastIndexOf('.');
        if (dot > 0 && dot < n.length() - 1 && dot >= n.length() - 7) return n;
        return n + ext;
    }
}
