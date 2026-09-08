package com.aegis.fdx;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * D-04: generates the ~40-file validation corpus covering every required format
 * plus the adversarial cases — corrupted, encrypted, nested and multilingual.
 *
 * <p>Everything is synthesised here, so the dataset is reproducible and contains
 * no third-party or client material.
 */
public final class TestDataset {

    /** Canary strings the expected-results file asserts on. */
    public static final String CANARY_PDF = "SETTLEMENT-CANARY-PDF";
    public static final String CANARY_DOCX = "SETTLEMENT-CANARY-DOCX";
    public static final String CANARY_XLSX = "LEDGER-CANARY-XLSX";
    public static final String CANARY_PPTX = "BRIEFING-CANARY-PPTX";
    public static final String CANARY_NESTED = "DEEPLY-NESTED-CANARY";
    public static final String CANARY_EML = "WIRE-CANARY-EML";
    public static final String CANARY_ARABIC = "\u0627\u0644\u062A\u062D\u0642\u064A\u0642 \u0627\u0644\u0631\u0642\u0645\u064A";
    public static final String CANARY_CJK = "\u8abf\u67fb\u5831\u544a\u66f8";
    public static final String CANARY_CYRILLIC = "\u0440\u0430\u0441\u0441\u043b\u0435\u0434\u043e\u0432\u0430\u043d\u0438\u0435";
    public static final String INVOICE_NO = "INV-88213";
    /** M3-A. Only recoverable by OCR — exists in no text layer anywhere. */
    public static final String CANARY_OCR_IMAGE = "OCR-CANARY-IMAGE";
    public static final String CANARY_OCR_PDF = "OCR-CANARY-SCANNED";

