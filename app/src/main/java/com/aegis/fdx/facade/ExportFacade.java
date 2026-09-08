package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.SearchResultDto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serialises result sets and reference data for download.
 *
 * <p>Each method returns the encoded bytes so the caller decides where they go.
 *
 * <p>This does <em>not</em> replace {@link com.aegis.fdx.export.Exporter}, which
 * implements the full 31-column production load file and keeps all of its existing
 * callers. This is the lighter "export what is on screen" path.
 */
public final class ExportFacade {

    /** Column order of the CSV export. */
    public static final String[] CSV_COLUMNS = {
            "id", "file_name", "file_path", "file_type", "file_size",
            "source_name", "aspect_name", "rank", "match_count", "status",
            "custodian", "md5", "sha256"
    };

    /** Extra column appended when per-match rows are requested. */
    public static final String CSV_LINE_MATCH_COLUMN = "matched_line";

    private ExportFacade() {
    }

    /** Results as UTF-8 CSV with a BOM, one row per result. */
    public static byte[] exportSearchResultsCsv(List<SearchResultDto> results) {
        return exportSearchResultsCsv(results, null, true);
    }

    public static byte[] exportSearchResultsCsv(List<SearchResultDto> results,
                                                String filename,
                                                boolean includeLineMatches) {
        requireResults(results);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            // UTF-8 BOM so Excel opens the file correctly, matching the AEGIS load file.
            w.write('\ufeff');
            writeRow(w, header(includeLineMatches));
            for (SearchResultDto r : results) {
                if (includeLineMatches && !r.snippets().isEmpty()) {
                    for (String snippet : r.snippets()) {
                        writeRow(w, row(r, snippet, true));
                    }
                } else {
                    writeRow(w, row(r, null, includeLineMatches));
                }
            }
        } catch (IOException e) {
            throw FacadeException.internal("failed to write CSV export", e);
        }
        return out.toByteArray();
    }

    /** Results as a JSON document. */
    /**
     * A vocabulary list — keywords, categories or category words — with whole-case
     * file counts, as CSV. The reference's list pages export their visible rows
     * client-side; this exports the whole case.
     */
    public static byte[] exportTermsCsv(List<com.aegis.fdx.facade.dto.TermSummary> terms) {
        StringBuilder sb = new StringBuilder("id,kind,text,normalized,word_count,file_count,hits\r\n");
        for (com.aegis.fdx.facade.dto.TermSummary t : terms) {
            sb.append(t.id()).append(',').append(t.kind().label()).append(',')
              .append(csvCell(t.text())).append(',').append(csvCell(t.normalized())).append(',')
              .append(t.wordCount()).append(',').append(t.fileCount()).append(',')
              .append(t.hits()).append("\r\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String csvCell(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    public static byte[] exportSearchResultsJson(List<SearchResultDto> results) {
        return exportSearchResultsJson(results, null);
    }

    public static byte[] exportSearchResultsJson(List<SearchResultDto> results, String filename) {
        requireResults(results);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"count\":").append(results.size()).append(",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SearchResultDto r = results.get(i);
            sb.append('{');
            json(sb, "id", r.id()).append(',');
            json(sb, "file_name", r.fileName()).append(',');
            json(sb, "file_path", r.filePath()).append(',');
            json(sb, "file_type", r.fileType()).append(',');
            sb.append("\"file_size\":").append(r.fileSize()).append(',');
            json(sb, "source_name", r.sourceName()).append(',');
            json(sb, "aspect_name", r.aspectName()).append(',');
            sb.append("\"rank\":").append(r.rank()).append(',');
            sb.append("\"match_count\":").append(r.matchCount()).append(',');
            json(sb, "status", r.status()).append(',');
            json(sb, "custodian", r.custodian()).append(',');
            json(sb, "md5", r.md5()).append(',');
            json(sb, "sha256", r.sha256()).append(',');
            sb.append("\"snippets\":[");
            for (int s = 0; s < r.snippets().size(); s++) {
                if (s > 0) {
                    sb.append(',');
                }
                sb.append(quote(r.snippets().get(s)));
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Results as a genuine {@code .xlsx}.
     *
     * <p>Writes the minimal OOXML parts directly, so the bytes open in a spreadsheet
     * application without adding a spreadsheet dependency to the build.
     */
    public static byte[] exportSearchResultsExcel(List<SearchResultDto> results) {
        return exportSearchResultsExcel(results, null);
    }

    public static byte[] exportSearchResultsExcel(List<SearchResultDto> results, String filename) {
        requireResults(results);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                     xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="Results" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>""");

            StringBuilder sheet = new StringBuilder();
            sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                    .append("<sheetData>");
            sheet.append("<row r=\"1\">");
            for (String c : CSV_COLUMNS) {
                sheet.append("<c t=\"inlineStr\"><is><t>").append(xml(c)).append("</t></is></c>");
            }
            sheet.append("</row>");
            int rowNum = 2;
            for (SearchResultDto r : results) {
                sheet.append("<row r=\"").append(rowNum++).append("\">");
                for (String v : row(r, null, false)) {
                    sheet.append("<c t=\"inlineStr\"><is><t>").append(xml(v)).append("</t></is></c>");
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (IOException e) {
            throw FacadeException.internal("failed to write XLSX export", e);
        }
        return out.toByteArray();
    }

    /** Settings as a JSON document. */
    public static byte[] exportSettings(Map<String, Object> settingsData) {
        if (settingsData == null) {
            throw FacadeException.validation("settings_data is required");
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : settingsData.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Object v = e.getValue();
            sb.append(quote(e.getKey())).append(':');
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append(quote(String.valueOf(v)));
            }
        }
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A ZIP archive of serialised tables plus a manifest.
     *
     * @param tables logical table name mapped to its already-serialised CSV payload
     */
    public static byte[] exportDatabaseBackup(Map<String, byte[]> tables) {
        if (tables == null || tables.isEmpty()) {
            throw FacadeException.validation("tables is required");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : tables.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey() + ".csv"));
                zip.write(e.getValue() == null ? new byte[0] : e.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("manifest.json"));
            StringBuilder m = new StringBuilder("{\"tables\":[");
            boolean first = true;
            for (String k : tables.keySet()) {
                if (!first) {
                    m.append(',');
                }
                first = false;
                m.append(quote(k));
            }
            m.append("]}");
            zip.write(m.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw FacadeException.internal("failed to write database backup", e);
        }
        return out.toByteArray();
    }

    // ---- helpers ---------------------------------------------------------

    private static void requireResults(List<SearchResultDto> results) {
        if (results == null) {
            throw FacadeException.validation("results is required");
        }
    }

    private static String[] header(boolean includeLineMatches) {
        if (!includeLineMatches) {
            return CSV_COLUMNS;
        }
        String[] h = new String[CSV_COLUMNS.length + 1];
        System.arraycopy(CSV_COLUMNS, 0, h, 0, CSV_COLUMNS.length);
        h[CSV_COLUMNS.length] = CSV_LINE_MATCH_COLUMN;
        return h;
    }

    private static String[] row(SearchResultDto r, String snippet, boolean includeLineMatches) {
        String[] base = {
                nz(r.id()), nz(r.fileName()), nz(r.filePath()), nz(r.fileType()),
                String.valueOf(r.fileSize()), nz(r.sourceName()), nz(r.aspectName()),
                String.valueOf(r.rank()), String.valueOf(r.matchCount()),
                nz(r.status()), nz(r.custodian()), nz(r.md5()), nz(r.sha256())
        };
        if (!includeLineMatches) {
            return base;
        }
        String[] withLine = new String[base.length + 1];
        System.arraycopy(base, 0, withLine, 0, base.length);
        withLine[base.length] = nz(snippet);
        return withLine;
    }

    /** RFC-4180 with CRLF, matching the production load-file conventions. */
    private static void writeRow(Writer w, String[] cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                w.write(',');
            }
            String v = cells[i] == null ? "" : cells[i];
            if (v.indexOf('"') >= 0 || v.indexOf(',') >= 0
                    || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                w.write('"');
                w.write(v.replace("\"", "\"\""));
                w.write('"');
            } else {
                w.write(v);
            }
        }
        w.write("\r\n");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static StringBuilder json(StringBuilder sb, String key, String value) {
        return sb.append(quote(key)).append(':').append(value == null ? "null" : quote(value));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** A mutable map for assembling backup payloads. */
    public static Map<String, byte[]> newBackupMap() {
        return new LinkedHashMap<>();
    }
}
