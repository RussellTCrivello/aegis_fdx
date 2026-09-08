package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ooxml.extractor.POIXMLTextExtractor;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.extractor.POIOLE2TextExtractor;

import java.io.InputStream;
import java.util.List;

/**
 * F-02 / F-08: Office document text + metadata via Apache POI.
 *
 * <p>Handles both OLE2 (DOC/XLS/PPT) and OOXML (DOCX/XLSX/PPTX) through POI's
 * {@link ExtractorFactory}, which selects the right extractor from the stream
 * itself rather than trusting the file extension.
 */
public final class OfficeAnalyzer implements Analyzer {

    private static final List<String> OOXML_EXT =
            List.of("docx", "xlsx", "pptx", "docm", "xlsm", "pptm");
    private static final List<String> OLE2_EXT =
            List.of("doc", "xls", "ppt");

    @Override public String id() { return "poi-office"; }

    @Override
    public List<String> mediaTypes() {
        return List.of(
                "application/msword",
                "application/vnd.ms-excel",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Override
    public double sniff(byte[] header, String fileName) {
        String ext = Magic.ext(fileName);
        if (Magic.isZip(header) && OOXML_EXT.contains(ext)) return 0.95;
        // OLE2 is shared with MSG — the mail analyzer outranks us for .msg.
        if (Magic.isOle2(header) && OLE2_EXT.contains(ext)) return 0.95;
        if (OOXML_EXT.contains(ext) || OLE2_EXT.contains(ext)) return 0.5;
        return 0;
    }

    @Override public int priority() { return 10; }

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        try (POITextExtractor extractor = ExtractorFactory.createExtractor(in)) {
            String text = extractor.getText();
            item.extractedText(text == null ? "" : text.strip());

            if (extractor instanceof POIXMLTextExtractor xml) {
                readOoxmlProps(item, xml);
            } else if (extractor instanceof POIOLE2TextExtractor ole) {
                readOle2Props(item, ole);
            }
            item.addMetadata("Extractor", extractor.getClass().getSimpleName());
        }
    }

    private void readOoxmlProps(Item item, POIXMLTextExtractor xml) {
        try {
            POIXMLProperties props = xml.getDocument().getProperties();
            POIXMLProperties.CoreProperties core = props.getCoreProperties();
            if (core != null) {
                put(item, "Title", core.getTitle());
                put(item, "Author", core.getCreator());
                put(item, "Subject", core.getSubject());
                put(item, "Keywords", core.getKeywords());
                put(item, "LastModifiedBy", core.getLastModifiedByUser());
                if (core.getCreated() != null) item.created(core.getCreated().toInstant());
                if (core.getModified() != null) item.modified(core.getModified().toInstant());
            }
            POIXMLProperties.ExtendedProperties ext = props.getExtendedProperties();
            if (ext != null) {
                put(item, "Application", ext.getApplication());
                put(item, "Company", ext.getCompany());
                if (ext.getPages() > 0) put(item, "Pages", String.valueOf(ext.getPages()));
                if (ext.getWords() > 0) put(item, "Words", String.valueOf(ext.getWords()));
            }
        } catch (Exception e) {
            item.errors().add("OOXML property read failed: " + e.getMessage());
        }
    }

    private void readOle2Props(Item item, POIOLE2TextExtractor ole) {
        try {
            SummaryInformation si = ole.getSummaryInformation();
            if (si == null) return;
            put(item, "Title", si.getTitle());
            put(item, "Author", si.getAuthor());
            put(item, "Subject", si.getSubject());
            put(item, "Keywords", si.getKeywords());
            put(item, "Application", si.getApplicationName());
            put(item, "LastModifiedBy", si.getLastAuthor());
            if (si.getCreateDateTime() != null) item.created(si.getCreateDateTime().toInstant());
            if (si.getLastSaveDateTime() != null) item.modified(si.getLastSaveDateTime().toInstant());
        } catch (Exception e) {
            item.errors().add("OLE2 property read failed: " + e.getMessage());
        }
    }

    private static void put(Item item, String k, String v) {
        if (v != null && !v.isBlank()) item.addMetadata(k, v);
    }
}