    public static List<String> build(Path dir) throws Exception {
        Files.createDirectories(dir);
        List<String> manifest = new ArrayList<>();

        // ---------- documents ----------
        write(dir, "settlement_agreement.pdf", pdf(
                CANARY_PDF + "\nSettlement agreement between the parties.\n"
                + "Payment of 240,000 EUR is due on 2024-03-15.\n"
                + "Invoice reference " + INVOICE_NO + "."), manifest);

        write(dir, "contract_draft.docx", docx(
                CANARY_DOCX + " Draft contract revision 4. The supplier shall deliver "
                + "the hardware consignment no later than the agreed date."), manifest);

        write(dir, "ledger_q1.xlsx", xlsx(
                new String[][]{
                        {"date", "vendor", "amount", "account"},
                        {"2024-03-01", "Nordwind Logistik", "4150.00", "DE89370400440532013000"},
                        {CANARY_XLSX, "Total", "4150.00", ""}}), manifest);

        write(dir, "board_briefing.pptx", pptx(
                CANARY_PPTX + " — acquisition timeline and valuation model"), manifest);

        write(dir, "notes.txt",
                (CANARY_ARABIC + "\nAuthentication failure for user svc_backup from 10.4.19.22.\n")
                        .getBytes(StandardCharsets.UTF_8), manifest);

        write(dir, "payments.csv",
                "date,vendor,amount\n2024-03-01,Nordwind Logistik,4150.00\n"
                        .getBytes(StandardCharsets.UTF_8), manifest);

        write(dir, "report.html",
                ("<html><body><h1>" + CANARY_CJK + "</h1><p>Quarterly review of the "
                 + "settlement position.</p><script>ignored()</script></body></html>")
                        .getBytes(StandardCharsets.UTF_8), manifest);

        write(dir, "manifest.xml",
                "<?xml version=\"1.0\"?><case><id>2024-017</id><custodian>A. Farouk</custodian></case>"
                        .getBytes(StandardCharsets.UTF_8), manifest);

        write(dir, "config.json",
                "{\"case\":\"2024-017\",\"ocr\":true,\"depth\":20}".getBytes(StandardCharsets.UTF_8),
                manifest);

        write(dir, "memo.rtf",
                ("{\\rtf1\\ansi " + CANARY_CYRILLIC + " memorandum of understanding.}")
                        .getBytes(StandardCharsets.UTF_8), manifest);

        // ---------- email ----------
        byte[] eml = eml(CANARY_EML);
        write(dir, "wire_request.eml", eml, manifest);
        write(dir, "thread_reply.eml", emlSimple("RE: Settlement agreement",
                "s.okafor@northwind-legal.example", "a.farouk@northwind-legal.example",
                "Confirming receipt of the signed agreement."), manifest);
        write(dir, "mailbox.mbox", mbox(), manifest);

        // ---------- images ----------
        write(dir, "receipt_scan.png", png(64, 48), manifest);

        // v1 licence decision: RAR is retained + hashed but not expanded.
        write(dir, "archive.rar", rarSignature(), manifest);

        // ---- M3-A OCR fixtures: text exists ONLY as pixels ------------------
        write(dir, "scanned_memo.png", scannedImage(new String[]{
                CANARY_OCR_IMAGE,
                "Invoice reference " + INVOICE_NO,
                "Wire transfer of 240,000 EUR"}), manifest);
        write(dir, "scanned_contract.pdf", scannedPdf(new String[]{
                CANARY_OCR_PDF,
                "Settlement agreement page one",
                "Reference " + INVOICE_NO}), manifest);
        write(dir, "photo.jpg", jpegWithExif(), manifest);
        write(dir, "logo.gif", gif(), manifest);
        write(dir, "diagram.bmp", bmp(), manifest);

        // ---------- media (metadata only) ----------
        write(dir, "interview.mp3", id3Mp3(), manifest);
        write(dir, "walkthrough.mp4", mp4Stub(), manifest);
        write(dir, "recording.wav", wav(), manifest);

        // ---------- archives, incl. nesting (F-03 / AT-02) ----------
        byte[] innerPdf = pdf(CANARY_NESTED + " level four payload with " + INVOICE_NO);
        byte[] innerEml = emlWithAttachment("Nested delivery", innerPdf, "level4.pdf");
        byte[] innerZip = zip(new String[]{"level3.eml"}, new byte[][]{innerEml});
        byte[] outerZip = zip(new String[]{"level2.zip", "readme.txt"},
                new byte[][]{innerZip, "outer container".getBytes(StandardCharsets.UTF_8)});
        write(dir, "nested_evidence.zip", outerZip, manifest);

        write(dir, "documents.zip", zip(
                new String[]{"a.txt", "b.txt", "sub/c.txt"},
                new byte[][]{"alpha".getBytes(), "beta".getBytes(), "gamma".getBytes()}), manifest);

        write(dir, "backup.tar", tar(), manifest);
        write(dir, "backup.tar.gz", gzip(tar()), manifest);
        write(dir, "single.txt.gz", gzip("compressed single stream".getBytes(StandardCharsets.UTF_8)),
                manifest);

        // ---------- adversarial ----------
        write(dir, "encrypted.zip", encryptedZip(), manifest);          // AT-03
        write(dir, "corrupt.pdf", corruptPdf(), manifest);              // AT-01
        write(dir, "corrupt.docx", corruptOoxml(), manifest);           // AT-01
        write(dir, "truncated.zip", truncatedZip(), manifest);          // AT-01
        write(dir, "empty.txt", new byte[0], manifest);
        write(dir, "unknown.xyz", new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, manifest);
        write(dir, "no_extension", "file without an extension".getBytes(StandardCharsets.UTF_8),
                manifest);

        // ---------- duplicates (AT-04) ----------
        byte[] dupe = "identical duplicate payload for dedupe testing".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("custodian_a"));
        Files.createDirectories(dir.resolve("custodian_b"));
        for (int i = 1; i <= 3; i++) {
            Files.write(dir.resolve("custodian_a").resolve("dupe_" + i + ".txt"), dupe);
            Files.write(dir.resolve("custodian_b").resolve("dupe_" + i + ".txt"), dupe);
            manifest.add("custodian_a/dupe_" + i + ".txt");
            manifest.add("custodian_b/dupe_" + i + ".txt");
        }

        // ---------- multilingual ----------
        write(dir, "arabic.txt", (CANARY_ARABIC + " \u0645\u0644\u0641 \u0627\u062e\u062a\u0628\u0627\u0631")
                .getBytes(StandardCharsets.UTF_8), manifest);
        write(dir, "japanese.txt", (CANARY_CJK + " \u30c6\u30b9\u30c8\u30d5\u30a1\u30a4\u30eb")
                .getBytes(StandardCharsets.UTF_8), manifest);
        write(dir, "russian.txt", (CANARY_CYRILLIC + " \u0442\u0435\u0441\u0442\u043e\u0432\u044b\u0439 \u0444\u0430\u0439\u043b")
                .getBytes(StandardCharsets.UTF_8), manifest);

