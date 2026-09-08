package com.aegis.fdx.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * M3-A. Tesseract OCR driver.
 *
 * <p>Deliberately drives the {@code tesseract} <em>executable</em> rather than
 * binding libtesseract through JNA. Reasons, in order of weight:
 *
 * <ol>
 *   <li><b>Fault isolation.</b> Tesseract on a malformed or hostile image can
 *       segfault or run away. In-process that kills the JVM and takes the whole
 *       evidence run with it, violating N-05. Out-of-process it is a non-zero
 *       exit code we record against one element.</li>
 *   <li><b>Timeouts are enforceable.</b> A native call cannot be interrupted; a
 *       child process can be destroyed. Prevents one pathological scan from
 *       stalling ingestion.</li>
 *   <li><b>Licensing.</b> Tesseract is Apache-2.0 but ships as a native binary;
 *       invoking it keeps it a redistributable external tool rather than linked
 *       code, which keeps the dependency report clean.</li>
 * </ol>
 *
 * <p>The cost is process-spawn overhead per image (~10-30 ms), negligible next to
 * the 0.5-3 s an actual OCR pass takes.
 *
 * <p>Thread-safe and stateless; a single instance is shared by all OCR workers.
 */
public final class OcrEngine {

    /** Hard ceiling on a single OCR invocation. */
    private static final long TIMEOUT_SECONDS = 120;

    private final Path binary;
    private final Path tessdata;
    private final boolean available;
    private final String version;
    private final List<String> installedLanguages;

    public OcrEngine() {
        this(null, null);
    }

    /**
     * @param binaryOverride  explicit path to the tesseract executable, or null to
     *                        probe {@code aegis.tesseract}, then {@code PATH}
     * @param tessdataOverride explicit tessdata dir, or null to use the environment
     */
    public OcrEngine(Path binaryOverride, Path tessdataOverride) {
        Path bin = binaryOverride != null ? binaryOverride : probeBinary();
        Path data = tessdataOverride != null ? tessdataOverride : probeTessdata();

        String v = null;
        List<String> langs = List.of();
        boolean ok = false;
        if (bin != null && Files.isExecutable(bin)) {
            Exec probe = exec(bin, data, List.of("--version"), null);
            // Must exit cleanly AND report a version. A binary that fails to load its
            // shared libraries still prints the word "tesseract" in its error text, so
            // substring matching alone would falsely report OCR as available and every
            // document would silently come back with no text.
            if (probe != null && probe.exitCode == 0) {
                String line = firstLine(probe);
                if (line != null && line.toLowerCase().matches("^tesseract\\s+v?\\d.*")) {
                    v = line.trim();
                    ok = true;
                    langs = parseLanguages(exec(bin, data, List.of("--list-langs"), null));
                }
            }
            if (!ok && probe != null) {
                String why = (probe.stderr + probe.stdout).strip();
                v = why.isEmpty() ? null : "unusable: " + firstLine(probe).trim();
            }
        }
        this.binary = bin;
        this.tessdata = data;
        this.available = ok;
        this.version = v;
        this.installedLanguages = langs;
    }

    /** False when no usable Tesseract is installed; callers must degrade, not fail. */
    public boolean available() { return available; }

    /** e.g. {@code "tesseract 5.5.0"}, or null when unavailable. */
    public String version() { return version; }

    /** Language codes Tesseract can actually load right now. */
    public List<String> installedLanguages() { return installedLanguages; }

    public Path binaryPath() { return binary; }

