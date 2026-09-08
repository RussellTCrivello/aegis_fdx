package com.aegis.fdx.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * F-29 per-case settings. Defaults follow the decision table in the brief.
 *
 * <p>Settings belong to the case, not to the session, so they are written next to it
 * as a plain properties file and read back when the case is reopened. Nothing in this
 * class silently discards a value: an unreadable or partially corrupt file leaves the
 * current values in place rather than resetting the case to defaults, and passwords are
 * deliberately never written to disk.
 */
public final class CaseSettings {

    public enum DedupeScope { OFF, PER_CUSTODIAN, GLOBAL }

    private boolean ocrEnabled = true;              // enabled by default
    private List<String> ocrLanguages = new ArrayList<>(List.of("eng"));
    private DedupeScope dedupeScope = DedupeScope.OFF;   // highlight only
    private int maxArchiveDepth = 20;               // F-03
    private final List<String> passwords = new ArrayList<>();  // F-04
    private int indexMemoryMb = 4096;               // N-04
    private int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1); // A-03
    private boolean encryptCaseFolder = true;       // N-06 AES-256
    private long streamThresholdMb = 100;           // N-04

    public boolean ocrEnabled() { return ocrEnabled; }
    public void ocrEnabled(boolean v) { ocrEnabled = v; }
    public List<String> ocrLanguages() { return ocrLanguages; }
    public DedupeScope dedupeScope() { return dedupeScope; }
    public void dedupeScope(DedupeScope v) { dedupeScope = v; }
    public int maxArchiveDepth() { return maxArchiveDepth; }
    public void maxArchiveDepth(int v) { maxArchiveDepth = v; }
    public List<String> passwords() { return passwords; }
    public int indexMemoryMb() { return indexMemoryMb; }
    public void indexMemoryMb(int v) { indexMemoryMb = v; }
    public int workers() { return workers; }
    public void workers(int v) { workers = v; }
    public boolean encryptCaseFolder() { return encryptCaseFolder; }
    public void encryptCaseFolder(boolean v) { encryptCaseFolder = v; }
    public long streamThresholdMb() { return streamThresholdMb; }

    // ---- persistence -------------------------------------------------------

    /** File name used inside a case folder. */
    public static final String FILE_NAME = "settings.properties";

    /**
     * Reads settings from {@code file} into this instance.
     *
     * <p>A missing file is not an error: a case that has never had its settings changed
     * keeps the defaults. A malformed value is skipped, keeping the current value, so a
     * hand-edited file cannot make a case unopenable.
     *
     * @return true if a file was found and read
     */
    public boolean loadFrom(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return false;
        }
        ocrEnabled = bool(p, "ocr.enabled", ocrEnabled);
        String langs = p.getProperty("ocr.languages");
        if (langs != null && !langs.isBlank()) {
            List<String> parsed = new ArrayList<>();
            for (String s : langs.split(",")) {
                if (!s.isBlank()) {
                    parsed.add(s.trim());
                }
            }
            if (!parsed.isEmpty()) {
                ocrLanguages = parsed;
            }
        }
        String scope = p.getProperty("dedupe.scope");
        if (scope != null) {
            try {
                dedupeScope = DedupeScope.valueOf(scope.trim());
            } catch (IllegalArgumentException ignored) {
                // an unknown scope keeps the current one
            }
        }
        maxArchiveDepth = intInRange(p, "archive.maxDepth", maxArchiveDepth, 1, 50);
        indexMemoryMb = intInRange(p, "index.memoryMb", indexMemoryMb, 64, 65536);
        workers = intInRange(p, "processing.workers", workers, 1, 256);
        encryptCaseFolder = bool(p, "case.encrypt", encryptCaseFolder);
        return true;
    }

    /**
     * Writes settings to {@code file}, creating the parent directory if needed.
     *
     * <p>Passwords supplied for encrypted containers are held for the session only and
     * are never written.
     */
    public void saveTo(Path file) throws IOException {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("ocr.enabled", Boolean.toString(ocrEnabled));
        p.setProperty("ocr.languages", String.join(",", ocrLanguages));
        p.setProperty("dedupe.scope", dedupeScope.name());
        p.setProperty("archive.maxDepth", Integer.toString(maxArchiveDepth));
        p.setProperty("index.memoryMb", Integer.toString(indexMemoryMb));
        p.setProperty("processing.workers", Integer.toString(workers));
        p.setProperty("case.encrypt", Boolean.toString(encryptCaseFolder));
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "AEGIS-FDX case settings");
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        String s = v.trim();
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private static int intInRange(Properties p, String key, int fallback, int min, int max) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(v.trim());
            return parsed < min || parsed > max ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
