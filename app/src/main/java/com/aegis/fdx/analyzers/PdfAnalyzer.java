package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.List;

/**
 * F-08: PDF text + metadata via PDFBox 3.
 *
 * <p>Encrypted PDFs are tried against the case password list; on failure the
 * element is marked LOCKED and the run continues (F-04). A PDF that yields little
 * or no text is flagged for OCR downstream (F-08).
 */
public final class PdfAnalyzer implements Analyzer {

    /** Below this many characters a PDF is treated as scanned → OCR candidate. */
    private static final int OCR_THRESHOLD = 24;

    private final List<String> passwords;

    public PdfAnalyzer(List<String> passwords) {
        this.passwords = passwords == null ? List.of() : passwords;
    }

    @Override public String id() { return "pdfbox"; }

    @Override public List<String> mediaTypes() { return List.of("application/pdf"); }

    @Override
    public double sniff(byte[] header, String fileName) {
        if (Magic.isPdf(header)) return 1.0;
        return Magic.extIn(fileName, "pdf") ? 0.4 : 0;
    }

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        item.mediaType("application/pdf");

        PDDocument doc = null;
        try {
            doc = open(in, item);
            if (doc == null) return;      // locked; status already set

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            item.extractedText(text == null ? "" : text.strip());

            PDDocumentInformation info = doc.getDocumentInformation();
            if (info != null) {
                if (notBlank(info.getTitle())) item.addMetadata("Title", info.getTitle());
                if (notBlank(info.getAuthor())) item.addMetadata("Author", info.getAuthor());
                if (notBlank(info.getSubject())) item.addMetadata("Subject", info.getSubject());
                if (notBlank(info.getKeywords())) item.addMetadata("Keywords", info.getKeywords());
                if (notBlank(info.getCreator())) item.addMetadata("Creator", info.getCreator());
                if (notBlank(info.getProducer())) item.addMetadata("Producer", info.getProducer());
                if (info.getCreationDate() != null) {
                    item.created(info.getCreationDate().toInstant());
                }
                if (info.getModificationDate() != null) {
                    item.modified(info.getModificationDate().toInstant());
                }
            }
            item.addMetadata("Pages", String.valueOf(doc.getNumberOfPages()));
            item.addMetadata("PDF-Encrypted", String.valueOf(doc.isEncrypted()));

            if (item.extractedText().length() < OCR_THRESHOLD) {
                item.needsOcr(true);
                item.addMetadata("OCR-Candidate", "true (no extractable text layer)");
            }
        } finally {
            if (doc != null) {
                try { doc.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** Tries the empty password first, then each case password (F-04). */
    private PDDocument open(InputStream in, Item item) throws Exception {
        byte[] bytes = in.readAllBytes();
        try {
            return Loader.loadPDF(new RandomAccessReadBuffer(bytes));
        } catch (InvalidPasswordException first) {
            for (String pw : passwords) {
                try {
                    PDDocument d = Loader.loadPDF(new RandomAccessReadBuffer(bytes), pw);
                    item.addMetadata("Unlocked-With", "supplied password");
                    return d;
                } catch (InvalidPasswordException ignored) {
                    // try the next candidate
                }
            }
            item.markLocked(passwords.size());
            return null;
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
