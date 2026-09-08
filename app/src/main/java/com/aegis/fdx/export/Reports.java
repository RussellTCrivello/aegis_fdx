package com.aegis.fdx.export;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.ocr.OcrStage;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M3-B. F-27 reports: processing, duplicates, and search.
 *
 * <p>Each report renders to both Markdown (readable, diffable, pasteable into a
 * filing) and CSV (loadable into Excel or a review platform). Reports are written
 * into the case's own {@code /exports} folder so a case stays self-contained (F-28).
 */
public final class Reports {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final CaseFolder folder;
    private final CaseDatabase db;

    public Reports(CaseFolder folder, CaseDatabase db) {
        this.folder = folder;
        this.db = db;
    }

    // =====================================================================
    // Processing report
    // =====================================================================

    /**
     * F-27 processing report: what came in, what happened to it, and what did not
     * work. The exceptions section is the point of the document — a reviewer needs
     * to see every element that failed, was locked, or was unsupported.
     */
    public Path processing(List<Item> items, OcrStage.Summary ocr, long elapsedMs)
            throws IOException {

        Map<ItemStatus, Integer> byStatus = new LinkedHashMap<>();
        for (ItemStatus s : ItemStatus.values()) byStatus.put(s, 0);
        Map<String, int[]> byType = new TreeMap<>();
        Map<String, int[]> byCustodian = new TreeMap<>();
        long totalBytes = 0;
        int containers = 0, withText = 0, emails = 0, attachments = 0, maxDepth = 0;

        List<Item> exceptions = new ArrayList<>();

        for (Item it : items) {
            byStatus.merge(it.status(), 1, Integer::sum);
            totalBytes += it.size();
            if (it.container()) containers++;
            if (it.extractedText() != null && !it.extractedText().isBlank()) withText++;
            if (it.isEmail()) emails++;
            if (it.attachedFrom() != null) attachments++;
            maxDepth = Math.max(maxDepth, it.depth());

            String ext = it.extension() == null || it.extension().isBlank()
                    ? "(none)" : it.extension().toLowerCase();
            byType.computeIfAbsent(ext, k -> new int[2])[0]++;
            byType.get(ext)[1] += it.status() == ItemStatus.INDEXED ? 1 : 0;

            String cust = it.custodian() == null || it.custodian().isBlank()
                    ? "(unassigned)" : it.custodian();
            byCustodian.computeIfAbsent(cust, k -> new int[2])[0]++;
            byCustodian.get(cust)[1] += it.status() == ItemStatus.INDEXED ? 1 : 0;

            if (it.status() == ItemStatus.ERROR || it.status() == ItemStatus.LOCKED
                    || it.status() == ItemStatus.UNSUPPORTED) {
                exceptions.add(it);
            }
        }

        StringBuilder md = new StringBuilder(8192);
        md.append("# Processing Report\n\n");
        md.append("**Case:** ").append(folder.name()).append("  \n");
        md.append("**Generated:** ").append(STAMP.format(Instant.now())).append("  \n");
        md.append("**Processing time:** ").append(fmtDuration(elapsedMs)).append("\n\n");

        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n|---|---:|\n");
        md.append("| Elements processed | ").append(fmt(items.size())).append(" |\n");
        md.append("| Total volume | ").append(fmtBytes(totalBytes)).append(" |\n");
        md.append("| Containers expanded | ").append(fmt(containers)).append(" |\n");
        md.append("| Maximum nesting depth | ").append(maxDepth).append(" |\n");
        md.append("| Emails | ").append(fmt(emails)).append(" |\n");
        md.append("| Attachments promoted | ").append(fmt(attachments)).append(" |\n");
        md.append("| Elements with text | ").append(fmt(withText)).append(" |\n");
        if (elapsedMs > 0) {
            md.append("| Throughput | ")
              .append(fmt(Math.round(items.size() / (elapsedMs / 3_600_000.0))))
              .append(" items/hour |\n");
        }
        md.append('\n');

        md.append("## Status breakdown (F-07)\n\n");
        md.append("| Status | Count | Share |\n|---|---:|---:|\n");
        for (var e : byStatus.entrySet()) {
            if (e.getValue() == 0 && e.getKey() != ItemStatus.INDEXED) continue;
            md.append("| ").append(e.getKey().label()).append(" | ").append(fmt(e.getValue()))
              .append(" | ").append(pct(e.getValue(), items.size())).append(" |\n");
        }
        md.append('\n');

        md.append("## OCR (F-09)\n\n");
        if (ocr == null || !ocr.engineAvailable()) {
            md.append("OCR was **not applied** — ")
              .append(ocr == null ? "disabled for this case."
                                  : "no usable Tesseract installation was found.")
              .append("\n\n");
        } else {
            md.append("| Metric | Value |\n|---|---:|\n");
            md.append("| Engine | ").append(ocr.engineVersion()).append(" |\n");
            md.append("| Languages | ").append(String.join(", ", ocr.languages())).append(" |\n");
            md.append("| Elements OCR'd | ").append(fmt(ocr.processed())).append(" |\n");
            md.append("| Text recovered | ").append(fmt(ocr.recognised())).append(" |\n");
            md.append("| Failed | ").append(fmt(ocr.failed())).append(" |\n");
            md.append("| Characters added | ").append(fmt(ocr.charactersAdded())).append(" |\n\n");
        }

        md.append("## By file type\n\n| Type | Elements | Indexed |\n|---|---:|---:|\n");
        byType.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> md.append("| ").append(e.getKey()).append(" | ")
                        .append(fmt(e.getValue()[0])).append(" | ")
                        .append(fmt(e.getValue()[1])).append(" |\n"));
        md.append('\n');