        return manifest;
    }

    // ---- builders ------------------------------------------------------------

    private static void write(Path dir, String name, byte[] data, List<String> manifest)
            throws Exception {
        Files.write(dir.resolve(name), data);
        manifest.add(name);
    }

    public static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 700);
                for (String line : text.split("\n")) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -16);
                }
                cs.endText();
            }
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    static byte[] docx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun r = p.createRun();
            r.setText(text);
            doc.getProperties().getCoreProperties().setCreator("AEGIS Test Harness");
            doc.getProperties().getCoreProperties().setTitle("Contract draft");
            doc.write(bos);
            return bos.toByteArray();
        }
    }

    static byte[] xlsx(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Ledger");
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    static byte[] pptx(String text) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new java.awt.Rectangle(50, 50, 500, 100));
            box.setText(text);
            ppt.write(bos);
            return bos.toByteArray();
        }
    }

    static byte[] eml(String canary) {
        return ("""
                Message-ID: <wire-001@northwind-legal.example>
                Date: Mon, 13 Jan 2024 22:00:00 +0000
                From: J. Kowalski <j.kowalski@northwind-legal.example>
                To: M. Weber <m.weber@northwind-legal.example>
                Cc: L. Nguyen <l.nguyen@northwind-legal.example>
                Subject: Wire instructions
                MIME-Version: 1.0
                Content-Type: text/plain; charset=UTF-8

                %s
                Following up on the wire transfer request. The beneficiary account
                details changed - please confirm by phone before releasing funds.
                """.formatted(canary)).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] emlSimple(String subject, String from, String to, String body) {
        return ("Message-ID: <" + Math.abs(subject.hashCode()) + "@test.example>\n"
                + "Date: Tue, 14 Jan 2024 09:30:00 +0000\n"
                + "From: " + from + "\nTo: " + to + "\nSubject: " + subject + "\n"
                + "Content-Type: text/plain; charset=UTF-8\n\n" + body + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Multipart message carrying a real PDF — exercises F-10 attachment split. */
    static byte[] emlWithAttachment(String subject, byte[] attachment, String fileName) {
        String b64 = java.util.Base64.getMimeEncoder().encodeToString(attachment);
        return ("Message-ID: <nested@test.example>\n"
                + "Date: Wed, 15 Jan 2024 11:00:00 +0000\n"
                + "From: a.farouk@northwind-legal.example\n"
                + "To: s.okafor@northwind-legal.example\n"
                + "Subject: " + subject + "\n"
                + "MIME-Version: 1.0\n"
                + "Content-Type: multipart/mixed; boundary=\"BOUND42\"\n\n"
                + "--BOUND42\nContent-Type: text/plain; charset=UTF-8\n\n"
                + "Please see the attached level four document.\n\n"
                + "--BOUND42\nContent-Type: application/pdf\n"
                + "Content-Transfer-Encoding: base64\n"
                + "Content-Disposition: attachment; filename=\"" + fileName + "\"\n\n"
                + b64 + "\n\n--BOUND42--\n").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] mbox() {
        return ("From sender@example.test Mon Jan 15 10:00:00 2024\n"
                + "From: sender@example.test\nTo: rec@example.test\nSubject: First mbox message\n\n"
                + "Body of the first message.\n\n"
                + "From sender@example.test Mon Jan 15 11:00:00 2024\n"
                + "From: sender@example.test\nTo: rec@example.test\nSubject: Second mbox message\n\n"
                + "Body of the second message.\n").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] zip(String[] names, byte[][] payloads) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            for (int i = 0; i < names.length; i++) {
                ZipArchiveEntry e = new ZipArchiveEntry(names[i]);
                e.setSize(payloads[i].length);
                zos.putArchiveEntry(e);
                zos.write(payloads[i]);
                zos.closeArchiveEntry();
            }
        }
        return bos.toByteArray();
    }

    static byte[] tar() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            byte[] data = "tar member payload".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry e = new TarArchiveEntry("member.txt");
            e.setSize(data.length);
            tos.putArchiveEntry(e);
            tos.write(data);
            tos.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream gz = new GzipCompressorOutputStream(bos)) {
            gz.write(input);
        }
        return bos.toByteArray();
    }

    /**
     * ZIP with the encryption bit set on the entry. commons-compress reports
     * {@code canReadEntryData == false}, which is exactly the LOCKED path (AT-03).
     */
    static byte[] encryptedZip() throws Exception {
        byte[] plain = zip(new String[]{"secret.txt"},
                new byte[][]{"classified".getBytes(StandardCharsets.UTF_8)});
        byte[] out = plain.clone();
        // Set general-purpose bit 0 (encrypted) in the local header and central dir.
        for (int i = 0; i + 3 < out.length; i++) {
            boolean local = (out[i] & 0xFF) == 0x50 && (out[i + 1] & 0xFF) == 0x4B
                    && (out[i + 2] & 0xFF) == 0x03 && (out[i + 3] & 0xFF) == 0x04;
            boolean central = (out[i] & 0xFF) == 0x50 && (out[i + 1] & 0xFF) == 0x4B
                    && (out[i + 2] & 0xFF) == 0x01 && (out[i + 3] & 0xFF) == 0x02;
            if (local && i + 6 < out.length) out[i + 6] |= 0x01;
            if (central && i + 8 < out.length) out[i + 8] |= 0x01;
        }
        return out;
    }

    static byte[] corruptPdf() {
        byte[] header = "%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i * 31 % 251);
        byte[] out = new byte[header.length + junk.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(junk, 0, out, header.length, junk.length);
        return out;
    }

    static byte[] corruptOoxml() throws Exception {
        byte[] good = docx("this will be truncated");
        return java.util.Arrays.copyOf(good, good.length / 3);   // kills the central directory
    }

    static byte[] truncatedZip() throws Exception {
        byte[] good = zip(new String[]{"a.txt"}, new byte[][]{"alpha".getBytes()});
        return java.util.Arrays.copyOf(good, Math.max(16, good.length - 24));
    }

    // ---- tiny binary formats -------------------------------------------------

    /** Renders text to a clean raster — a stand-in for a flatbed scan. */
    static java.awt.image.BufferedImage renderText(String[] lines, int w, int h, int fs) {
        var img = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font("Serif", java.awt.Font.PLAIN, fs));
        int y = fs * 2;
        for (String l : lines) { g.drawString(l, fs, y); y += fs * 3 / 2; }
        g.dispose();
        return img;
    }

    /** M3-A. A raster whose text exists only as pixels. */
    static byte[] scannedImage(String[] lines) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(renderText(lines, 1100, 420, 34), "png", bos);
        return bos.toByteArray();
    }

    /** M3-A. A PDF whose page is a pure image: zero extractable text without OCR. */
    static byte[] scannedPdf(String[] lines) throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var rect = org.apache.pdfbox.pdmodel.common.PDRectangle.A4;
            var page = new org.apache.pdfbox.pdmodel.PDPage(rect);
            doc.addPage(page);
            var img = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
                    .createFromImage(doc, renderText(lines, 1240, 1754, 40));
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.drawImage(img, 0, 0, rect.getWidth(), rect.getHeight());
            }
            var bos = new java.io.ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }


    /**
     * A minimal RAR5 file (valid signature, no readable payload). Proves the
     * licence-driven Unsupported path: preserved and hashed, never dropped.
     */
    static byte[] rarSignature() {
        byte[] sig = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00};
        byte[] out = new byte[64];
        System.arraycopy(sig, 0, out, 0, sig.length);
        for (int i = sig.length; i < out.length; i++) out[i] = (byte) (i * 7);
        return out;
    }


    static byte[] png(int w, int h) throws Exception {
        var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, (x * 4) << 8 | (y * 5));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** Baseline JPEG with a hand-built APP1/EXIF block carrying GPS. */
    static byte[] jpegWithExif() throws Exception {
        var img = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", bos);
        byte[] jpg = bos.toByteArray();

        byte[] exif = buildExif();
        byte[] out = new byte[jpg.length + exif.length];
        System.arraycopy(jpg, 0, out, 0, 2);                 // SOI
        System.arraycopy(exif, 0, out, 2, exif.length);      // APP1
        System.arraycopy(jpg, 2, out, 2 + exif.length, jpg.length - 2);
        return out;
    }

    private static byte[] buildExif() {
        // TIFF little-endian, IFD0 with Make + GPS pointer, GPS IFD with lat/lon.
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        java.util.function.BiConsumer<Integer, Integer> u16 = (v, n) -> {
            t.write(v & 0xFF); t.write((v >> 8) & 0xFF);
        };
        java.util.function.Consumer<Integer> u32 = v -> {
            t.write(v & 0xFF); t.write((v >> 8) & 0xFF);
            t.write((v >> 16) & 0xFF); t.write((v >> 24) & 0xFF);
        };
        t.write('I'); t.write('I'); u16.accept(42, 0); u32.accept(8);   // header, IFD0 @8

        u16.accept(2, 0);                                    // 2 entries
        u16.accept(0x010F, 0); u16.accept(2, 0); u32.accept(6); u32.accept(0x2000 + 0);
        // placeholder — value offset patched below
        int makeOffsetPos = t.size() - 4;
        u16.accept(0x8825, 0); u16.accept(4, 0); u32.accept(1); u32.accept(0);
        int gpsPtrPos = t.size() - 4;
        u32.accept(0);                                       // next IFD = 0

        byte[] arr = t.toByteArray();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(arr);

        int makeAt = body.size();
        body.writeBytes("AEGIS\0".getBytes(StandardCharsets.US_ASCII));

        int gpsAt = body.size();
        ByteArrayOutputStream g = new ByteArrayOutputStream();
        writeU16(g, 4);
        writeGpsEntry(g, 1, 2, 2, 0);                        // lat ref 'N'
        int latRefPos = g.size() - 4;
        writeGpsEntry(g, 2, 5, 3, 0);                        // lat rationals
        int latPos = g.size() - 4;
        writeGpsEntry(g, 3, 2, 2, 0);                        // lon ref 'E'
        int lonRefPos = g.size() - 4;
        writeGpsEntry(g, 4, 5, 3, 0);
        int lonPos = g.size() - 4;
        writeU32(g, 0);
        byte[] gpsIfd = g.toByteArray();
        body.writeBytes(gpsIfd);

        int valuesAt = body.size();
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        int latRefAt = valuesAt + v.size(); v.writeBytes(new byte[]{'N', 0});
        int lonRefAt = valuesAt + v.size(); v.writeBytes(new byte[]{'E', 0});
        int latAt = valuesAt + v.size();
        writeRational(v, 50, 1); writeRational(v, 6, 1); writeRational(v, 3600, 100);
        int lonAt = valuesAt + v.size();
        writeRational(v, 8, 1); writeRational(v, 40, 1); writeRational(v, 4800, 100);
        body.writeBytes(v.toByteArray());

        byte[] tiff = body.toByteArray();
        patch32(tiff, makeOffsetPos, makeAt);
        patch32(tiff, gpsPtrPos, gpsAt);
        patch32(tiff, gpsAt + latRefPos, latRefAt);
        patch32(tiff, gpsAt + latPos, latAt);
        patch32(tiff, gpsAt + lonRefPos, lonRefAt);
        patch32(tiff, gpsAt + lonPos, lonAt);

        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write(0xFF); app1.write(0xE1);
        int len = 2 + 6 + tiff.length;
        app1.write((len >> 8) & 0xFF); app1.write(len & 0xFF);
        app1.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        app1.writeBytes(tiff);
        return app1.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }

    private static void writeRational(ByteArrayOutputStream o, int num, int den) {
        writeU32(o, num); writeU32(o, den);
    }

    private static void writeGpsEntry(ByteArrayOutputStream o, int tag, int type, int count, int val) {
        writeU16(o, tag); writeU16(o, type); writeU32(o, count); writeU32(o, val);
    }

    private static void patch32(byte[] a, int at, int v) {
        a[at] = (byte) (v & 0xFF);
        a[at + 1] = (byte) ((v >> 8) & 0xFF);
        a[at + 2] = (byte) ((v >> 16) & 0xFF);
        a[at + 3] = (byte) ((v >> 24) & 0xFF);
    }

    static byte[] gif() throws Exception {
        var img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "gif", bos);
        return bos.toByteArray();
    }

    static byte[] bmp() throws Exception {
        var img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "bmp", bos);
        return bos.toByteArray();
    }

    static byte[] id3Mp3() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("ID3".getBytes(StandardCharsets.US_ASCII));
        o.write(3); o.write(0); o.write(0);
        o.write(0); o.write(0); o.write(0); o.write(32);
        o.writeBytes("TIT2".getBytes(StandardCharsets.US_ASCII));
        o.write(0); o.write(0); o.write(0); o.write(10);
        o.write(0); o.write(0); o.write(0);
        o.writeBytes("Interview".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(new byte[24]);
        o.write(0xFF); o.write(0xFB);                       // frame sync
        o.writeBytes(new byte[64]);
        return o.toByteArray();
    }

    static byte[] mp4Stub() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0); o.write(0); o.write(0); o.write(0x18);
        o.writeBytes("ftypmp42".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(new byte[]{0, 0, 0, 0});
        o.writeBytes("mp42isom".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(new byte[128]);
        return o.toByteArray();
    }

    static byte[] wav() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeU32(o, 36 + 64);
        o.writeBytes("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        writeU32(o, 16); writeU16(o, 1); writeU16(o, 1);
        writeU32(o, 44100); writeU32(o, 88200); writeU16(o, 2); writeU16(o, 16);
        o.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        writeU32(o, 64);
        o.writeBytes(new byte[64]);
        return o.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/testdata");
        List<String> m = build(dir);
        System.out.println("Generated " + m.size() + " files in " + dir.toAbsolutePath());
    }
}
