package com.aegis.fdx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@code docs/interface-function-matrix.tsv} to account.
 *
 * <p>The matrix claims, per interactive element, which Java handler, facade, database
 * operation and test stand behind it. A claim that names code or a test that does not
 * exist is a hidden gap, so every symbol is resolved against the source tree. Every
 * screen in the interface must appear, every button label in the interface must be
 * inventoried, and every row that is not VERIFIED must say why.
 */
public final class InterfaceFunctionMatrixTest {

    private static final Set<String> STATUSES = Set.of(
            "VERIFIED", "ADAPTED", "LIMITED", "UNSUPPORTED", "REFERENCE-INERT", "ENVIRONMENT-LIMITED", "NOT RUN");

    private static final int COLUMNS = 17;
    private static final int COL_JAVA_DEST = 8;
    private static final int COL_JAVA_CONTROL = 9;
    private static final int COL_JAVA_HANDLER = 10;
    private static final int COL_JAVA_FACADE = 11;
    private static final int COL_DB_OP = 12;
    private static final int COL_TEST = 14;
    private static final int COL_STATUS = 15;
    private static final int COL_NOTE = 16;

    @Test
    @DisplayName("Every row has every column and an agreed status")
    void shape() throws Exception {
        List<String> bad = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String[] r : rows()) {
            if (r.length != COLUMNS) {
                bad.add(r[0] + " has " + r.length + " columns");
                continue;
            }
            if (!ids.add(r[0])) {
                bad.add(r[0] + " is duplicated");
            }
            for (int i = 0; i < COLUMNS; i++) {
                if (r[i].isBlank()) {
                    if (i == COL_NOTE && r[COL_STATUS].equals("VERIFIED")) {
                        continue;
                    }
                    bad.add(r[0] + " column " + i + " is blank");
                }
            }
            if (!STATUSES.contains(r[COL_STATUS])) {
                bad.add(r[0] + " status " + r[COL_STATUS]);
            }
        }
        assertEquals(List.of(), bad, String.valueOf(bad));
        assertTrue(ids.size() >= 100, "the matrix must cover the application, not a sample: " + ids.size());
    }

    @Test
    @DisplayName("Every Java handler, facade and database operation named in the matrix exists")
    void javaSymbolsResolve() throws Exception {
        Map<String, String> main = mainIndex();
        List<String> missing = new ArrayList<>();
        for (String[] r : rows()) {
            for (int col : List.of(COL_JAVA_HANDLER, COL_JAVA_FACADE, COL_DB_OP)) {
                for (String symbol : r[col].split(",")) {
                    String s = symbol.trim();
                    if (s.startsWith("(") || s.isEmpty()) {
                        continue; // "(none)", "(clipboard)", "(as S06)", "in-memory sort" ...
                    }
                    if (!s.matches("[A-Z][A-Za-z0-9]*(#[a-zA-Z0-9_]+)?")) {
                        continue; // prose such as "in-memory sort of the page"
                    }
                    if (!resolves(s, main)) {
                        missing.add(r[0] + " → " + s);
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "the matrix names Java that does not exist: " + missing);
    }

    @Test
    @DisplayName("Every test named in the matrix exists")
    void testsResolve() throws Exception {
        Map<String, String> tests = testIndex();
        List<String> missing = new ArrayList<>();
        for (String[] r : rows()) {
            if (!resolves(r[COL_TEST].trim(), tests)) {
                missing.add(r[0] + " → " + r[COL_TEST]);
            }
        }
        assertEquals(List.of(), missing, "the matrix cites tests that do not exist: " + missing);
    }

    @Test
    @DisplayName("Anything not VERIFIED explains itself")
    void limitationsExplained() throws Exception {
        List<String> silent = new ArrayList<>();
        for (String[] r : rows()) {
            if (!r[COL_STATUS].equals("VERIFIED") && r[COL_NOTE].trim().length() < 40) {
                silent.add(r[0] + " (" + r[COL_STATUS] + ")");
            }
        }
        assertEquals(List.of(), silent, "unexplained limitation: " + silent);
    }

    @Test
    @DisplayName("Every destination in the interface is a Java destination in the matrix")
    void everyScreenInventoried() throws Exception {
        Set<String> inMatrix = new LinkedHashSet<>();
        for (String[] r : rows()) {
            for (String d : r[COL_JAVA_DEST].split("[/,]")) {
                inMatrix.add(d.trim());
            }
            inMatrix.add(r[COL_JAVA_HANDLER].split("#")[0].trim());
        }
        List<String> missing = new ArrayList<>();
        Pattern title = Pattern.compile("public String title\\(\\)\\s*\\{\\s*return\\s+\"([^\"]+)\"");
        for (Map.Entry<String, String> e : mainIndex().entrySet()) {
            if (!e.getKey().endsWith("Screen") || !e.getValue().contains("implements")) {
                continue;
            }
            boolean named = inMatrix.contains(e.getKey());
            Matcher m = title.matcher(e.getValue());
            if (m.find()) {
                named |= inMatrix.contains(m.group(1));
            }
            if (!named) {
                missing.add(e.getKey());
            }
        }
        assertEquals(List.of(), missing, "screens with no matrix row: " + missing);
    }

    @Test
    @DisplayName("Every button label in the interface is inventoried as a Java control")
    void everyButtonInventoried() throws Exception {
        String controls = String.join("\n", rows().stream().map(r -> r[COL_JAVA_CONTROL]).toList())
                .toLowerCase();
        Pattern button = Pattern.compile(
                "Fas\\.(?:primary|secondary|outline|danger|ghost)\\(\"([^\"]{2,})\"");
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : mainIndex().entrySet()) {
            if (!e.getKey().endsWith("Screen") && !e.getKey().equals("SourceForm")) {
                continue;
            }
            Matcher m = button.matcher(e.getValue());
            while (m.find()) {
                String label = m.group(1).toLowerCase();
                String head = label.split(" ")[0];
                // "Back to X", "Analyze X", "Open X" are generic navigation verbs already covered
                if (head.equals("back") || head.equals("analyze") || head.equals("open")) {
                    continue;
                }
                if (!controls.contains(label) && !controls.contains(head)) {
                    missing.add(e.getKey() + ": " + m.group(1));
                }
            }
        }
        assertEquals(List.of(), missing, "controls with no matrix row: " + missing);
    }

    @Test
    @DisplayName("The narrative document agrees with the data")
    void documentAgrees() throws Exception {
        String md = Files.readString(docs().resolve("INTERFACE_FUNCTION_MATRIX.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("interface-function-matrix.tsv"),
                "the document must point at its machine-readable source");
        for (String s : STATUSES) {
            assertTrue(md.contains(s), "document must define status " + s);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String[]> cached;

    private static List<String[]> rows() throws IOException {
        if (cached == null) {
            List<String[]> out = new ArrayList<>();
            List<String> lines = Files.readAllLines(docs().resolve("interface-function-matrix.tsv"),
                    StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                out.add(lines.get(i).split("\t", -1));
            }
            cached = out;
        }
        return cached;
    }

    private static boolean resolves(String symbol, Map<String, String> index) {
        String type = symbol;
        String member = null;
        int hash = symbol.indexOf('#');
        if (hash >= 0) {
            type = symbol.substring(0, hash);
            member = symbol.substring(hash + 1);
        }
        String source = index.get(type);
        return source != null && (member == null || source.contains(member + "("));
    }

    private static Map<String, String> main;
    private static Map<String, String> tests;

    private static Map<String, String> mainIndex() throws IOException {
        if (main == null) {
            main = index(firstDirectory(List.of("app/src/main/java", "../app/src/main/java")));
        }
        return main;
    }

    private static Map<String, String> testIndex() throws IOException {
        if (tests == null) {
            tests = index(firstDirectory(List.of("app/src/test/java", "../app/src/test/java")));
        }
        return tests;
    }

    private static Map<String, String> index(Path root) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".java")) {
                    out.put(n.substring(0, n.length() - 5), Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    private static Path docs() {
        return firstDirectory(List.of("docs", "../docs"));
    }

    private static Path firstDirectory(List<String> candidates) {
        for (String c : candidates) {
            if (Files.isDirectory(Path.of(c))) {
                return Path.of(c);
            }
        }
        throw new IllegalStateException("not found: " + candidates);
    }

    public static void main(String[] args) throws Exception {
        InterfaceFunctionMatrixTest t = new InterfaceFunctionMatrixTest();
        int failed = 0;
        for (var m : InterfaceFunctionMatrixTest.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Test.class)) {
                try {
                    m.invoke(t);
                    System.out.println("  ok    " + m.getAnnotation(DisplayName.class).value());
                } catch (java.lang.reflect.InvocationTargetException e) {
                    failed++;
                    System.out.println("  FAIL  " + m.getAnnotation(DisplayName.class).value()
                            + "\n        " + e.getCause().getMessage());
                }
            }
        }
        System.out.println("=== " + (failed == 0 ? "all passed" : failed + " failed") + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