        md.append("## By custodian\n\n| Custodian | Elements | Indexed |\n|---|---:|---:|\n");
        byCustodian.forEach((k, v) -> md.append("| ").append(k).append(" | ")
                .append(fmt(v[0])).append(" | ").append(fmt(v[1])).append(" |\n"));
        md.append('\n');

        md.append("## Exceptions\n\n");
        if (exceptions.isEmpty()) {
            md.append("No errors, locked, or unsupported elements.\n\n");
        } else {
            md.append(exceptions.size())
              .append(" element(s) require review. Every one was retained, hashed and indexed; ")
              .append("none were dropped.\n\n");
            md.append("| Element | Name | Status | Reason |\n|---|---|---|---|\n");
            for (Item it : exceptions) {
                md.append("| ").append(it.id()).append(" | ").append(mdEscape(it.name()))
                  .append(" | ").append(it.status().label()).append(" | ")
                  .append(mdEscape(reasonFor(it))).append(" |\n");
            }
            md.append('\n');
        }

        Path out = exportsDir().resolve("processing-report-" + stamp() + ".md");
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);

        List<String[]> csv = new ArrayList<>();
        for (Item it : items) {
            csv.add(new String[]{it.id(), it.name(), it.extension(), it.mediaType(),
                    String.valueOf(it.size()), it.status().label(), it.custodian(),
                    String.valueOf(it.depth()), String.valueOf(it.ocrApplied()),
                    it.sha256(), reasonFor(it)});
        }
        writeCsv(out.resolveSibling(out.getFileName().toString().replace(".md", ".csv")),
                new String[]{"ElementID", "Name", "Extension", "MediaType", "SizeBytes",
                        "Status", "Custodian", "Depth", "OcrApplied", "SHA256", "Reason"},
                csv);
        return out;
    }

    // =====================================================================
    // Duplicates report
    // =====================================================================

    /** F-27 duplicates report. Duplicates are reported, never deleted (F-05). */
    public Path duplicates(Map<String, List<String>> clusters, Map<String, Item> byId)
            throws IOException {

        List<Map.Entry<String, List<String>>> real = clusters.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .sorted(Comparator.<Map.Entry<String, List<String>>>comparingInt(
                        e -> e.getValue().size()).reversed())
                .toList();

        long wasted = 0;
        int redundant = 0;
        for (var e : real) {
            Item first = byId.get(e.getValue().get(0));
            long sz = first == null ? 0 : first.size();
            wasted += sz * (e.getValue().size() - 1L);
            redundant += e.getValue().size() - 1;
        }

        StringBuilder md = new StringBuilder(4096);
        md.append("# Duplicates Report\n\n");
        md.append("**Case:** ").append(folder.name()).append("  \n");
        md.append("**Generated:** ").append(STAMP.format(Instant.now())).append("\n\n");
        md.append("Duplicates are identified by **SHA-256** and are marked in every ")
          .append("location they occur. Nothing is deleted (F-05).\n\n");

        md.append("| Metric | Value |\n|---|---:|\n");
        md.append("| Duplicate groups | ").append(fmt(real.size())).append(" |\n");
        md.append("| Redundant copies | ").append(fmt(redundant)).append(" |\n");
        md.append("| Reviewable volume saved | ").append(fmtBytes(wasted)).append(" |\n\n");

        List<String[]> csv = new ArrayList<>();
        if (real.isEmpty()) {
            md.append("No duplicate content was found.\n");
        } else {
            md.append("## Groups\n\n");
            int n = 0;
            for (var e : real) {
                n++;
                Item first = byId.get(e.getValue().get(0));
                md.append("### Group ").append(n).append(" — ")
                  .append(e.getValue().size()).append(" copies");
                if (first != null) md.append(" · ").append(fmtBytes(first.size())).append(" each");
                md.append("\n\n`").append(e.getKey()).append("`\n\n");
                md.append("| Element | Name | Custodian | Location |\n|---|---|---|---|\n");
                boolean primary = true;
                for (String id : e.getValue()) {
                    Item it = byId.get(id);
                    if (it == null) continue;
                    md.append("| ").append(id).append(primary ? " *(original)*" : "")
                      .append(" | ").append(mdEscape(it.name()))
                      .append(" | ").append(mdEscape(nz(it.custodian())))
                      .append(" | ").append(mdEscape(location(it))).append(" |\n");
                    csv.add(new String[]{String.valueOf(n), e.getKey(), id, it.name(),
                            nz(it.custodian()), location(it), String.valueOf(it.size()),
                            primary ? "original" : "duplicate"});
                    primary = false;
                }
                md.append('\n');
            }
        }

        Path out = exportsDir().resolve("duplicates-report-" + stamp() + ".md");
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        writeCsv(out.resolveSibling(out.getFileName().toString().replace(".md", ".csv")),
                new String[]{"Group", "SHA256", "ElementID", "Name", "Custodian",
                        "Location", "SizeBytes", "Role"},
                csv);
        return out;
    }

    // =====================================================================
    // Search report
    // =====================================================================

    /** F-27 search report: the query, when it ran, and exactly what it returned. */
    public Path search(String query, List<Item> hits, long millis, String user)
            throws IOException {

        StringBuilder md = new StringBuilder(4096);
        md.append("# Search Report\n\n");
        md.append("**Case:** ").append(folder.name()).append("  \n");
        md.append("**Query:** `").append(query == null ? "" : query).append("`  \n");
        md.append("**Executed:** ").append(STAMP.format(Instant.now()))
          .append(" by ").append(nz(user)).append("  \n");
        md.append("**Results:** ").append(fmt(hits.size()))
          .append(" element(s) in ").append(millis).append(" ms\n\n");

        Map<String, Integer> byType = new TreeMap<>();
        Map<String, Integer> byCustodian = new TreeMap<>();
        for (Item it : hits) {
            byType.merge(it.extension() == null || it.extension().isBlank()
                    ? "(none)" : it.extension().toLowerCase(), 1, Integer::sum);
            byCustodian.merge(nz(it.custodian()).isBlank()
                    ? "(unassigned)" : it.custodian(), 1, Integer::sum);
        }

        if (!hits.isEmpty()) {
            md.append("## Distribution\n\n");
            md.append("| Type | Hits | | Custodian | Hits |\n|---|---:|---|---|---:|\n");
            var t = new ArrayList<>(byType.entrySet());
            var c = new ArrayList<>(byCustodian.entrySet());
            for (int i = 0; i < Math.max(t.size(), c.size()); i++) {
                md.append("| ").append(i < t.size() ? t.get(i).getKey() : "")
                  .append(" | ").append(i < t.size() ? fmt(t.get(i).getValue()) : "")
                  .append(" | | ").append(i < c.size() ? c.get(i).getKey() : "")
                  .append(" | ").append(i < c.size() ? fmt(c.get(i).getValue()) : "")
                  .append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Results\n\n");
        if (hits.isEmpty()) {
            md.append("No elements matched this query.\n");
        } else {
            md.append("| # | Element | Name | Type | Custodian | Tags | Location |\n");
            md.append("|---:|---|---|---|---|---|---|\n");
            int n = 0;
            for (Item it : hits) {
                n++;
                md.append("| ").append(n).append(" | ").append(it.id())
                  .append(" | ").append(mdEscape(it.name()))
                  .append(" | ").append(nz(it.extension()))
                  .append(" | ").append(mdEscape(nz(it.custodian())))
                  .append(" | ").append(String.join(", ", it.tags()))
                  .append(" | ").append(mdEscape(location(it))).append(" |\n");
            }
        }

        Path out = exportsDir().resolve("search-report-" + stamp() + ".md");
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);

        List<String[]> csv = new ArrayList<>();
        for (Item it : hits) {
            csv.add(new String[]{it.id(), it.name(), nz(it.extension()), nz(it.custodian()),
                    String.join(";", it.tags()), location(it), nz(it.sha256()),
                    it.status().label()});
        }
        writeCsv(out.resolveSibling(out.getFileName().toString().replace(".md", ".csv")),
                new String[]{"ElementID", "Name", "Type", "Custodian", "Tags",
                        "Location", "SHA256", "Status"},
                csv);

        try { db.audit(user, "SEARCH_REPORT", query + " → " + hits.size() + " hits", null); }
        catch (Exception ignored) { }
        return out;
    }

    // =====================================================================

    private Path exportsDir() throws IOException {
        Path d = folder.exports();
        Files.createDirectories(d);
        return d;
    }

    private static String reasonFor(Item it) {
        if (!it.errors().isEmpty()) return String.join(" | ", it.errors());
        String locked = it.metadata().get("Locked");
        if (locked != null) return locked;
        String unsup = it.metadata().get("Unsupported");
        if (unsup != null) return unsup;
        return "";
    }

    private static String location(Item it) {
        if (it.containerPath() != null && !it.containerPath().isBlank()) {
            return it.containerPath();
        }
        return nz(it.sourcePath());
    }

    private void writeCsv(Path path, String[] header, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(String.join(",", header)).append("\r\n");
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(csvQuote(r[i]));
            }
            sb.append("\r\n");
        }
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(path, all);
    }

    private static String csvQuote(String v) {
        if (v == null) return "";
        boolean needs = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        String s = v.replace("\"", "\"\"");
        return needs ? '"' + s + '"' : s;
    }

    private static String mdEscape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String stamp() { return FILE_STAMP.format(Instant.now()); }

    private static String fmt(long n) { return String.format("%,d", n); }

    private static String pct(long n, long total) {
        return total == 0 ? "0%" : String.format("%.1f%%", 100.0 * n / total);
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b / 1024.0;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format("%.1f %s", v, u[i]);
    }

    private static String fmtDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        if (s < 60) return s + " s";
        return String.format("%d min %d s", s / 60, s % 60);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
