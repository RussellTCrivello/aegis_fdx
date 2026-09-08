package com.aegis.fdx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the coverage inventory honest.
 *
 * <p>{@code docs/coverage.tsv} claims, for every destination and capability, that it is
 * implemented, that a named piece of Java does the work, and that a named test proves
 * it. A claim like that is worth exactly as much as its weakest row, so this suite
 * resolves every one of them: the classification must be one of the five agreed words,
 * the Java symbol must exist in the main sources, the test symbol must exist in the test
 * sources, and anything less than fully verified must say what is missing.
 *
 * <p>It also works the other way round. Every destination in the interface must appear
 * in the inventory, so that a screen cannot be added without being classified, and the
 * matrix document must describe the same number of rows as the data file, so that the
 * prose cannot quietly fall behind.
 */
public final class CoverageMatrixTest {

    private static final Set<String> CLASSIFICATIONS =
            Set.of("VERIFIED", "LIMITED", "ADAPTED", "UNSUPPORTED", "ABSENT");

    /** Classifications that must explain themselves. */
    private static final Set<String> MUST_EXPLAIN =
            Set.of("LIMITED", "ADAPTED", "UNSUPPORTED", "ABSENT");

    private record Row(String id, String area, String reference, String classification,
                       String javaSymbol, String operation, String test, String note) { }

    @Test
    @DisplayName("Every row is classified with one of the agreed words")
    void everyRowIsClassified() throws Exception {
        List<String> bad = new ArrayList<>();
        for (Row r : rows()) {
            if (!CLASSIFICATIONS.contains(r.classification())) {
                bad.add(r.id() + " → " + r.classification());
            }
        }
        assertEquals(List.of(), bad, "unknown classification: " + bad);
    }

