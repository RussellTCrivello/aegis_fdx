package com.aegis.fdx;

import com.aegis.fdx.analyzers.AttachmentNames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared attachment-naming rungs behave, including at their edges. */
class AttachmentNamesTest {

    @Test
    @DisplayName("Real names pass through, hostile characters do not")
    void sanitize() {
        assertEquals("report Q3 (final).pdf",
                AttachmentNames.sanitize("report Q3 (final).pdf"));
        assertEquals("a_b_c_d_e_f_g_h_i_j_k",
                AttachmentNames.sanitize("a/b\\c:d*e?f\"g<h>i|j\tk"));
        assertEquals("", AttachmentNames.sanitize(null));
        assertEquals("", AttachmentNames.sanitize("   "));
    }

    @Test
    @DisplayName("Truncation preserves a trailing extension")
    void truncationKeepsExtension() {
        String longPdf = "n".repeat(100) + ".pdf";
        String got = AttachmentNames.sanitize(longPdf);
        assertEquals(80, got.length());
        assertTrue(got.endsWith(".pdf"), got);
        assertEquals(80, AttachmentNames.sanitize("n".repeat(100)).length());
        assertEquals("short.pdf", AttachmentNames.sanitize("short.pdf"));
    }

    @Test
    @DisplayName("Directory parts are dropped in either separator style")
    void basename() {
        assertEquals("file.pdf", AttachmentNames.basename("C:\\Users\\x\\file.pdf"));
        assertEquals("file.pdf", AttachmentNames.basename("/tmp/x/file.pdf"));
        assertEquals("file.pdf", AttachmentNames.basename("file.pdf"));
        assertEquals("", AttachmentNames.basename(null));
    }

    @Test
    @DisplayName("Content-IDs shaped like filenames are recovered, the rest rejected")
    void fromContentId() {
        assertEquals("image001.png",
                AttachmentNames.fromContentId("<image001.png@01DA2B3C>"));
        assertEquals("photo.JPG", AttachmentNames.fromContentId("photo.JPG@mail"));
        assertEquals("plain.png", AttachmentNames.fromContentId("plain.png"));
        assertEquals("", AttachmentNames.fromContentId("<abc123@x>"));
        assertEquals("", AttachmentNames.fromContentId("<my file.png@x>"));
        assertEquals("", AttachmentNames.fromContentId("trailingdot.@x"));
        assertEquals("", AttachmentNames.fromContentId(null));
    }

    @Test
    @DisplayName("MIME types map to extensions, parameters and case ignored")
    void extensionFor() {
        assertEquals(".png", AttachmentNames.extensionFor("image/png"));
        assertEquals(".html", AttachmentNames.extensionFor("Text/HTML; charset=utf-8"));
        assertEquals(".xlsx", AttachmentNames.extensionFor(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertEquals(".gz", AttachmentNames.extensionFor("application/gzip"));
        assertNull(AttachmentNames.extensionFor("application/octet-stream"));
        assertNull(AttachmentNames.extensionFor("bogus"));
        assertNull(AttachmentNames.extensionFor(null));
    }

    @Test
    @DisplayName("Sane unknown subtypes work as extensions, junk does not")
    void extensionSubtypeFallback() {
        assertEquals(".stl", AttachmentNames.extensionFor("model/stl"));
        assertNull(AttachmentNames.extensionFor("application/x-foo"));
        assertNull(AttachmentNames.extensionFor("application/vnd.ms-outlook-bar"));
    }

    @Test
    @DisplayName("Extensions are appended only when missing and well-formed")
    void ensureExtension() {
        assertEquals("a.pdf", AttachmentNames.ensureExtension("a", ".pdf"));
        assertEquals("a.pdf", AttachmentNames.ensureExtension("a.pdf", ".txt"));
        assertEquals("a", AttachmentNames.ensureExtension("a", "bogus"));
        assertEquals("a.pdf", AttachmentNames.ensureExtension("a.", ".pdf"));
        assertEquals("archive.tar", AttachmentNames.ensureExtension("archive.tar.", ".pdf"));
        assertEquals("", AttachmentNames.ensureExtension(null, ".pdf"));
    }
}
