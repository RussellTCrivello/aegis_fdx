package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** F-02: plain-text family — TXT, CSV, HTML, XML, JSON, RTF, log. */
public final class TextAnalyzer implements Analyzer {

    private static final long MAX_TEXT = 32L * 1024 * 1024;   // N-04 guard

    @Override public String id() { return "text"; }

    @Override
    public List<String> mediaTypes() {
        return List.of("text/plain", "text/csv", "text/html", "application/xml",
                "application/json", "application/rtf");
    }

    @Override
    public double sniff(byte[] h, String fileName) {
        if (Magic.extIn(fileName, "txt", "csv", "tsv", "log", "md", "json", "xml", "html", "htm", "rtf")) {
            return 0.85;
        }
        if (h.length == 0) return 0;
        // Heuristic: mostly printable ASCII/UTF-8 in the header means text.
        int printable = 0, checked = Math.min(h.length, 1024);
        for (int i = 0; i < checked; i++) {
            int b = h[i] & 0xFF;
            if (b == 0) return 0;                       // NUL ⇒ binary
            if (b >= 0x20 || b == '\n' || b == '\r' || b == '\t') printable++;
        }
        return printable > checked * 0.92 ? 0.35 : 0;
    }

    @Override public int priority() { return -10; }      // yield to typed analyzers

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        byte[] data = in.readNBytes((int) Math.min(MAX_TEXT, Integer.MAX_VALUE));
        String text = new String(data, StandardCharsets.UTF_8);

        String ext = Magic.ext(item.name());
        item.mediaType(switch (ext) {
            case "csv" -> "text/csv";
            case "html", "htm" -> "text/html";
            case "xml" -> "application/xml";
            case "json" -> "application/json";
            case "rtf" -> "application/rtf";
            default -> "text/plain";
        });

        if ("html".equals(ext) || "htm".equals(ext)) {
            text = stripTags(text);
        } else if ("rtf".equals(ext)) {
            text = stripRtf(text);
        }

        item.extractedText(text.strip());
        item.addMetadata("Characters", String.valueOf(item.extractedText().length()));
        item.addMetadata("Lines", String.valueOf(item.extractedText().lines().count()));
    }

    static String stripTags(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                   .replaceAll("(?s)<[^>]+>", " ")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("&quot;", "\"")
                   .replaceAll("[ \\t]{2,}", " ");
    }

    static String stripRtf(String rtf) {
        return rtf.replaceAll("\\{\\\\\\*?[^{}]*\\}", " ")
                  .replaceAll("\\\\[a-zA-Z]+-?\\d* ?", " ")
                  .replaceAll("[{}]", " ")
                  .replaceAll("[ \\t]{2,}", " ");
    }
}
