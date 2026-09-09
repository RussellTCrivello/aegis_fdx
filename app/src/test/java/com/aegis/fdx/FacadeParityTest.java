package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.ContentFacade;
import com.aegis.fdx.facade.ExportFacade;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.FileProcessingFacade;
import com.aegis.fdx.facade.ImportFacade;
import com.aegis.fdx.facade.PreviewFacade;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.facade.SortField;
import com.aegis.fdx.facade.SortOrder;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.KeywordDto;
import com.aegis.fdx.facade.dto.NotificationDto;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.PreviewDto;
import com.aegis.fdx.facade.dto.ProcessingResultDto;
import com.aegis.fdx.facade.dto.SavedSearchDto;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.facade.dto.StatisticsDto;
import com.aegis.fdx.facade.dto.WordDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Python-parity facade layer.
 *
 * <p>Scope is the interface contract, not the internals: for each facade this asserts
 * that the operation exists with the Python parameter shape, that Python's documented
 * defaults are applied, that interface-level validation rejects bad input the way the
 * Python services do, and that the call actually reaches real Java functionality.
 */
class FacadeParityTest {

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Facade-Parity", s);
    }

    // ================= sources =================

    @Test
    @DisplayName("SourceFacade.createSource matches Python create_source signature and defaults")
    void sourceFacade(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            // Python: create_source(name, country, job, importance) with the optional tail defaulted
            int id = f.sources().createSource("Acme Corp", "NL", "custodian", 0.75);
            assertTrue(id > 0, "create_source returns a generated id");

            SourceDto got = f.sources().getSource(id);
            assertEquals("Acme Corp", got.name());
            assertEquals("NL", got.country());
            assertEquals("custodian", got.job());
            assertEquals(0.75, got.importance(), 1e-9);
            // Python defaults entry_date to date.today() when None
            assertEquals(LocalDate.now(), got.entryDate());
            // Python defaults the optional strings to ""
            assertEquals("", got.city());

            // full positional form
            int full = f.sources().createSource(
                    new SourceDraft("Beta Ltd", "DE", "witness", 0.5)
                            .city("Berlin").description("desc").accounts("acct")
                            .note("note").attachments("att").ownership("own")
                            .accessStatus("open").entryDate(LocalDate.of(2024, 1, 15)));
            SourceDto b = f.sources().getSource(full);
            assertEquals("Berlin", b.city());
            assertEquals(LocalDate.of(2024, 1, 15), b.entryDate());

            assertEquals(2, f.sources().listSources().size());

            // interface-level validation, mirroring Python ValueError
            assertThrows(FacadeException.class, () -> f.sources().createSource("", "NL", "j", 0.5));
            assertThrows(FacadeException.class,
                    () -> f.sources().createSource("X", "NL", "j", 5.0));
            // unique name -> conflict
            FacadeException dup = assertThrows(FacadeException.class,
                    () -> f.sources().createSource("Acme Corp", "NL", "custodian", 0.75));
            assertEquals(FacadeException.Kind.CONFLICT, dup.kind());
            // missing id -> not found
            FacadeException nf = assertThrows(FacadeException.class,
                    () -> f.sources().getSource(9999));
            assertEquals(FacadeException.Kind.NOT_FOUND, nf.kind());

            int copy = f.sources().duplicateSource(id);
            assertTrue(copy > 0);
            assertTrue(f.sources().deleteSource(copy));
        }
    }

    // ================= sides =================

    @Test
    @DisplayName("AspectFacade creates, reads and validates aspects")
    void aspectFacade(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int id = f.aspects().createAspect("Plaintiff", 0.9);
            AspectDto s = f.aspects().getAspect(id);
            assertEquals("Plaintiff", s.name());
            assertEquals(0.9, s.importance(), 1e-9);
            assertEquals(LocalDate.now(), s.dateCreation(), "date_creation defaults to today");

            int dated = f.aspects().createAspect("Defendant", 0.4, LocalDate.of(2023, 6, 1));
            assertEquals(LocalDate.of(2023, 6, 1), f.aspects().getAspect(dated).dateCreation());

            assertEquals(2, f.aspects().listAspects().size());
            assertThrows(FacadeException.class, () -> f.aspects().createAspect(" ", 0.5));
            assertThrows(FacadeException.class, () -> f.aspects().createAspect("Z", -1.0));
        }
    }

    // ================= words / categories / keywords =================

    @Test
    @DisplayName("Word, Category and Keyword facades mirror the Python corpus operations")
    void corpusFacades(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int w1 = f.words().createWord("invoice");
            int w2 = f.words().createWord("contract");
            assertTrue(w1 > 0 && w2 > 0);
            // Python relies on the UNIQUE constraint: creating again returns the same id
            assertEquals(w1, f.words().createWord("invoice"));

            Page<WordDto> page = f.words().getWords();
            assertEquals(2, page.totalCount());
            assertEquals(2, page.results().size());

            Page<WordDto> hit = f.words().searchWords("inv", 10, 0);
            assertEquals(1, hit.results().size());
            assertEquals("invoice", hit.results().get(0).word());

            assertTrue(f.words().updateWord(w2, "agreement"));
            assertEquals("agreement", f.words().getWord(w2).word());

            // categories are keyed by word in Python
            int cat = f.categories().createCategory("finance");
            assertTrue(cat > 0);
            assertTrue(f.categories().categoryExists("finance"));
            assertFalse(f.categories().categoryExists("nonexistent"));

            CategoryDto cd = f.categories().getCategory(cat);
            assertEquals("finance", cd.word());

            assertTrue(f.categories().linkWordToCategory("invoice", "finance"));
            assertEquals(1, f.categories().getCategoryWords(cat, 10, 0).results().size());

            // keywords hang off a category word
            assertTrue(f.keywords().createKeyword("unpaid invoice notice", "finance"));
            Page<KeywordDto> kws = f.keywords().listKeywords();
            assertEquals(1, kws.totalCount());
            assertEquals("finance", kws.results().get(0).categoryWord());
            assertTrue(f.keywords().keywordExists("unpaid invoice notice"));

            Map<Integer, List<Integer>> grouped = f.keywords().keywordIdsByCategory();
            assertEquals(1, grouped.size());

            // unknown category -> NOT_FOUND, not a silent success
            FacadeException nf = assertThrows(FacadeException.class,
                    () -> f.keywords().createKeyword("a valid phrase", "no-such-category"));
            assertEquals(FacadeException.Kind.NOT_FOUND, nf.kind());

            assertEquals(1, f.words().bulkDeleteWords(List.of(w2)));
        }
    }

    // ================= notifications =================

    @Test
    @DisplayName("NotificationFacade mirrors notification_service and /api/notifications")
    void notificationFacade(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int n1 = f.notifications().createNotification("info", "high", "Ingest done",
                    "All files processed", "E-000001", null);
            assertTrue(n1 > 0);

            int n2 = f.notifications().createSimilarFilesNotification(
                    "E-000002", List.of("E-000003", "E-000004"), "normal");
            assertTrue(n2 > 0);

            Instant soon = Instant.now().plus(5, ChronoUnit.DAYS);
            int n3 = f.notifications().createFutureEventNotification(
                    "E-000005", soon, "Hearing", "Court date");
            assertTrue(n3 > 0);

            assertEquals(3, f.notifications().getPendingCount());
            Page<NotificationDto> all = f.notifications().getNotifications();
            assertEquals(3, all.results().size());

            assertTrue(f.notifications().markAsRead(n1));
            assertEquals(2, f.notifications().getPendingCount());

            assertTrue(f.notifications().dismissNotification(n2));
            assertEquals(2, f.notifications().getStats().active());

            // Python default days_ahead=30 finds the 5-day-out event
            List<NotificationDto> upcoming = f.notifications().getUpcomingEvents();
            assertEquals(1, upcoming.size());
            assertEquals("Hearing", upcoming.get(0).title());

            assertThrows(FacadeException.class,
                    () -> f.notifications().getUpcomingEvents(0));
        }
    }

    // ================= search history =================

    @Test
    @DisplayName("SearchHistoryFacade mirrors search_history.py including defaults")
    void searchHistoryFacade(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            f.history().addSearch("invoice", 12);
            f.history().addSearch("contract", 3, "user-1");
            assertEquals(2, f.history().getHistory().size());
            assertEquals(1, f.history().getHistory(20, "user-1").size());

            int saved = f.history().saveSearch("My search", "invoice AND paid");
            SavedSearchDto s = f.history().getSavedSearch(saved);
            assertEquals("My search", s.name());
            assertEquals(0, s.useCount());

            f.history().markUsed(saved);
            assertEquals(1, f.history().getSavedSearch(saved).useCount());

            assertTrue(f.history().updateSavedSearch(saved, "Renamed", null, null));
            assertEquals("Renamed", f.history().getSavedSearch(saved).name());

            assertThrows(FacadeException.class,
                    () -> f.history().updateSavedSearch(saved, null, null, null));

            assertTrue(f.history().deleteSavedSearch(saved));
            assertEquals(0, f.history().getSavedSearches().size());

            f.history().clearHistory();
            assertEquals(0, f.history().getHistory().size());
        }
    }

    // ================= processing + search + preview + dashboard =================

    @Test
    @DisplayName("Processing, Search, Preview and Dashboard facades run against a real case")
    void endToEnd(@TempDir Path tmp) throws Exception {
        Path evidence = tmp.resolve("evidence");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("invoice.txt"),
                "Invoice 2024 total due 1500 EUR for consulting services",
                StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("memo.txt"),
                "Internal memo about the consulting engagement",
                StandardCharsets.UTF_8);

        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            // Python: storage_source and storage_side are MANDATORY
            assertThrows(FacadeException.class, () -> f.processing(null, "left"));
            assertThrows(FacadeException.class, () -> f.processing("Acme", " "));

            FileProcessingFacade proc = f.processing("Acme", "left");

            // Python zero-data-loss: a missing path returns a record, does not throw
            ProcessingResultDto missing = proc.processSingleFile(
                    tmp.resolve("nope.txt").toString());
            assertFalse(missing.success());
            assertNotNull(missing.error(), "failed file still yields a record");

            List<ProcessingResultDto> results = proc.processFolder(evidence.toString());
            assertTrue(results.size() >= 2, "every discovered file yields a record");

            StatisticsDto stats = proc.getStatistics();
            assertTrue(stats.total() >= 2);
            // Python access pattern stats.get('total')
            assertEquals(stats.total(), stats.get("total"));
            assertTrue(proc.getStorageStatistics().completed() >= 2);

            // ---- search ----
            Page<SearchResultDto> page = f.search().search("consulting");
            assertEquals(2, page.totalCount(), "both documents mention consulting");
            assertEquals(2, page.results().size());

            // Python simple_search defaults
            assertEquals(2, f.search().search("consulting").totalCount());

            // paging: limit/offset behave as in Python
            Page<SearchResultDto> first = f.search().search(SearchCriteria.of("consulting")
                    .sortBy(SortField.NAME, SortOrder.ASCENDING).page(0, 1));
            Page<SearchResultDto> second = f.search().search(SearchCriteria.of("consulting")
                    .sortBy(SortField.NAME, SortOrder.ASCENDING).page(1, 1));
            assertEquals(1, first.results().size());
            assertEquals(1, second.results().size());
            assertFalse(first.results().get(0).id().equals(second.results().get(0).id()),
                    "offset advances the window");

            // file_type filter
            assertEquals(2, f.search().search(
                    SearchCriteria.of("consulting").fileType("txt")).totalCount());
            assertEquals(0, f.search().search(
                    SearchCriteria.of("consulting").fileType("pdf")).results().size());

            // validation mirrors Python's rejects
            assertThrows(FacadeException.class, () -> f.search().search(""));
            assertThrows(FacadeException.class,
                    () -> SearchCriteria.of("x").sortBy(null, SortOrder.ASCENDING));
            assertThrows(FacadeException.class, () -> SearchCriteria.of("x").limit(0));
            assertThrows(FacadeException.class, () -> SearchCriteria.of("x")
                    .between(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 1, 1)));

            // source_id resolves through SourceFacade
            int src = f.sources().createSource("Acme", "NL", "custodian", 0.5);
            assertEquals(2, f.search().search(
                    SearchCriteria.of("consulting").source(src)).totalCount());

            assertFalse(f.search().autocomplete("invo").isEmpty());

            // "Did you mean": a near miss must offer documents that really exist here.
            // The Search destination shows these as clickable chips when a query finds
            // nothing, so an empty result is never the end of the road.
            List<String> nearMiss = f.search().getSearchSuggestions("consultng");
            assertFalse(nearMiss.isEmpty(),
                    "a one-character typo should still find the consulting documents");
            for (String suggestion : nearMiss) {
                assertTrue(List.of("invoice.txt", "memo.txt").contains(suggestion),
                        "a suggestion must name a document that exists here, not a guess: "
                                + suggestion);
            }
            assertTrue(nearMiss.stream()
                            .anyMatch(sug -> f.search().search(sug).totalCount() > 0),
                    "clicking a suggestion must lead somewhere real");
            assertTrue(f.search().getSearchSuggestions("zzzzzzzzzz").isEmpty(),
                    "nothing close means no suggestion, rather than an invented one");

            // ---- preview ----
            String id = page.results().get(0).id();
            PreviewDto pv = f.preview().getPreview(id);
            assertEquals(PreviewDto.PreviewType.TEXT, pv.previewType());
            assertNotNull(pv.content(), "extracted text is returned for a txt element");
            assertEquals(1200, PreviewFacade.DEFAULT_MAX_WIDTH);
            assertThrows(FacadeException.class, () -> f.preview().getPreview("E-999999"));
            assertThrows(FacadeException.class, () -> f.preview().getPreview(id, 0, 100));

            // ---- dashboard ----
            DashboardStatsCheck(f);

            // ---- export ----
            byte[] csv = ExportFacade.exportSearchResultsCsv(page.results());
            String csvText = new String(csv, StandardCharsets.UTF_8);
            assertTrue(csvText.startsWith("\ufeff"), "UTF-8 BOM for Excel");
            assertTrue(csvText.contains("file_name"));
            assertTrue(csvText.contains("invoice.txt") || csvText.contains("memo.txt"));

            byte[] json = ExportFacade.exportSearchResultsJson(page.results());
            String jsonText = new String(json, StandardCharsets.UTF_8);
            assertTrue(jsonText.startsWith("{\"count\":2"));

            byte[] xlsx = ExportFacade.exportSearchResultsExcel(page.results());
            assertEquals('P', (char) xlsx[0]);
            assertEquals('K', (char) xlsx[1]);

            byte[] settings = ExportFacade.exportSettings(Map.of("ocr", "false"));
            assertTrue(new String(settings, StandardCharsets.UTF_8).contains("\"ocr\""));

            // ---- import ----
            ImportFacade imp = f.imports();
            Map<String, byte[]> backupTables = ExportFacade.newBackupMap();
            backupTables.put("sources", "id,name\n1,Acme\n".getBytes(StandardCharsets.UTF_8));
            byte[] backup = ExportFacade.exportDatabaseBackup(backupTables);

            ImportFacade.BackupValidation v = imp.importDatabaseBackup(backup);
            assertTrue(v.valid());
            assertTrue(v.entries().contains("sources.csv"));
            // Python documents this as validate-only
            FacadeException uns = assertThrows(FacadeException.class,
                    () -> imp.importDatabaseBackup(backup, true));
            assertEquals(FacadeException.Kind.UNSUPPORTED, uns.kind());

            Map<String, String> loaded = imp.importSettings(settings);
            assertEquals("false", loaded.get("ocr"));
            assertThrows(FacadeException.class,
                    () -> imp.importSettings("not json".getBytes(StandardCharsets.UTF_8)));

            // CSV file list import drives real processing
            String csvList = "file_path\n" + evidence.resolve("invoice.txt") + "\n";
            List<ProcessingResultDto> imported = f.imports("Acme", "left")
                    .importFileListFromCsv(csvList.getBytes(StandardCharsets.UTF_8), 1, 1);
            assertEquals(1, imported.size());

            // missing required column is a validation error
            assertThrows(FacadeException.class, () -> f.imports("Acme", "left")
                    .importFileListFromCsv("name\nx\n".getBytes(StandardCharsets.UTF_8), 1, 1));
        }
    }

    private void DashboardStatsCheck(AegisFacades f) {
        var stats = f.dashboard().getStats();
        assertTrue(stats.totalFiles() >= 2);
        assertTrue(stats.totalBytes() > 0);
        assertFalse(f.dashboard().getFileTypeBreakdown().isEmpty());
        assertFalse(f.dashboard().getSourcesFiltered().isEmpty());
        assertTrue(f.dashboard().getFilesFiltered("txt", null, 10).size() >= 2);
        assertEquals(0, f.dashboard().getFilesFiltered("pdf", null, 10).size());
        assertNotNull(f.dashboard().getSimilarFiles());
    }

    // ================= existing API untouched =================

    @Test
    @DisplayName("Existing AEGIS API still reachable alongside the new facades")
    void existingApiPreserved(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            // The original interface keeps working with no facade involved.
            assertNotNull(c.folder());
            assertNotNull(c.db());
            assertNotNull(c.index());
            assertEquals(0, c.indexedCount());
            assertFalse(c.ingestRunning());

            // and both views address the same case
            AegisFacades f = AegisFacades.open(c);
            assertEquals(c, f.liveCase());

            // the facade layer holds no resources of its own: the case stays usable
            assertEquals(0, c.indexedCount());
        }
    }

    // ================= image previews =================

    /** A 1x1 transparent PNG, so the test needs no binary fixture. */
    private static final String ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    @Test
    @DisplayName("Image previews carry the native bytes plus the stored extracted text")
    void imagePreviewCarriesBytesAndText(@TempDir Path tmp) throws Exception {
        byte[] png = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64);
        Path evidence = tmp.resolve("evidence");
        Files.createDirectories(evidence);
        Files.write(evidence.resolve("photo.png"), png);

        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            List<ProcessingResultDto> results =
                    f.processing("Acme", "left").processFolder(evidence.toString());
            assertTrue(results.size() >= 1, "every discovered file yields a record");

            String id = c.allItems().stream()
                    .filter(it -> it.name() != null && it.name().endsWith("photo.png"))
                    .map(it -> it.id())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("ingested photo.png not found"));

            PreviewDto p = f.preview().getPreview(id);
            assertEquals(PreviewDto.PreviewType.IMAGE, p.previewType());
            assertNotNull(p.data(), "image previews carry the native bytes");
            assertEquals(png.length, p.data().length);
            assertNotNull(p.content(), "image previews carry the stored text");
            assertTrue(p.content().contains("photo.png"),
                    "stored text starts from the file name, got: " + p.content());
            assertFalse(p.truncated());
        }
    }

    @Test
    @DisplayName("ContentFacade serves native bytes and recognises image files")
    void nativeBytesServeRegisteredFiles(@TempDir Path tmp) throws Exception {
        byte[] png = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64);
        Path file = tmp.resolve("photo.png");
        Files.write(file, png);

        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            int srcId = f.sources().createSource("Acme", "NL", "custodian", 0.8);
            int aspectId = f.aspects().createAspect("Plaintiff", 0.9);

            int pathId = f.contents().createPath("photo.png", file.toString(), png.length,
                    "png", "Unread", LocalDate.now(), LocalDate.now(),
                    null, null, srcId, aspectId, null);
            assertArrayEquals(png, f.contents().getNativeBytes(pathId),
                    "registered bytes round-trip");

            int missing = f.contents().createPath("gone.png",
                    tmp.resolve("gone.png").toString(), 0, "png",
                    "Unread", LocalDate.now(), LocalDate.now(),
                    null, null, srcId, aspectId, null);
            assertNull(f.contents().getNativeBytes(missing),
                    "moved files yield null, not an exception");

            assertTrue(ContentFacade.isImageFile("photo.PNG"));
            assertTrue(ContentFacade.isImageFile("scan.tiff"));
            assertFalse(ContentFacade.isImageFile("report.pdf"));
            assertFalse(ContentFacade.isImageFile("noextension"));
            assertFalse(ContentFacade.isImageFile(null));
        }
    }
}
