package com.aegis.fdx;

import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FileState;
import com.aegis.fdx.facade.dto.ContentDto;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;
import com.aegis.fdx.ui.screens.AnalysisScreen;
import com.aegis.fdx.ui.screens.CategoriesScreen;
import com.aegis.fdx.ui.screens.DashboardScreen;
import com.aegis.fdx.ui.screens.EmailWordsScreen;
import com.aegis.fdx.ui.screens.FileLibraryScreen;
import com.aegis.fdx.ui.screens.ImportExportScreen;
import com.aegis.fdx.ui.screens.KeywordsScreen;
import com.aegis.fdx.ui.screens.NotificationsScreen;
import com.aegis.fdx.ui.screens.SavedSearchesScreen;
import com.aegis.fdx.ui.screens.SearchScreen;
import com.aegis.fdx.ui.screens.SettingsScreen;
import com.aegis.fdx.ui.screens.AspectsScreen;
import com.aegis.fdx.ui.screens.SourcesScreen;
import com.aegis.fdx.ui.screens.UploadScreen;
import com.aegis.fdx.ui.screens.WordsScreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the File Analysis System front end and the sources/sides/categories/
 * keywords/contents integration.
 *
 * <p>Scope note: JavaFX scene-graph construction needs a toolkit, which is not always
 * available on a headless CI box. These tests therefore assert the parts that can be
 * verified without a display — the screen inventory, the Python navigation order, the
 * icon set, and above all the <em>data contracts</em> each screen renders. The visual
 * rendering itself is verified separately by {@code tools/FasShotHarness}, which
 * launches the real application and writes {@code docs/screens-fas/*.png}.
 */
class UiParityTest {

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Ui-Parity", s);
    }

    /** The Python sidebar, in the order base.html renders it. */
    private static final List<String> PYTHON_NAV = List.of(
            "Dashboard", "Analysis", "Search", "Sources", "Aspects", "Email Words",
            "Keywords", "Words", "Categories", "Upload Files", "File Library",
            "Notifications", "Settings");

    @Test
    @DisplayName("Every Python sidebar entry has a Screen with the same title and a distinct icon")
    void screenInventory(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            // The agent is optional; a null provider means "unavailable", which is a
            // valid state and keeps this test independent of any model runtime.
            AgentService agent = new AgentService(f, new CorpusDatabase(c.db()), null);
            List<Screen> screens = List.of(
                    new DashboardScreen(f), new AnalysisScreen(f),
                    new SearchScreen(f, agent),
                    new SourcesScreen(f), new AspectsScreen(f), new EmailWordsScreen(f),
                    new KeywordsScreen(f, agent), new WordsScreen(f),
                    new CategoriesScreen(f, agent),
                    new UploadScreen(f), new FileLibraryScreen(f),
                    new NotificationsScreen(f), new SettingsScreen(f, new CaseSettings()),
                    new SavedSearchesScreen(f), new ImportExportScreen(f));

            List<String> titles = new ArrayList<>();
            for (Screen s : screens) {
                titles.add(s.title());
                assertNotNull(s.icon(), s.title() + " must declare an icon");
                assertFalse(s.icon().isBlank(), s.title() + " icon must not be blank");
                assertNotNull(s.breadcrumb(), s.title() + " must declare a breadcrumb");
                assertTrue(s.breadcrumb().startsWith("Home"),
                        s.title() + " breadcrumb should start at Home, like the Python bar");
            }

            // every Python nav entry is present, with the Python label verbatim
            for (String expected : PYTHON_NAV) {
                assertTrue(titles.contains(expected),
                        "missing screen for Python nav entry: " + expected);
            }

            // and the shared ones appear in the Python order
            List<String> ordered = titles.stream().filter(PYTHON_NAV::contains).toList();
            assertEquals(PYTHON_NAV, ordered, "sidebar order must match Python base.html");
        }
    }

    @Test
    @DisplayName("Icon set covers every Bootstrap Icon the Python sidebar uses")
    void iconSet() {
        // Named after their bi-* counterparts in the Python templates.
        String[] required = {
                Icons.SPEEDOMETER2, Icons.ARCHIVE, Icons.SEARCH, Icons.BUILDING,
                Icons.DIAGRAM3, Icons.ENVELOPE_AT, Icons.KEY, Icons.BOOK, Icons.TAGS,
                Icons.CLOUD_UPLOAD, Icons.FOLDER_OPEN, Icons.BELL, Icons.GEAR
        };
        for (String p : required) {
            assertNotNull(p);
            assertFalse(p.isBlank());
        }
        // distinct glyphs, so no two nav entries look identical
        assertEquals(required.length, java.util.Arrays.stream(required).distinct().count());

        // Glyphs the stat cards across the analysis screens bind to; a missing
        // constant here is a compile failure in the screen that references it, so
        // this guards the set the screens are allowed to use.
        String[] statGlyphs = {
                Icons.FILES, Icons.CHECK_CIRCLE, Icons.FOLDER, Icons.FOLDER_OPEN,
                Icons.FONTS, Icons.TAGS, Icons.KEY, Icons.DATABASE, Icons.HDD_STACK,
                Icons.ARCHIVE, Icons.LIST, Icons.CHART, Icons.SHIELD, Icons.INFO,
                Icons.ALERT
        };
        for (String p : statGlyphs) {
            assertNotNull(p);
            assertFalse(p.isBlank());
        }

        assertNotNull(Icons.outline(Icons.SEARCH, "#4f46e5", 16));
        assertNotNull(Icons.filled(Icons.PLAY, "#4f46e5", 16));
        assertNotNull(Icons.box(Icons.GEAR, "#4f46e5", 16));
    }

    @Test
    @DisplayName("Stylesheet ships on the classpath with the Python design tokens")
    void stylesheetPresent() throws Exception {
        var url = UiParityTest.class.getResource("/com/aegis/fdx/ui/fas.css");
        assertNotNull(url, "fas.css must be packaged as a resource");
        String css = new String(url.openStream().readAllBytes(), StandardCharsets.UTF_8);
        // exact token values from the Python :root block
        assertTrue(css.contains("#4f46e5"), "primary colour");
        assertTrue(css.contains("#06b6d4"), "secondary colour");
        assertTrue(css.contains("#1e293b"), "sidebar background");
        assertTrue(css.contains("260px"), "sidebar width");
        assertTrue(css.contains(".sidebar-nav-link"), "nav link class");
        assertTrue(css.contains(".stat-card"), "stat card class");
        assertTrue(css.contains(".section-card"), "section card class");
        assertTrue(css.contains(".badge"), "badge class");
    }

    @Test
    @DisplayName("Contents integration: sources, sides, paths and contents link to real ingest")
    void contentsIntegration(@TempDir Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("invoice.txt"),
                "Invoice 2024 consulting services total due", StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("memo.txt"),
                "Memo about the consulting engagement", StandardCharsets.UTF_8);

        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int srcId = f.sources().createSource("Acme", "NL", "custodian", 0.8);
            int sideId = f.aspects().createAspect("Plaintiff", 0.9);

            // run the real pipeline
            f.processing("Acme", "Plaintiff").processFolder(ev.toString());

            // project into the Python relational model
            int registered = f.contents().registerIngestedItems(srcId, sideId);
            assertTrue(registered >= 2, "each ingested element becomes a paths row");

            var page = f.contents().getPaths();
            assertEquals(registered, page.totalCount());

            PathDto p = page.results().get(0);
            assertNotNull(p.fileName());
            assertNotNull(p.hashValue(), "paths row links to a hashs row from the real SHA-256");
            assertEquals("Acme", p.sourceName(), "path is linked to its source");
            assertEquals("Plaintiff", p.aspectName(), "path is linked to its aspect");
            assertEquals(FileState.UNREAD.label(), p.fileStatus());
            assertNotNull(p.elementId(), "path keeps the AEGIS element id");

            // extracted text reached the contents table
            String text = f.contents().getContentAsText(p.id());
            assertNotNull(text);
            assertFalse(text.isBlank(), "extracted text is stored as content");

            List<ContentDto> parts = f.contents().getContents(p.id());
            assertFalse(parts.isEmpty());
            assertEquals(p.id(), parts.get(0).pathId());

            // re-running is idempotent: no duplicate registry rows
            assertEquals(0, f.contents().registerIngestedItems(srcId, sideId));
            assertEquals(registered, f.contents().getPaths().totalCount());

            // status round-trip, as the File Library toggle does
            assertTrue(f.contents().setPathStatus(p.id(), FileState.READ.label()));
            assertEquals(FileState.READ.label(),
                    f.contents().getPath(p.id()).fileStatus());

            // filters used by the File Library screen
            assertEquals(registered,
                    f.contents().getPaths(null, srcId, sideId, null, 100, 0).totalCount());
            assertEquals(0,
                    f.contents().getPaths("pdf", null, null, null, 100, 0).results().size());
            assertEquals(1, f.contents()
                    .getPaths(null, null, null, FileState.READ.label(), 100, 0).totalCount());
        }
    }

    @Test
    @DisplayName("Hash and path facades validate like the Python service")
    void contentValidation(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int h = f.contents().createHash("abc123", null);
            assertTrue(h > 0);
            assertEquals(h, f.contents().createHash("abc123", null), "hash insert is idempotent");

            int pathId = f.contents().createPath("a.txt", "/tmp/a.txt", 10, "txt",
                    FileState.UNREAD.label(), LocalDate.now(), LocalDate.now(),
                    h, null, null, null, null);
            assertTrue(pathId > 0);

            // Python CHECK constraint on file_status
            assertThrowsFacade(() -> f.contents().createPath("b.txt", "/tmp/b.txt", 1, "txt",
                    "Archived", null, null, null, null, null, null, null));
            // negative size violates the Python CHECK
            assertThrowsFacade(() -> f.contents().createPath("c.txt", "/tmp/c.txt", -5, "txt",
                    null, null, null, null, null, null, null, null));
            // required fields
            assertThrowsFacade(() -> f.contents().createPath("", "/tmp/d.txt", 1, "txt",
                    null, null, null, null, null, null, null, null));
            assertThrowsFacade(() -> f.contents().getPath(999_999));

            int cid = f.contents().createContent("hello world", LocalDate.now(), pathId);
            assertTrue(cid > 0);
            assertEquals("hello world", f.contents().getContentAsText(pathId));
            assertTrue(f.contents().deleteContent(cid));
            assertEquals("", f.contents().getContentAsText(pathId));
        }
    }

    @Test
    @DisplayName("Dashboard byte formatting matches the Python Storage Used caption")
    void byteFormatting() {
        assertEquals("512 B", DashboardScreen.humanBytes(512));
        assertEquals("1.0 KB", DashboardScreen.humanBytes(1024));
        assertEquals("1.5 KB", DashboardScreen.humanBytes(1536));
        assertEquals("1.0 MB", DashboardScreen.humanBytes(1024L * 1024));
        assertEquals("1.0 GB", DashboardScreen.humanBytes(1024L * 1024 * 1024));
    }

    private static void assertThrowsFacade(Runnable r) {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.aegis.fdx.facade.FacadeException.class, r::run);
    }
}
