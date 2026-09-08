package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.ProcessingResultDto;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads externally supplied data back in.
 *
 * <p>Backup import is validation-only by design: it reports what an archive contains
 * without mutating the case, because restoring over a live case would require schema
 * surgery that is not safe to perform implicitly.
 */
public final class ImportFacade {

    private final FileProcessingFacade processing;

    /** Backup/settings import need no engine; pass null when only those are used. */
    public ImportFacade() {
        this(null);
    }

    public ImportFacade(FileProcessingFacade processing) {
        this.processing = processing;
    }

    /**
     * Validates a backup archive and reports its contents.
     *
     * <p>Requesting an actual restore raises {@link FacadeException.Kind#UNSUPPORTED}
     * rather than silently doing nothing.
     */
    public BackupValidation importDatabaseBackup(byte[] backupFile) {
        return importDatabaseBackup(backupFile, false);
    }

    public BackupValidation importDatabaseBackup(byte[] backupFile, boolean restoreData) {
        if (backupFile == null || backupFile.length == 0) {
            throw FacadeException.validation("backup_file is required");
        }
        if (restoreData) {
            throw FacadeException.unsupported(
                    "restore_data is not supported: backup import is validate-only");
        }
        List<String> entries = new ArrayList<>();
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backupFile))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zip.getNextEntry()) != null) {
                entries.add(e.getName());
                int n;
                while ((n = zip.read(buf)) > 0) {
                    totalBytes += n;
                }
                zip.closeEntry();
            }
        } catch (Exception e) {
            return new BackupValidation(false, List.of(), 0L,
                    "not a readable ZIP archive: " + e.getMessage());
        }
        if (entries.isEmpty()) {
            return new BackupValidation(false, entries, 0L, "archive contains no entries");
        }
        return new BackupValidation(true, entries, totalBytes, null);
    }

    /** Reads a flat JSON settings object. */
    public Map<String, String> importSettings(byte[] settingsFile) {
        if (settingsFile == null || settingsFile.length == 0) {
            throw FacadeException.validation("settings_file is required");
        }
        String json = new String(settingsFile, StandardCharsets.UTF_8).trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw FacadeException.validation("settings_file must contain a JSON object");
        }
        // Flat key/value parse: the settings document is a single-level object.
        Map<String, String> out = new LinkedHashMap<>();
        String body = json.substring(1, json.length() - 1).trim();
        if (body.isEmpty()) {
            return out;
        }
        for (String pair : splitTopLevel(body)) {
            int colon = indexOfUnquoted(pair, ':');
            if (colon < 0) {
                throw FacadeException.validation("malformed settings entry: " + pair.trim());
            }
            String k = unquote(pair.substring(0, colon).trim());
            String v = unquote(pair.substring(colon + 1).trim());
            out.put(k, v);
        }
        return out;
    }

    /**
     * Processes every path listed in a CSV.
     *
     * <p>The CSV must carry a {@code file_path} or {@code path} column. Each listed
     * path is run through the pipeline, so this needs a {@link FileProcessingFacade}.
     */
    public List<ProcessingResultDto> importFileListFromCsv(byte[] csvFile,
                                                           int sourceId,
                                                           int aspectId) {
        if (csvFile == null || csvFile.length == 0) {
            throw FacadeException.validation("csv_file is required");
        }
        Validate.positiveId(sourceId, "sourceId");
        Validate.positiveId(aspectId, "aspectId");
        if (processing == null) {
            throw FacadeException.unsupported(
                    "importFileListFromCsv requires a FileProcessingFacade; construct "
                            + "ImportFacade with the one-argument constructor");
        }

        List<String> paths = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(csvFile), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null) {
                throw FacadeException.validation("csv_file is empty");
            }
            if (!header.isEmpty() && header.charAt(0) == '\ufeff') {
                header = header.substring(1);
            }
            String[] cols = header.split(",", -1);
            int idx = -1;
            for (int i = 0; i < cols.length; i++) {
                String c = cols[i].trim().toLowerCase().replace("\"", "");
                if ("file_path".equals(c) || "path".equals(c)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw FacadeException.validation(
                        "csv_file must contain a 'file_path' or 'path' column");
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(",", -1);
                if (idx < cells.length) {
                    String p = cells[idx].trim().replaceAll("^\"|\"$", "");
                    if (!p.isBlank()) {
                        paths.add(p);
                    }
                }
            }
        } catch (FacadeException e) {
            throw e;
        } catch (Exception e) {
            throw FacadeException.internal("failed to read CSV", e);
        }

        List<ProcessingResultDto> out = new ArrayList<>(paths.size());
        for (String p : paths) {
            out.add(processing.processSingleFile(p));
        }
        return out;
    }

    /** What a backup archive was found to contain. */
    public record BackupValidation(boolean valid, List<String> entries,
                                   long uncompressedBytes, String error) {
        public BackupValidation {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    // ---- minimal JSON helpers -------------------------------------------

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
                continue;
            }
            if (c == '\\' && inStr) {
                cur.append(c);
                esc = true;
                continue;
            }
            if (c == '"') {
                inStr = !inStr;
            }
            if (!inStr && (c == '{' || c == '[')) {
                depth++;
            }
            if (!inStr && (c == '}' || c == ']')) {
                depth--;
            }
            if (!inStr && depth == 0 && c == ',') {
                parts.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private static int indexOfUnquoted(String s, char target) {
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (c == '\\' && inStr) {
                esc = true;
                continue;
            }
            if (c == '"') {
                inStr = !inStr;
            } else if (!inStr && c == target) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        }
        return t;
    }
}