    /**
     * Runs OCR over an in-memory image.
     *
     * @param imageBytes raw image (PNG/JPEG/TIFF/BMP...)
     * @param languages  requested language codes; unavailable ones are dropped and
     *                   the call still proceeds with whatever remains
     * @return recognised text, never null — empty means "nothing legible"
     */
    public Result recognise(byte[] imageBytes, List<String> languages) throws IOException {
        if (!available) return Result.unavailable();
        if (imageBytes == null || imageBytes.length == 0) return Result.empty();

        Path tmp = Files.createTempFile("aegis-ocr-", ".img");
        try {
            Files.write(tmp, imageBytes);
            return recognise(tmp, languages);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** Runs OCR over an image already on disk. */
    public Result recognise(Path image, List<String> languages) {
        if (!available) return Result.unavailable();

        List<String> usable = resolveLanguages(languages);
        long t0 = System.currentTimeMillis();

        List<String> args = new ArrayList<>();
        args.add(image.toAbsolutePath().toString());
        args.add("stdout");
        if (!usable.isEmpty()) {
            args.add("-l");
            args.add(String.join("+", usable));
        }
        // 3 = fully automatic page segmentation. Correct default for documents.
        args.add("--psm");
        args.add("3");

        Exec r = exec(binary, tessdata, args, null);
        long ms = System.currentTimeMillis() - t0;

        if (r == null) {
            return new Result("", usable, ms, false, "OCR process could not be started");
        }
        if (r.timedOut) {
            return new Result("", usable, ms, false,
                    "OCR timed out after " + TIMEOUT_SECONDS + "s");
        }
        if (r.exitCode != 0) {
            return new Result("", usable, ms, false,
                    "tesseract exit " + r.exitCode + ": " + firstLine(r).trim());
        }
        return new Result(r.stdout.strip(), usable, ms, true, null);
    }

    /** Requested languages filtered to those actually installed. */
    private List<String> resolveLanguages(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return installedLanguages.contains("eng") ? List.of("eng") : List.of();
        }
        List<String> out = new ArrayList<>();
        for (String l : requested) {
            if (l == null) continue;
            String code = l.trim().toLowerCase();
            if (installedLanguages.isEmpty() || installedLanguages.contains(code)) out.add(code);
        }
        if (out.isEmpty() && installedLanguages.contains("eng")) out.add("eng");
        return out;
    }

    // =====================================================================
    // Process plumbing
    // =====================================================================

    private static Exec exec(Path bin, Path tessdata, List<String> args, byte[] stdin) {
        if (bin == null) return null;
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.toAbsolutePath().toString());
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            if (tessdata != null) {
                pb.environment().put("TESSDATA_PREFIX", tessdata.toAbsolutePath().toString());
            }
            // Bundled shared libraries must be resolvable when we ship a private copy
            // of Tesseract (jpackage, or an unpacked distro tree). Probe the layouts we
            // actually ship rather than assuming one.
            List<String> libDirs = new ArrayList<>();
            String explicit = System.getProperty("aegis.ocrLibPath");
            if (explicit != null && !explicit.isBlank()) libDirs.add(explicit);
            Path parent = bin.getParent();
            if (parent != null) {
                for (String rel : new String[]{
                        "../lib", "../lib/x86_64-linux-gnu", "../../lib/x86_64-linux-gnu",
                        "../lib64", "lib"}) {
                    Path cand = parent.resolve(rel).normalize();
                    if (Files.isDirectory(cand)) libDirs.add(cand.toAbsolutePath().toString());
                }
            }
            String inherited = System.getenv("LD_LIBRARY_PATH");
            if (inherited != null && !inherited.isBlank()) libDirs.add(inherited);
            if (!libDirs.isEmpty()) {
                pb.environment().put("LD_LIBRARY_PATH",
                        String.join(java.io.File.pathSeparator, libDirs));
            }
            Process p = pb.start();
            if (stdin != null) {
                try (var os = p.getOutputStream()) { os.write(stdin); }
            } else {
                p.getOutputStream().close();
            }
            // Drain both pipes concurrently or a full stderr buffer deadlocks the child.
            var outBuf = new StringBuilder();
            var errBuf = new StringBuilder();
            Thread to = drain(p.getInputStream(), outBuf);
            Thread te = drain(p.getErrorStream(), errBuf);

            boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                to.join(1000);
                te.join(1000);
                return new Exec("", errBuf.toString(), -1, true);
            }
            to.join(5000);
            te.join(5000);
            return new Exec(outBuf.toString(), errBuf.toString(), p.exitValue(), false);
        } catch (Exception e) {
            return null;
        }
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) {
                    synchronized (sink) { sink.append(buf, 0, n); }
                }
            } catch (IOException ignored) { }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Path probeBinary() {
        String prop = System.getProperty("aegis.tesseract");
        if (prop != null && !prop.isBlank()) {
            Path p = Paths.get(prop);
            if (Files.isExecutable(p)) return p;
        }
        String env = System.getenv("AEGIS_TESSERACT");
        if (env != null && !env.isBlank()) {
            Path p = Paths.get(env);
            if (Files.isExecutable(p)) return p;
        }
        // Alongside the installed application (jpackage layout).
        for (String rel : new String[]{"runtime/ocr/bin/tesseract", "ocr/bin/tesseract"}) {
            Path p = Paths.get(rel);
            if (Files.isExecutable(p)) return p.toAbsolutePath();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            String exe = win ? "tesseract.exe" : "tesseract";
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path p = Paths.get(dir, exe);
                if (Files.isExecutable(p)) return p;
            }
        }
        return null;
    }

    private static Path probeTessdata() {
        String prop = System.getProperty("aegis.tessdata");
        if (prop != null && !prop.isBlank()) return Paths.get(prop);
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && !env.isBlank()) return Paths.get(env);
        return null;
    }

    private static List<String> parseLanguages(Exec r) {
        if (r == null) return List.of();
        List<String> out = new ArrayList<>();
        // Tesseract prints the header on stdout or stderr depending on build.
        for (String stream : new String[]{r.stdout, r.stderr}) {
            for (String line : stream.split("\\R")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("List of") || s.contains(" ")) continue;
                if (s.matches("[A-Za-z_]{2,}")) out.add(s.toLowerCase());
            }
        }
        Collections.sort(out);
        return List.copyOf(new java.util.LinkedHashSet<>(out));
    }

    private static String firstLine(Exec r) {
        if (r == null) return null;
        String s = r.stdout.isBlank() ? r.stderr : r.stdout;
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private record Exec(String stdout, String stderr, int exitCode, boolean timedOut) { }

    /** Outcome of one OCR attempt. Failure is data, not an exception. */
    public record Result(String text, List<String> languages, long millis,
                         boolean success, String error) {

        static Result unavailable() {
            return new Result("", List.of(), 0, false, "no Tesseract installation found");
        }

        static Result empty() {
            return new Result("", List.of(), 0, true, null);
        }

        public boolean hasText() { return text != null && !text.isBlank(); }

        /** Rough confidence proxy: share of characters that are plausible text. */
        public double legibility() {
            if (text == null || text.isEmpty()) return 0;
            long good = text.chars().filter(c -> Character.isLetterOrDigit(c)
                    || Character.isWhitespace(c) || ".,;:!?-'\"()".indexOf(c) >= 0).count();
            return (double) good / text.length();
        }
    }
}
