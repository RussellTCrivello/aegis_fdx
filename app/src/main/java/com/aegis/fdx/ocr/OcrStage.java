package com.aegis.fdx.ocr;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M3-A. Applies OCR to elements the analyzers flagged with {@code needsOcr}.
 *
 * <p>Runs <b>after</b> the main ingest pass, on its own executor, so a slow OCR
 * pass never blocks intake, indexing, or the UI. Each element is independent, so
 * a failure is recorded against that element and the sweep continues (N-05).
 *
 * <p>Two sources of OCR work:
 * <ul>
 *   <li><b>Images</b> — handed straight to Tesseract.</li>
 *   <li><b>Scanned PDFs</b> — PDFBox reports little or no embedded text, so each
 *       page is rendered to a raster at {@value #RENDER_DPI} DPI and OCR'd.
 *       Page count is capped to keep a pathological file bounded.</li>
 * </ul>
 */
public final class OcrStage {

    /** 300 DPI is the accuracy/speed sweet spot for Tesseract on document scans. */
    private static final int RENDER_DPI = 300;

    /** Guard against a 5,000-page scan monopolising the OCR pool. */
    private static final int MAX_PDF_PAGES = 50;

    /** Below this many characters a PDF is treated as scanned rather than digital. */
    public static final int SCANNED_PDF_TEXT_THRESHOLD = 100;

    private final OcrEngine engine;
    private final List<String> languages;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong recognised = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong charsAdded = new AtomicLong();

    public OcrStage(OcrEngine engine, List<String> languages) {
        this.engine = engine;
        this.languages = languages == null || languages.isEmpty() ? List.of("eng") : languages;
    }

    public boolean available() { return engine.available(); }

    public long processedCount()  { return processed.get(); }
    public long recognisedCount() { return recognised.get(); }
    public long failedCount()     { return failed.get(); }
    public long charactersAdded() { return charsAdded.get(); }

    /**
     * OCRs one element in place, appending any recognised text to its extracted text.
     *
     * @param payload the element's raw bytes (image or PDF)
     * @return true when new text was recognised and appended
     */
    public boolean apply(Item item, byte[] payload) {
        if (!engine.available() || item == null || payload == null) return false;
        processed.incrementAndGet();

        try {
            String found = isPdf(payload) ? ocrPdf(item, payload) : ocrImage(item, payload);

            item.ocrApplied(true);
            item.needsOcr(false);

            if (found == null || found.isBlank()) {
                item.addMetadata("OCR", "no text recognised (" + String.join("+", languages) + ")");
                return false;
            }

            String existing = item.extractedText() == null ? "" : item.extractedText();
            item.extractedText(existing.isBlank() ? found : existing + "\n\n" + found);
            item.addMetadata("OCR", "applied (" + String.join("+", languages) + ")");
            item.addMetadata("OCR-Characters", String.valueOf(found.length()));

            // An element that failed only because it had no extractable text is now
            // legitimately indexed.
            if (item.status() == ItemStatus.UNSUPPORTED && !found.isBlank()) {
                item.status(ItemStatus.INDEXED);
            }
            recognised.incrementAndGet();
            charsAdded.addAndGet(found.length());
            return true;

        } catch (Throwable t) {
            // Throwable: OCR touches native rendering; an Error must not kill the sweep.
            failed.incrementAndGet();
            item.ocrApplied(true);
            item.needsOcr(false);
            item.errors().add("OCR failed: " + t.getClass().getSimpleName()
                    + ": " + String.valueOf(t.getMessage()));
            item.addMetadata("OCR", "failed");
            return false;
        }
    }

    private String ocrImage(Item item, byte[] payload) throws Exception {
        OcrEngine.Result r = engine.recognise(payload, languages);
        recordResult(item, r, 1);
        return r.hasText() ? r.text() : null;
    }

    /** Renders each page and OCRs it. Used when a PDF carries no usable text layer. */
    private String ocrPdf(Item item, byte[] payload) throws Exception {
        StringBuilder sb = new StringBuilder();
        int pagesDone = 0;
        long totalMs = 0;

        try (PDDocument doc = Loader.loadPDF(payload)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), MAX_PDF_PAGES);
            if (doc.getNumberOfPages() > MAX_PDF_PAGES) {
                item.addMetadata("OCR-Truncated",
                        "OCR limited to first " + MAX_PDF_PAGES + " of "
                                + doc.getNumberOfPages() + " pages");
            }

            for (int i = 0; i < pages; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.GRAY);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(img, "png", png);
                img.flush();

                OcrEngine.Result r = engine.recognise(png.toByteArray(), languages);
                totalMs += r.millis();
                pagesDone++;
                if (r.hasText()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append("[page ").append(i + 1).append("]\n").append(r.text());
                }
            }
        }
        item.addMetadata("OCR-Pages", String.valueOf(pagesDone));
        item.addMetadata("OCR-Duration-Ms", String.valueOf(totalMs));
        return sb.length() == 0 ? null : sb.toString();
    }

    private void recordResult(Item item, OcrEngine.Result r, int units) {
        item.addMetadata("OCR-Duration-Ms", String.valueOf(r.millis()));
        if (!r.success() && r.error() != null) {
            item.errors().add("OCR: " + r.error());
            failed.incrementAndGet();
        }
    }

    /**
     * Decides whether an element needs OCR. Called by the pipeline after extraction.
     *
     * <p>Images always qualify. PDFs qualify only when their embedded text layer is
     * effectively empty — OCR'ing a digital PDF would waste seconds and produce
     * worse text than PDFBox already extracted.
     */
    public static boolean shouldOcr(Item item, byte[] payload) {
        if (item == null || item.ocrApplied()) return false;
        String media = item.mediaType() == null ? "" : item.mediaType();
        int textLen = item.extractedText() == null ? 0 : item.extractedText().strip().length();

        if (media.startsWith("image/")) return true;
        if (media.equals("application/pdf") || (payload != null && isPdf(payload))) {
            return textLen < SCANNED_PDF_TEXT_THRESHOLD;
        }
        return item.needsOcr();
    }

    private static boolean isPdf(byte[] b) {
        return b != null && b.length > 4
                && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    /** Snapshot of the sweep for the processing report. */
    public record Summary(long processed, long recognised, long failed, long charactersAdded,
                          boolean engineAvailable, String engineVersion, List<String> languages) { }

    public Summary summary() {
        return new Summary(processed.get(), recognised.get(), failed.get(), charsAdded.get(),
                engine.available(), engine.version(), new ArrayList<>(languages));
    }
}
