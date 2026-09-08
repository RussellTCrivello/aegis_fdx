package com.aegis.fdx.engine;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * M3-D. Forensic integrity controls.
 *
 * <p>Answers the question a court asks: <em>can you show the evidence you indexed
 * is the evidence you were given, and that you did not alter it?</em>
 *
 * <ul>
 *   <li>{@link #verifySources} re-hashes every original file and compares against
 *       the value recorded at ingest.</li>
 *   <li>{@link #verifyReadOnly} proves the source tree was never written to, by
 *       comparing last-modified times captured at ingest.</li>
 *   <li>{@link #verifyTextStore} confirms the extracted text backing the index is
 *       still present, which is what makes F-14 rebuilds possible.</li>
 * </ul>
 *
 * <p>Everything here is read-only. The verifier never repairs anything: silently
 * "fixing" evidence would destroy the very provenance it exists to demonstrate.
 */
public final class IntegrityVerifier {

    private final CaseFolder folder;
    private final CaseDatabase db;
    private final Consumer<String> log;

    public IntegrityVerifier(CaseFolder folder, CaseDatabase db, Consumer<String> log) {
        this.folder = folder;
        this.db = db;
        this.log = log == null ? m -> { } : log;
    }

    /** One element's verdict. */
    public record Finding(String itemId, String name, Kind kind, String detail) {
        public enum Kind {
            /** Hash matches the value recorded at ingest. */
            VERIFIED,
            /** Hash differs — the source changed after ingest. */
            MODIFIED,
            /** Source file no longer exists. */
            MISSING,
            /** Element has no stored hash to compare against. */
            NO_BASELINE,
            /** Element lives inside a container; verified via its parent. */
            EMBEDDED,
            /** Extracted text backing the index is gone. */
            TEXT_MISSING,
            /** Could not be read. */
            UNREADABLE
        }
        public boolean ok() {
            return kind == Kind.VERIFIED || kind == Kind.EMBEDDED;
        }
    }

    /** Aggregate verdict for a verification run. */
    public record Report(List<Finding> findings, int verified, int modified, int missing,
                         int embedded, int noBaseline, int unreadable, int textMissing,
                         long bytesHashed, long millis) {

        /** True only when nothing was modified, missing, or unreadable. */
        public boolean clean() {
            return modified == 0 && missing == 0 && unreadable == 0 && textMissing == 0;
        }

        /** Findings a reviewer must look at. */
        public List<Finding> problems() {
            return findings.stream().filter(f -> !f.ok()).toList();
        }

        @Override public String toString() {
            return verified + " verified, " + embedded + " embedded, " + modified
                    + " MODIFIED, " + missing + " missing, " + unreadable + " unreadable, "
                    + textMissing + " text missing";
        }
    }

    /**
     * Re-hashes every element's original file and compares to the ingest baseline.
     *
     * @param items every element in the case
     */
    public Report verifySources(List<Item> items) {
        long t0 = System.currentTimeMillis();
        List<Finding> findings = new ArrayList<>();
        int verified = 0, modified = 0, missing = 0, embedded = 0;
        int noBaseline = 0, unreadable = 0, textMissing = 0;
        long bytes = 0;

        for (Item it : items) {
            // Container members have no standalone file; their integrity follows
            // from the parent archive's hash.
            if (it.depth() > 0) {
                embedded++;
                findings.add(new Finding(it.id(), it.name(), Finding.Kind.EMBEDDED,
                        "verified through parent container"));
                continue;
            }
            if (it.sha256() == null || it.sha256().isBlank()) {
                noBaseline++;
                findings.add(new Finding(it.id(), it.name(), Finding.Kind.NO_BASELINE,
                        "no hash recorded at ingest"));
                continue;
            }
            Path src;
            try {
                src = it.sourcePath() == null ? null : Path.of(it.sourcePath());
            } catch (Exception e) {
                src = null;
            }
            if (src == null || !Files.isRegularFile(src)) {
                missing++;
                findings.add(new Finding(it.id(), it.name(), Finding.Kind.MISSING,
                        "source no longer at " + it.sourcePath()));
                continue;
            }
            try {
                long size = Files.size(src);
                String actual = sha256(src);
                bytes += size;
                if (actual.equalsIgnoreCase(it.sha256())) {
                    verified++;
                    findings.add(new Finding(it.id(), it.name(), Finding.Kind.VERIFIED,
                            it.sha256()));
                } else {
                    modified++;
                    findings.add(new Finding(it.id(), it.name(), Finding.Kind.MODIFIED,
                            "expected " + it.sha256() + " but found " + actual));
                    log.accept("INTEGRITY FAILURE: " + it.name()
                            + " has changed since ingest");
                }
            } catch (Exception e) {
                unreadable++;
                findings.add(new Finding(it.id(), it.name(), Finding.Kind.UNREADABLE,
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        // F-14: the index can only be rebuilt while the stored text survives.
        for (Item it : items) {
            if (it.extractedText() == null || it.extractedText().isBlank()) continue;
            Path tf = folder.textFileFor(it.id());
            if (!Files.exists(tf)) {
                textMissing++;
                findings.add(new Finding(it.id(), it.name(), Finding.Kind.TEXT_MISSING,
                        "extracted text absent from " + tf));
            }
        }

        Report r = new Report(findings, verified, modified, missing, embedded,
                noBaseline, unreadable, textMissing, bytes,
                System.currentTimeMillis() - t0);

        try {
            db.audit("system", "INTEGRITY_VERIFY", r.toString(), null);
        } catch (Exception ignored) { }
        log.accept("Integrity verification: " + r);
        return r;
    }

    /**
     * F-06. Confirms the source tree was not written to during processing, by
     * comparing each file's current last-modified time with the value recorded at
     * ingest. A changed timestamp on read-only evidence is a serious finding.
     */
    public List<Finding> verifyReadOnly(List<Item> items) {
        List<Finding> out = new ArrayList<>();
        for (Item it : items) {
            // Compare against the intake baseline, never modified(): analyzers
            // overwrite that with the document's own internal date.
            if (it.depth() > 0 || it.fsModified() == null || it.sourcePath() == null) continue;
            try {
                Path p = Path.of(it.sourcePath());
                if (!Files.isRegularFile(p)) continue;
                long now = Files.getLastModifiedTime(p).toMillis();
                long then = it.fsModified().toEpochMilli();
                // Filesystem timestamp granularity varies; a 2s window avoids
                // false positives on FAT/network volumes.
                if (Math.abs(now - then) > 2000) {
                    out.add(new Finding(it.id(), it.name(), Finding.Kind.MODIFIED,
                            "last-modified changed since ingest"));
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /**
     * Best-effort OS-level write protection on the source tree before a run.
     * Advisory only — the real guarantee is that the pipeline never opens a source
     * for writing.
     *
     * @return true when protection was applied
     */
    public static boolean protectSource(Path root) {
        try {
            if (!Files.exists(root)) return false;
            var view = Files.getFileAttributeView(root,
                    java.nio.file.attribute.PosixFileAttributeView.class);
            if (view == null) return false;      // Windows: handled by opening read-only
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        Set<PosixFilePermission> perms =
                                new java.util.HashSet<>(Files.getPosixFilePermissions(p));
                        perms.remove(PosixFilePermission.OWNER_WRITE);
                        perms.remove(PosixFilePermission.GROUP_WRITE);
                        perms.remove(PosixFilePermission.OTHERS_WRITE);
                        Files.setPosixFilePermissions(p, perms);
                    } catch (Exception ignored) { }
                });
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