    @Test
    @DisplayName("Every row names Java that exists")
    void everyRowNamesRealJava() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Row r : rows()) {
            for (String symbol : List.of(r.javaSymbol(), r.operation())) {
                if (!resolves(symbol, mainSourceIndex())) {
                    missing.add(r.id() + " → " + symbol);
                }
            }
        }
        assertEquals(List.of(), missing,
                "the inventory names Java that does not exist: " + missing);
    }

    @Test
    @DisplayName("Every row names a test that exists")
    void everyRowNamesRealTests() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Row r : rows()) {
            if (!resolves(r.test(), testSourceIndex())) {
                missing.add(r.id() + " → " + r.test());
            }
        }
        assertEquals(List.of(), missing,
                "the inventory cites tests that do not exist: " + missing);
    }

    @Test
    @DisplayName("Anything not fully verified says what is missing")
    void limitationsAreExplained() throws Exception {
        List<String> silent = new ArrayList<>();
        for (Row r : rows()) {
            if (MUST_EXPLAIN.contains(r.classification()) && r.note().length() < 40) {
                silent.add(r.id() + " (" + r.classification() + ")");
            }
        }
        assertEquals(List.of(), silent,
                "a limitation or omission with no explanation is a hidden gap: " + silent);
    }

    @Test
    @DisplayName("Every destination in the interface appears in the inventory")
    void everyDestinationIsInventoried() throws Exception {
        StringBuilder inventory = new StringBuilder();
        for (Row r : rows()) {
            inventory.append(r.javaSymbol()).append(' ').append(r.operation()).append(' ');
        }
        List<String> unlisted = new ArrayList<>();
        for (Path p : filesUnder(sourceRoot().resolve("com/aegis/fdx/ui/screens"))) {
            String name = p.getFileName().toString();
            if (!name.endsWith(".java")) {
                continue;
            }
            String code = Files.readString(p, StandardCharsets.UTF_8);
            if (!code.contains("implements Screen") && !code.contains("implements Detail")) {
                continue;
            }
            String simple = name.substring(0, name.length() - ".java".length());
            if (inventory.indexOf(simple) < 0) {
                unlisted.add(simple);
            }
        }
        assertEquals(List.of(), unlisted,
                "a destination exists but is not classified in docs/coverage.tsv: " + unlisted);
    }

    @Test
    @DisplayName("The matrix document describes the same rows as the data")
    void documentAgreesWithData() throws Exception {
        Path doc = docs().resolve("COVERAGE_MATRIX.md");
        assertTrue(Files.isRegularFile(doc), "docs/COVERAGE_MATRIX.md must exist");
        String text = Files.readString(doc, StandardCharsets.UTF_8);

        List<String> absent = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Row r : rows()) {
            ids.add(r.id());
            if (!text.contains(r.id())) {
                absent.add(r.id());
            }
        }
        assertEquals(List.of(), absent, "rows missing from the matrix document: " + absent);

        // And the document must not invent rows that the data does not have.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\|\\s*([DRAEX]\\d{2})\\s*\\|")
                .matcher(text);
        List<String> invented = new ArrayList<>();
        while (m.find()) {
            if (!ids.contains(m.group(1))) {
                invented.add(m.group(1));
            }
        }
        assertEquals(List.of(), invented, "the matrix document lists rows with no data: " + invented);
    }

    // =============================================================== helpers

    private static List<Row> rows() throws IOException {
        Path tsv = docs().resolve("coverage.tsv");
        assertTrue(Files.isRegularFile(tsv), "docs/coverage.tsv must exist");
        List<Row> out = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#") || line.startsWith("id\t")) {
                continue;
            }
            String[] c = line.split("\t", -1);
            assertEquals(8, c.length,
                    "line " + lineNumber + " of coverage.tsv has " + c.length + " columns, not 8");
            for (int i = 0; i < c.length; i++) {
                assertTrue(!c[i].isBlank(),
                        "line " + lineNumber + " of coverage.tsv leaves column " + (i + 1) + " empty");
            }
            out.add(new Row(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]));
        }
        assertTrue(out.size() >= 30, "the inventory must cover the whole application, not a sample");
        return out;
    }

    /**
     * True when {@code symbol} — {@code Type} or {@code Type#member} — is present in the
     * given source index.
     */
    private static boolean resolves(String symbol, java.util.Map<String, String> index) {
        String type = symbol;
        String member = null;
        int hash = symbol.indexOf('#');
        if (hash >= 0) {
            type = symbol.substring(0, hash);
            member = symbol.substring(hash + 1);
        }
        String source = index.get(type);
        if (source == null) {
            return false;
        }
        return member == null || source.contains(member);
    }

    private static java.util.Map<String, String> mainIndex;
    private static java.util.Map<String, String> testIndex;

    private static java.util.Map<String, String> mainSourceIndex() throws IOException {
        if (mainIndex == null) {
            mainIndex = index(sourceRoot());
        }
        return mainIndex;
    }

    private static java.util.Map<String, String> testSourceIndex() throws IOException {
        if (testIndex == null) {
            testIndex = index(testRoot());
        }
        return testIndex;
    }

    private static java.util.Map<String, String> index(Path root) throws IOException {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (Path p : filesUnder(root)) {
            String name = p.getFileName().toString();
            if (!name.endsWith(".java")) {
                continue;
            }
            out.put(name.substring(0, name.length() - ".java".length()),
                    Files.readString(p, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Path sourceRoot() {
        return firstDirectory(List.of("app/src/main/java", "src/main/java",
                "../app/src/main/java"), "com/aegis/fdx", "main sources");
    }

    private static Path testRoot() {
        return firstDirectory(List.of("app/src/test/java", "src/test/java",
                "../app/src/test/java"), "com/aegis/fdx", "test sources");
    }

    private static Path docs() {
        return firstDirectory(List.of("docs", "../docs", "../../docs"), "", "docs");
    }

    private static Path firstDirectory(List<String> candidates, String marker, String what) {
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(marker.isEmpty() ? p : p.resolve(marker))) {
                return p;
            }
        }
        throw new IllegalStateException("cannot find the " + what + " from " + Path.of("").toAbsolutePath());
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    // ================================================================== main

    public static void main(String[] args) {
        CoverageMatrixTest t = new CoverageMatrixTest();
        String[] names = {
            "every row is classified",
            "every row names Java that exists",
            "every row names a test that exists",
            "anything not fully verified says what is missing",
            "every destination in the interface is inventoried",
            "the matrix document agrees with the data",
        };
        Check[] body = {
            t::everyRowIsClassified,
            t::everyRowNamesRealJava,
            t::everyRowNamesRealTests,
            t::limitationsAreExplained,
            t::everyDestinationIsInventoried,
            t::documentAgreesWithData,
        };
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < body.length; i++) {
            try {
                body[i].run();
                System.out.println("  ok    " + names[i]);
                passed++;
            } catch (Throwable e) {
                System.out.println("  FAIL  " + names[i] + " — " + e);
                failed++;
            }
        }
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface Check {
        void run() throws Exception;
    }
}
