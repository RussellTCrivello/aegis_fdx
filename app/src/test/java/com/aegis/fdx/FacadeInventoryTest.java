package com.aegis.fdx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Machine-checkable facade inventory (release-gate item 1).
 *
 * <p>{@code docs/facades.tsv} maps the 99 reference screenshots to reusable Java
 * screens. These checks prove the inventory corresponds to the code: every row names
 * a real screen and a destination the GUI gate actually visits. They are STATIC
 * correspondence checks — runtime proof comes from the GUI gate itself
 * ({@code tools/FasShotHarness} under Xvfb), which fails the build when a
 * destination does not render.
 */
class FacadeInventoryTest {

    private static final Path TSV = Path.of("docs/facades.tsv");
    private static final Path SCREENS = Path.of("app/src/main/java/com/aegis/fdx/ui/screens");
    private static final Path HARNESS = Path.of("tools/FasShotHarness.java");

    /** Detail destinations are opened by id, not by navigation key. */
    private static final Map<String, String> DETAIL_METHODS = Map.of(
            "Source Detail", "openSource",
            "Aspect Detail", "openAspect",
            "File Detail", "openFile",
            "Full Content", "openContent",
            "Keyword Detail", "openKeyword",
            "Word Detail", "openWord",
            "Category Detail", "openCategoryDetail");

    private record Row(String id, String refGroup, String refShots, String javaScreen,
                       String variant, String gate, String status) {
    }

    private static List<Row> rows() throws Exception {
        List<String> lines = Files.readAllLines(TSV);
        assertFalse(lines.isEmpty(), "facades.tsv is empty");
        String[] header = lines.get(0).split("\t", -1);
        assertArrayEquals(
                new String[]{"id", "ref_group", "ref_shots", "java_screen", "variant", "gate",
                        "status"},
                header);
        List<Row> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] c = lines.get(i).split("\t", -1);
            assertEquals(7, c.length, "row " + (i + 1) + " must have 7 columns");
            out.add(new Row(c[0], c[1], c[2], c[3], c[4], c[5], c[6]));
        }
        return out;
    }

    private static String allScreens() throws Exception {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> s = Files.list(SCREENS)) {
            for (Path p : s.filter(p -> p.toString().endsWith("Screen.java")).sorted()
                    .toList()) {
                b.append(Files.readString(p)).append('\n');
            }
        }
        return b.toString();
    }

    @Test
    @DisplayName("rows are well-formed: unique ids, fixed status vocabulary, no empty keys")
    void rowsWellFormed() throws Exception {
        List<Row> rows = rows();
        assertFalse(rows.isEmpty());
        Set<String> ids = new HashSet<>();
        Set<String> vocab = Set.of("NOT RUN", "EXECUTED", "STATICALLY VERIFIED");
        for (Row r : rows) {
            assertTrue(ids.add(r.id()), "duplicate id " + r.id());
            assertFalse(r.javaScreen().isBlank(), r.id() + " names no screen");
            assertFalse(r.gate().isBlank(), r.id() + " names no gate");
            assertFalse(r.refShots().isBlank(), r.id() + " cites no reference");
            assertTrue(vocab.contains(r.status()),
                    r.id() + " has unknown status " + r.status());
        }
    }

    @Test
    @DisplayName("every row names a Screen that exists in the codebase")
    void everyRowNamesAScreen() throws Exception {
        String code = allScreens();
        List<String> missing = new ArrayList<>();
        for (Row r : rows()) {
            // The title string must appear as a literal in some *Screen.java: a plain
            // title(), the TermDetailScreen switch arms, or the RelationshipsScreen
            // ternary. Correspondence, not rendering — the GUI gate proves rendering.
            if (!code.contains("\"" + r.javaScreen() + "\"")) {
                missing.add(r.id() + " → " + r.javaScreen());
            }
        }
        assertEquals(List.of(), missing, "the inventory names screens that do not exist");
    }

    @Test
    @DisplayName("every screen is covered by the GUI gate harness")
    void everyScreenIsCoveredByTheGuiGate() throws Exception {
        String harness = Files.readString(HARNESS);
        List<String> missing = new ArrayList<>();
        for (Row r : rows()) {
            String screen = r.javaScreen();
            if (harness.contains("\"" + screen + "\"")) {
                continue;
            }
            String m = DETAIL_METHODS.get(screen);
            if (m != null && harness.contains(m)) {
                continue;
            }
            missing.add(r.id() + " → " + screen);
        }
        assertEquals(List.of(), missing, "screens the GUI gate never visits");
    }
}
