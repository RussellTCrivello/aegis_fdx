package com.aegis.fdx.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * F-28: the self-contained case folder.
 *
 * <pre>
 * &lt;case&gt;/
 *   data/     extracted binaries
 *   index/    Lucene
 *   text/     extracted text, one file per element
 *   db/       SQLite (WAL)
 *   logs/     Logback
 *   exports/  productions
 *   case.json settings
 * </pre>
 *
 * Everything the case needs lives here; moving or archiving it is a folder copy.
 */
public final class CaseFolder {

    private final Path root;
    private final String name;

    private CaseFolder(Path root, String name) {
        this.root = root;
        this.name = name;
    }

    public static CaseFolder createOrOpen(Path root, String name) throws IOException {
        CaseFolder c = new CaseFolder(root.toAbsolutePath().normalize(), name);
        for (Path p : new Path[]{c.data(), c.index(), c.text(), c.db(), c.logs(), c.exports()}) {
            Files.createDirectories(p);
        }
        if (!Files.exists(c.caseJson())) {
            c.writeCaseJson(name);
        }
        return c;
    }

    public Path root() { return root; }
    public String name() { return name; }
    public Path data() { return root.resolve("data"); }
    public Path index() { return root.resolve("index"); }
    public Path text() { return root.resolve("text"); }
    public Path db() { return root.resolve("db"); }
    public Path logs() { return root.resolve("logs"); }
    public Path exports() { return root.resolve("exports"); }
    public Path caseJson() { return root.resolve("case.json"); }
    public Path database() { return db().resolve("case.db"); }

    /** Text is sharded two levels deep so a million files stay filesystem-friendly. */
    public Path textFileFor(String itemId) {
        String safe = itemId.replaceAll("[^A-Za-z0-9._-]", "_");
        String a = shard(safe, 0);
        String b = shard(safe, 1);
        return text().resolve(a).resolve(b).resolve(safe + ".txt");
    }

    public Path dataFileFor(String itemId, String fileName) {
        String safe = itemId.replaceAll("[^A-Za-z0-9._-]", "_");
        return data().resolve(shard(safe, 0)).resolve(shard(safe, 1)).resolve(safe + "_" + sanitize(fileName));
    }

    public void writeText(String itemId, String content) throws IOException {
        Path p = textFileFor(itemId);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    public String readText(String itemId) throws IOException {
        Path p = textFileFor(itemId);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    private void writeCaseJson(String name) throws IOException {
        String json = """
                {
                  "name": "%s",
                  "created": "%s",
                  "schema": 2,
                  "layout": ["data", "index", "text", "db", "logs", "exports"]
                }
                """.formatted(escape(name), Instant.now());
        Files.writeString(caseJson(), json, StandardCharsets.UTF_8);
    }

    private static String shard(String s, int level) {
        int hash = Math.abs(s.hashCode());
        int bucket = (hash >> (level * 8)) & 0xFF;
        return String.format("%02x", bucket);
    }

    private static String sanitize(String s) {
        String t = s == null ? "bin" : s.replaceAll("[^A-Za-z0-9._-]", "_");
        return t.length() > 64 ? t.substring(t.length() - 64) : t;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
