package com.aegis.fdx.export;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * M3-B. Writes an export set to disk (F-25..F-27).
 *
 * <p>Guarantees that matter forensically:
 * <ul>
 *   <li><b>Hidden is excluded</b> unless explicitly overridden (F-24).</li>
 *   <li><b>Hashes are re-verified</b> after writing. A native copy whose SHA-256
 *       no longer matches the ingested value is reported as a failure rather than
 *       silently produced — a corrupted production is worse than none.</li>
 *   <li><b>Every element gets a load-file row</b>, including ones whose payload
 *       could not be written, so the CSV is a complete account of the request.</li>
 *   <li><b>Metadata, tags, notes, hashes and relationships travel with the data</b>
 *       in the CSV and manifest.</li>
 * </ul>
 */
public final class Exporter {

    /** F-27 fixed load-file columns. Order is part of the contract; do not reorder. */
    public static final String[] COLUMNS = {
            "ElementID", "ParentID", "AttachedFrom", "DuplicateOf", "FileName",
            "Extension", "MediaType", "SizeBytes", "MD5", "SHA256", "Custodian",
            "SourcePath", "ContainerPath", "Depth", "Status", "Tags", "Notes",
            "From", "To", "CC", "Subject", "SentDate", "CreatedDate", "ModifiedDate",
            "AttachmentCount", "IsDuplicate", "OcrApplied", "GeoLocation",
            "ExportedPath", "TextPath", "Errors"
    };

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final CaseFolder folder;
    private final CaseDatabase db;
    private final Consumer<String> log;

    public Exporter(CaseFolder folder, CaseDatabase db, Consumer<String> log) {
        this.folder = folder;
        this.db = db;
        this.log = log == null ? m -> { } : log;
    }

    /**
     * Runs the export.
     *
     * @param items the candidate set, already resolved by scope
     */
    public Result run(ExportRequest req, List<Item> items) throws IOException {
        Path dest = req.destination();
        if (dest == null) throw new IllegalArgumentException("no export destination");
        Files.createDirectories(dest);

        List<Item> included = new ArrayList<>();
        int skippedHidden = 0;
        for (Item it : items) {
            if (it == null) continue;
            if (!req.includeHidden() && it.isHidden()) { skippedHidden++; continue; }  // F-24
            included.add(it);
        }

        log.accept("Export starting: " + req + " · " + included.size() + " element(s)"
                + (skippedHidden > 0 ? " (" + skippedHidden + " hidden excluded)" : ""));

        Path nativeDir = dest.resolve("native");
        Path textDir = dest.resolve("text");
        List<String[]> rows = new ArrayList<>();
        List<String> manifest = new ArrayList<>();
        int written = 0, failed = 0, verified = 0, verifyFailed = 0;

        for (Item it : included) {
            String relPath = "";
            String textRel = "";
            String error = "";

            try {
                Path target = resolveTarget(nativeDir, it, req);
                Files.createDirectories(target.getParent());

                // Writers may adjust the filename (adding .eml/.pdf/.txt), so they
                // return the path actually written. Hashing the requested path
                // instead reported every renamed export as a failure.
                Path actual = switch (req.format()) {
                    case NATIVE -> writeNative(it, target);
                    case EML    -> writeEml(it, target);
                    case PDF    -> writePdf(it, target);
                    case TEXT   -> writeTextOnly(it, target);
                };

                if (actual != null) {
                    written++;
                    relPath = dest.relativize(actual).toString().replace('\\', '/');

                    String sha = sha256(actual);
                    manifest.add(sha + "  " + relPath);

                    // Chain of custody: a native copy must be byte-identical.
                    if (req.verifyHashes() && req.format() == ExportRequest.Format.NATIVE
                            && it.sha256() != null && !it.sha256().isBlank()) {
                        if (sha.equalsIgnoreCase(it.sha256())) {
                            verified++;
                        } else {
                            verifyFailed++;
                            error = "HASH MISMATCH: expected " + it.sha256() + " got " + sha;
                            log.accept("VERIFY FAILED " + it.name() + " — " + error);
                        }
                    }
                } else {
                    failed++;
                    error = "no payload available to export";
                }

                if (req.writeText() && it.extractedText() != null
                        && !it.extractedText().isBlank()) {
                    Path tp = textDir.resolve(safe(it.id()) + ".txt");
                    Files.createDirectories(tp.getParent());
                    Files.writeString(tp, it.extractedText(), StandardCharsets.UTF_8);
                    textRel = dest.relativize(tp).toString().replace('\\', '/');
                }

            } catch (Exception e) {
                failed++;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.accept("Export failed for " + it.name() + " — " + error);
            }

            rows.add(row(it, relPath, textRel, error));
        }

        Path csv = dest.resolve("loadfile.csv");
        if (req.writeLoadFile()) writeCsv(csv, rows);

        if (req.writeManifest()) {
            Files.write(dest.resolve("MANIFEST-SHA256.txt"),
                    manifest, StandardCharsets.UTF_8);
        }

        Result result = new Result(included.size(), written, failed, skippedHidden,
                verified, verifyFailed, dest, req.writeLoadFile() ? csv : null);

        try {
            db.audit("analyst", "EXPORT", result.toString(), null);
        } catch (Exception ignored) { }

        log.accept("Export complete: " + result);
        return result;
    }

