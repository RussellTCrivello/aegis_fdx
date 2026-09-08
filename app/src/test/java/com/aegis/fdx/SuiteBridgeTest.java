package com.aegis.fdx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUnit bridge for the standalone suite harnesses.
 *
 * <p>The suites in this package are executable {@code main()} harnesses rather than
 * annotated JUnit classes: they are driven by {@code run-tests.sh} and
 * {@code final-acceptance.sh}, print their own evidence, and must run without a test
 * framework on the release gate. That design is deliberate and stays.
 *
 * <p>Gradle, however, resolves {@code :app:test} against the JUnit platform. With no
 * discoverable tests it fails the build outright
 * ({@code failOnNoDiscoveredTests}), which is what happened on the Windows host —
 * the harnesses were compiled but never executed, so a green Gradle build would have
 * meant nothing.
 *
 * <p>This class makes {@code ./gradlew :app:test} run the real suites and fail on real
 * failures. Each harness is invoked in-process; its {@code main()} returns normally on
 * success and calls {@code System.exit(1)} on failure, so a {@link SecurityException}-free
 * approach is used: every harness also prints a machine-readable footer
 * ({@code === N passed, M failed ===}) which is parsed here and asserted on.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AEGIS-FDX suite bridge")
class SuiteBridgeTest {

    /**
     * Suite footers come in two shapes: most print
     * {@code === N passed, M failed ===}, while QueryParserTest prints a bare
     * {@code N passed, M failed}. Both are accepted; the delimiters are optional.
     */
    private static final Pattern FOOTER = Pattern.compile(
            "(?m)^\\s*(?:===\\s*)?(\\d+)\\s+passed,\\s*(\\d+)\\s+failed(?:\\s*===)?\\s*$");

    @TempDir
    static Path work;

    @Test
    @Order(1)
    @DisplayName("query parser unit tests (M1)")
    void queryParser() throws Exception {
        runSuite("QueryParserTest", () -> QueryParserTest.main(new String[0]));
    }

    @Test
    @Order(2)
    @DisplayName("query validation (unknown fields / dates / regex)")
    void queryValidation() throws Exception {
        runSuite("QueryValidationTest", () -> QueryValidationTest.main(new String[0]));
    }

    @Test
    @Order(3)
    @DisplayName("drag-and-drop intake (F-01)")
    void dragAndDrop() throws Exception {
        Path w = work.resolve("dnd");
        runSuite("DragDropIngestTest",
                () -> DragDropIngestTest.main(new String[]{w.toString()}));
    }

    @Test
    @Order(4)
    @DisplayName("windows compatibility (N-01)")
    void windowsCompatibility() throws Exception {
        Path w = work.resolve("win");
        runSuite("WindowsCompatibilityTest",
                () -> WindowsCompatibilityTest.main(new String[]{w.toString()}));
    }

    @Test
    @Order(5)
    @DisplayName("pipeline acceptance AT-01..AT-10 (M2)")
    void pipelineAcceptance() throws Exception {
        Path ds = work.resolve("dataset");
        TestDataset.main(new String[]{ds.toString()});
        Path w = work.resolve("at");
        runSuite("PipelineAcceptanceTest",
                () -> PipelineAcceptanceTest.main(new String[]{w.toString()}));
    }

    @Test
    @Order(6)
    @DisplayName("milestone 3 acceptance (OCR / export / reports / integrity)")
    void milestone3() throws Exception {
        Path w = work.resolve("m3");
        runSuite("M3AcceptanceTest",
                () -> M3AcceptanceTest.main(new String[]{w.toString()}));
    }

    // =====================================================================

    @FunctionalInterface
    private interface Harness {
        void run() throws Exception;
    }

    /**
     * Runs a harness, mirrors its output to the build log, and asserts on the
     * {@code === N passed, M failed ===} footer it prints.
     *
     * <p>Note: each harness prints its footer <em>before</em> calling
     * {@code System.exit(1)} on failure. Gradle's worker treats that exit as a crashed
     * worker, so the footer assertions below are what turn a suite failure into a
     * readable JUnit result. The mirrored output is printed either way, so the failing
     * checks are always visible in the build log.
     */
    private void runSuite(String name, Harness harness) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream original = System.out;
        PrintStream tee = new PrintStream(buf, true, StandardCharsets.UTF_8);

        try {
            System.setOut(tee);
            harness.run();
        } finally {
            System.setOut(original);
        }

        String out = buf.toString(StandardCharsets.UTF_8);
        original.print(out);

        Matcher m = FOOTER.matcher(out);
        int passed = 0, failed = 0, footers = 0;
        while (m.find()) {
            passed += Integer.parseInt(m.group(1));
            failed += Integer.parseInt(m.group(2));
            footers++;
        }

        assertTrue(footers > 0,
                name + " produced no '=== N passed, M failed ===' footer; "
                        + "cannot verify it actually ran");
        assertTrue(passed > 0, name + " reported zero passing assertions");
        assertEquals(0, failed, name + " reported " + failed + " failing assertion(s)");

        original.println("  [bridge] " + name + ": " + passed + " passed, " + failed + " failed");
    }

    /** Guards against the dataset generator silently producing nothing. */
    @Test
    @Order(0)
    @DisplayName("reference dataset generates")
    void datasetGenerates() throws Exception {
        Path ds = work.resolve("dataset-check");
        TestDataset.main(new String[]{ds.toString()});
        long files;
        try (var s = Files.walk(ds)) {
            files = s.filter(Files::isRegularFile).count();
        }
        assertTrue(files >= 40,
                "reference dataset should generate at least 40 files, got " + files);
    }
}