    // =====================================================================
    // Format writers
    // =====================================================================

    /** F-26 native: byte-for-byte copy of the original evidence. */
    private Path writeNative(Item it, Path target) throws IOException {
        Path src = sourceOf(it);
        if (src != null && Files.isRegularFile(src)) {
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
        // Elements extracted from a container have no standalone source file. Their
        // preserved bytes live in the case data folder.
        Path stored = folder.dataFileFor(it.id(), it.name());
        if (Files.isRegularFile(stored)) {
            Files.copy(stored, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
        // Last resort: the extracted text, clearly named so it is never mistaken
        // for the original.
        if (it.extractedText() != null && !it.extractedText().isBlank()) {
            Path alt = target.resolveSibling(target.getFileName() + ".extracted.txt");
            Files.writeString(alt, it.extractedText(), StandardCharsets.UTF_8);
            return null;   // not a native copy: reported as such
        }
        return null;
    }

    /** F-26 EML: RFC-822. Non-email elements fall back to native. */
    private Path writeEml(Item it, Path target) throws IOException {
        if (!it.isEmail()) return writeNative(it, target);

        Path emlTarget = target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".eml")
                ? target : target.resolveSibling(target.getFileName() + ".eml");

        StringBuilder sb = new StringBuilder(1024);
        header(sb, "Message-ID", it.messageId());
        header(sb, "From", it.from());
        header(sb, "To", it.to());
        header(sb, "Cc", it.cc());
        header(sb, "Subject", it.subject());
        if (it.sentDate() != null) header(sb, "Date", ISO.format(it.sentDate()));
        header(sb, "X-Aegis-Element-ID", it.id());
        header(sb, "X-Aegis-Custodian", it.custodian());
        header(sb, "X-Aegis-SHA256", it.sha256());
        if (it.attachmentCount() > 0) {
            header(sb, "X-Aegis-Attachments", String.valueOf(it.attachmentCount()));
        }
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n");
        sb.append("\r\n");
        sb.append(it.extractedText() == null ? "" : it.extractedText().replace("\n", "\r\n"));

        Files.writeString(emlTarget, sb.toString(), StandardCharsets.UTF_8);
        return emlTarget;
    }

    /** F-26 PDF: images are embedded; everything else is laid out as text. */
    private Path writePdf(Item it, Path target) throws IOException {
        Path pdfTarget = target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? target : target.resolveSibling(target.getFileName() + ".pdf");

        Path src = sourceOf(it);
        String media = it.mediaType() == null ? "" : it.mediaType();

        // Already a PDF: copy rather than re-render, preserving the original.
        if (media.equals("application/pdf") && src != null && Files.isRegularFile(src)) {
            Files.copy(src, pdfTarget, StandardCopyOption.REPLACE_EXISTING);
            return pdfTarget;
        }

        try (PDDocument doc = new PDDocument()) {
            if (media.startsWith("image/") && src != null && Files.isRegularFile(src)) {
                embedImage(doc, src, it);
            } else {
                layoutText(doc, it);
            }
            doc.save(pdfTarget.toFile());
        } catch (Exception e) {
            throw new IOException("PDF render failed: " + e.getMessage(), e);
        }
        return pdfTarget;
    }

    private void embedImage(PDDocument doc, Path src, Item it) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject img = PDImageXObject.createFromFile(src.toString(), doc);

        float pw = PDRectangle.A4.getWidth() - 72;
        float ph = PDRectangle.A4.getHeight() - 108;
        float scale = Math.min(pw / img.getWidth(), ph / img.getHeight());
        if (scale > 1) scale = 1;
        float w = img.getWidth() * scale;
        float h = img.getHeight() * scale;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 10);
            cs.newLineAtOffset(36, PDRectangle.A4.getHeight() - 40);
            cs.showText(sanitize(it.id() + "  " + it.name()));
            cs.endText();
            cs.drawImage(img, 36, (PDRectangle.A4.getHeight() - h) / 2 - 20, w, h);
        }
    }

    private void layoutText(PDDocument doc, Item it) throws IOException {
        var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        final float margin = 50, leading = 13, fontSize = 9;
        final float width = PDRectangle.A4.getWidth() - 2 * margin;

        List<String> lines = new ArrayList<>();
        lines.add("@@BOLD@@" + it.name());
        lines.add("Element " + it.id() + "  ·  " + nz(it.custodian())
                + "  ·  SHA-256 " + shorten(it.sha256()));
        lines.add("");
        for (String para : nz(it.extractedText()).split("\\R")) {
            lines.addAll(wrap(para, font, fontSize, width));
        }
        if (lines.size() == 3) lines.add("(no extracted text)");

        int i = 0;
        while (i < lines.size()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(margin, PDRectangle.A4.getHeight() - margin);
                cs.setLeading(leading);
                int onPage = 0;
                int max = (int) ((PDRectangle.A4.getHeight() - 2 * margin) / leading);
                while (i < lines.size() && onPage < max) {
                    String l = lines.get(i++);
                    if (l.startsWith("@@BOLD@@")) {
                        cs.setFont(bold, fontSize + 2);
                        cs.showText(sanitize(l.substring(8)));
                        cs.setFont(font, fontSize);
                    } else {
                        cs.showText(sanitize(l));
                    }
                    cs.newLine();
                    onPage++;
                }
                cs.endText();
            }
        }
    }

    private Path writeTextOnly(Item it, Path target) throws IOException {
        Path t = target.resolveSibling(target.getFileName() + ".txt");
        Files.writeString(t, nz(it.extractedText()), StandardCharsets.UTF_8);
        return t;
    }

    // =====================================================================
    // Layout, CSV, helpers
    // =====================================================================

    /** F-26 folder layouts. Ids prefix every name so collisions are impossible. */
    private Path resolveTarget(Path base, Item it, ExportRequest req) {
        String file = safe(it.id()) + "_" + safe(it.name());
        return switch (req.layout()) {
            case FLAT -> base.resolve(file);
            case BY_TYPE -> base.resolve(
                    it.extension() == null || it.extension().isBlank()
                            ? "unknown" : safe(it.extension().toLowerCase(Locale.ROOT)))
                    .resolve(file);
            case BY_CUSTODIAN -> base.resolve(
                    it.custodian() == null || it.custodian().isBlank()
                            ? "unknown" : safe(it.custodian()))
                    .resolve(file);
            case HIERARCHY -> {
                String container = it.containerPath();
                if (container == null || container.isBlank()) {
                    yield base.resolve("loose").resolve(file);
                }
                Path p = base;
                for (String seg : container.split("[/\\\\!]")) {
                    if (!seg.isBlank()) p = p.resolve(safe(seg));
                }
                yield p.resolve(file);
            }
        };
    }

    private String[] row(Item it, String exported, String textPath, String error) {
        return new String[]{
                nz(it.id()), nz(it.parentId()), nz(it.attachedFrom()), nz(it.duplicateOf()),
                nz(it.name()), nz(it.extension()), nz(it.mediaType()),
                String.valueOf(it.size()), nz(it.md5()), nz(it.sha256()),
                nz(it.custodian()), nz(it.sourcePath()), nz(it.containerPath()),
                String.valueOf(it.depth()),
                it.status() == null ? "" : it.status().label(),
                String.join(";", it.tags()), nz(it.notes()),
                nz(it.from()), nz(it.to()), nz(it.cc()), nz(it.subject()),
                it.sentDate() == null ? "" : ISO.format(it.sentDate()),
                it.created() == null ? "" : ISO.format(it.created()),
                it.modified() == null ? "" : ISO.format(it.modified()),
                String.valueOf(it.attachmentCount()),
                String.valueOf(it.duplicate()), String.valueOf(it.ocrApplied()),
                nz(it.geoLocation()), exported, textPath,
                error.isEmpty() ? String.join(" | ", it.errors()) : error
        };
    }

    /** F-27 UTF-8 CSV with a BOM so Excel opens it correctly on Windows. */
    private void writeCsv(Path csv, List<String[]> rows) throws IOException {
        try (OutputStream os = Files.newOutputStream(csv);
             BufferedWriter w = new BufferedWriter(
                     new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            w.write(String.join(",", COLUMNS));
            w.write("\r\n");
            for (String[] r : rows) {
                StringBuilder sb = new StringBuilder(256);
                for (int i = 0; i < r.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(quote(r[i]));
                }
                w.write(sb.toString());
                w.write("\r\n");
            }
        }
    }

    /** RFC-4180 quoting. Newlines are preserved inside quotes. */
    private static String quote(String v) {
        if (v == null) return "";
        boolean needs = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        String s = v.replace("\"", "\"\"");
        return needs ? '"' + s + '"' : s;
    }

    private Path sourceOf(Item it) {
        if (it.sourcePath() == null || it.sourcePath().isBlank()) return null;
        // Container members carry the archive's path; they are not standalone files.
        if (it.depth() > 0) return null;
        try { return Path.of(it.sourcePath()); } catch (Exception e) { return null; }
    }

    private static String sha256(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IOException("hash failed: " + e.getMessage(), e);
        }
    }

    private static List<String> wrap(String text, PDType1Font font, float size, float width)
            throws IOException {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) { out.add(""); return out; }
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String probe = line.length() == 0 ? word : line + " " + word;
            float w;
            try { w = font.getStringWidth(sanitize(probe)) / 1000 * size; }
            catch (Exception e) { w = probe.length() * size * 0.5f; }
            if (w > width && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(probe);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /**
     * Standard-14 PDF fonts are WinAnsi only. Arabic/CJK/Cyrillic text would throw
     * and abort the whole export, so unsupported glyphs are replaced and the text
     * remains in the CSV and text sidecar where it is fully preserved.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\t') { sb.append("    "); continue; }
            if (c < 32) continue;
            sb.append(c <= 0xFF ? c : '?');
        }
        return sb.toString();
    }

    private static void header(StringBuilder sb, String k, String v) {
        if (v != null && !v.isBlank()) sb.append(k).append(": ").append(v).append("\r\n");
    }

    /** Windows reserved device names — unusable as a filename even with an extension. */
    private static final java.util.regex.Pattern WIN_RESERVED = java.util.regex.Pattern.compile(
            "(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$");

    /**
     * Makes an element name safe as a filename on every target platform.
     *
     * <p>Windows is the strictest and the primary target (N-01), so its rules apply
     * everywhere — an export produced on Linux must remain usable when copied to a
     * Windows review machine.
     *
     * <p>Beyond illegal characters this handles three cases that would otherwise
     * cause a silent evidence loss on Windows:
     * <ul>
     *   <li><b>Reserved device names</b> ({@code CON}, {@code NUL}, {@code COM1}…)
     *       cannot be created at all, even with an extension.</li>
     *   <li><b>Trailing dots and spaces</b> are silently stripped by the Win32 API,
     *       so {@code "report."} and {@code "report"} collide.</li>
     *   <li><b>{@code .} and {@code ..}</b> are directory references, not names.</li>
     * </ul>
     */
    private static String safe(String s) {
        if (s == null || s.isBlank()) return "unnamed";

        String out = s.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001F]", "_").trim();

        // Directory references are never valid filenames.
        if (out.equals(".") || out.equals("..")) return "unnamed";

        // Reserved device names: prefix rather than replace, so the original name
        // stays readable to a reviewer.
        if (WIN_RESERVED.matcher(out).matches()) out = "_" + out;

        // Win32 strips trailing dots/spaces, which would collide distinct elements.
        int end = out.length();
        while (end > 0 && (out.charAt(end - 1) == '.' || out.charAt(end - 1) == ' ')) end--;
        out = out.substring(0, end);

        // Leave room for the element-id prefix and any extension the writer appends,
        // keeping the full path clear of the 260-character MAX_PATH limit.
        if (out.length() > 120) out = out.substring(0, 120);

        out = out.trim();
        return out.isBlank() ? "unnamed" : out;
    }

    private static String shorten(String s) {
        return s == null || s.length() < 16 ? nz(s) : s.substring(0, 16) + "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Outcome of an export run. */
    public record Result(int candidates, int written, int failed, int skippedHidden,
                         int hashVerified, int hashMismatched,
                         Path destination, Path loadFile) {

        public boolean clean() { return failed == 0 && hashMismatched == 0; }

        @Override public String toString() {
            return written + "/" + candidates + " written, " + failed + " failed, "
                    + skippedHidden + " hidden excluded, " + hashVerified + " hash-verified"
                    + (hashMismatched > 0 ? ", " + hashMismatched + " MISMATCHED" : "");
        }
    }
}
