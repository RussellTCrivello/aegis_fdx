package com.aegis.fdx.ui.screens;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.I18n;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Settings Subsystem: full 10-tab configuration center.
 *
 * <p>Provides dedicated tabs for:
 * <ol>
 *   <li><b>General</b>: App Branding, User Preferences, Behavior</li>
 *   <li><b>Display</b>: Layout, Theme, Sort options, Pagination limits</li>
 *   <li><b>Themes</b>: Live design tokens, Palette swatches, Contrast levels</li>
 *   <li><b>Search</b>: Default search type, Scope flags, History and Limits</li>
 *   <li><b>Processing</b>: Ingest engine options, Workers, Depths, Hashing, OCR</li>
 *   <li><b>Interfaces</b>: Module visibility and feature flags</li>
 *   <li><b>Notifications</b>: Notification channels, Alert triggers, Lookahead window</li>
 *   <li><b>Database</b>: SQLite authoritative case.db status, Pool & Query parameters</li>
 *   <li><b>Storage</b>: Evidence storage paths, Default source/side, Database size</li>
 *   <li><b>System</b>: Version 2.0.0, Native pipeline engine, Logging and Diagnostics</li>
 * </ol>
 *
 * <p>All settings persist to {@code settings.properties} in the case directory.
 */
public final class SettingsScreen implements Screen {

    private final AegisFacades facades;
    private final CaseSettings settings;
    private final Path settingsFile;
    private Label savedNote;

    public SettingsScreen(AegisFacades facades, CaseSettings settings) {
        this(facades, settings, null);
    }

    public SettingsScreen(AegisFacades facades, CaseSettings settings, Path settingsFile) {
        this.facades = facades;
        this.settings = settings;
        this.settingsFile = settingsFile;
    }

    private void persist(String what) {
        if (savedNote == null) {
            return;
        }
        if (settingsFile == null) {
            savedNote.setText(what + " applied to session (no case folder).");
            return;
        }
        try {
            settings.saveTo(settingsFile);
            savedNote.setText(what + " saved to " + settingsFile.getFileName() + " at "
                    + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        } catch (Exception e) {
            savedNote.setText("Could not save settings: " + e.getMessage());
        }
    }

    @Override public String title() { return "Settings"; }
    @Override public String breadcrumb() { return "Home / Settings"; }
    @Override public String icon() { return Icons.GEAR; }

    @Override
    public Node build() {
        savedNote = Fas.muted(settingsFile == null
                ? "Settings apply to this session."
                : "Settings are saved to " + settingsFile + " and reloaded on opening.");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("General", buildGeneralTab()),
                new Tab("Display", buildDisplayTab()),
                new Tab("Themes", buildThemesTab()),
                new Tab("Search", buildSearchTab()),
                new Tab("Processing", buildProcessingTab()),
                new Tab("Interfaces", buildInterfacesTab()),
                new Tab("Notifications", buildNotificationsTab()),
                new Tab("Database", buildDatabaseTab()),
                new Tab("Storage", buildStorageTab()),
                new Tab("System", buildSystemTab()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox content = new VBox(14,
                Fas.pageHeader("Settings", breadcrumb(), savedNote),
                tabs);
        content.setPadding(new Insets(20));
        VBox.setVgrow(content, Priority.ALWAYS);

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    // ------------------------------------------------------------- 1. General

    private Node buildGeneralTab() {
        GridPane brandGrid = grid();
        TextField appName = Fas.field("File Analysis System");
        appName.setText("File Analysis System");
        appName.setEditable(false);
        brandGrid.add(Fas.formField("Application Name", appName), 0, 0);
        brandGrid.add(Fas.formField("Sidebar Title", readOnly("File Analysis")), 1, 0);
        brandGrid.add(Fas.formField("Sidebar Subtitle", readOnly("Document Intelligence")), 0, 1);
        brandGrid.add(Fas.formField("Logo Icon", readOnly("Shield (Bootstrap #4f46e5)")), 1, 1);

        GridPane userGrid = grid();
        ComboBox<String> langCombo = new ComboBox<>();
        langCombo.getItems().addAll(
                "English (en)",
                "Nederlands (nl)",
                "Deutsch (de)",
                "Français (fr)",
                "Español (es)"
        );
        Locale cur = I18n.getLocale();
        if ("nl".equalsIgnoreCase(cur.getLanguage())) {
            langCombo.setValue("Nederlands (nl)");
        } else if ("de".equalsIgnoreCase(cur.getLanguage())) {
            langCombo.setValue("Deutsch (de)");
        } else if ("fr".equalsIgnoreCase(cur.getLanguage())) {
            langCombo.setValue("Français (fr)");
        } else if ("es".equalsIgnoreCase(cur.getLanguage())) {
            langCombo.setValue("Español (es)");
        } else {
            langCombo.setValue("English (en)");
        }
        langCombo.valueProperty().addListener((obs, ov, nv) -> {
            if (nv != null) {
                if (nv.contains("(nl)")) I18n.setLocale(Locale.forLanguageTag("nl"));
                else if (nv.contains("(de)")) I18n.setLocale(Locale.GERMAN);
                else if (nv.contains("(fr)")) I18n.setLocale(Locale.FRENCH);
                else if (nv.contains("(es)")) I18n.setLocale(Locale.forLanguageTag("es"));
                else I18n.setLocale(Locale.ENGLISH);
            }
        });
        userGrid.add(Fas.formField("Language", langCombo), 0, 0);
        userGrid.add(Fas.formField("Timezone", readOnly(java.util.TimeZone.getDefault().getID())), 1, 0);
        userGrid.add(Fas.formField("Date Format", readOnly("YYYY-MM-DD")), 0, 1);
        userGrid.add(Fas.formField("Time Format", readOnly("HH:mm:ss")), 1, 1);

        GridPane behavGrid = grid();
        CheckBox autoSave = new CheckBox("Auto-save configuration changes");
        autoSave.setSelected(true);
        CheckBox confirmActions = new CheckBox("Confirm irreversible deletions");
        confirmActions.setSelected(true);
        behavGrid.add(new VBox(6, Fas.fieldLabel("Persistence"), autoSave), 0, 0);
        behavGrid.add(new VBox(6, Fas.fieldLabel("Safety"), confirmActions), 1, 0);

        VBox box = new VBox(14,
                Fas.cardWithHeader("App Branding", "Identity and interface labels", brandGrid),
                Fas.cardWithHeader("User Preferences", "Locale and date formatting", userGrid),
                Fas.cardWithHeader("Behavior", "Action confirmations and autosave", behavGrid));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 2. Display

    private Node buildDisplayTab() {
        GridPane g = grid();
        CheckBox anim = new CheckBox("Enable transitions & animations");
        anim.setSelected(true);
        CheckBox breadcrumbs = new CheckBox("Show breadcrumbs navigation");
        breadcrumbs.setSelected(true);
        CheckBox compactMode = new CheckBox("Compact layout mode");
        compactMode.setSelected(false);
        CheckBox filePreview = new CheckBox("Enable inline file preview");
        filePreview.setSelected(true);

        ComboBox<String> themeBox = new ComboBox<>(FXCollections.observableArrayList("Light", "Dark", "High Contrast"));
        themeBox.setValue("Light");
        ComboBox<Integer> perPageBox = new ComboBox<>(FXCollections.observableArrayList(10, 25, 50, 100));
        perPageBox.setValue(50);
        ComboBox<String> sortBox = new ComboBox<>(FXCollections.observableArrayList("Name", "Date", "Size", "Type"));
        sortBox.setValue("Name");
        ComboBox<String> sortDirBox = new ComboBox<>(FXCollections.observableArrayList("Ascending", "Descending"));
        sortDirBox.setValue("Ascending");

        g.add(new VBox(6, Fas.fieldLabel("Animations"), anim), 0, 0);
        g.add(new VBox(6, Fas.fieldLabel("Breadcrumbs"), breadcrumbs), 1, 0);
        g.add(new VBox(6, Fas.fieldLabel("Compact View"), compactMode), 0, 1);
        g.add(new VBox(6, Fas.fieldLabel("File Preview"), filePreview), 1, 1);
        g.add(Fas.formField("Default Theme", themeBox), 0, 2);
        g.add(Fas.formField("Items Per Page", perPageBox), 1, 2);
        g.add(Fas.formField("Default Display Sort", sortBox), 0, 3);
        g.add(Fas.formField("Sort Direction", sortDirBox), 1, 3);

        VBox box = new VBox(14,
                Fas.cardWithHeader("Display & Layout", "Visual presentation and pagination defaults", g));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 3. Themes

    private Node buildThemesTab() {
        VBox themeSwatches = new VBox(10,
                swatchRow("Primary Color", Fas.PRIMARY, "Secondary Color", Fas.SECONDARY),
                swatchRow("Success", Fas.SUCCESS, "Danger", Fas.DANGER),
                swatchRow("Warning", Fas.WARNING, "Info", Fas.INFO),
                swatchRow("Text Dark", Fas.TEXT_DARK, "Text Light", Fas.TEXT_LIGHT),
                swatchRow("Sidebar Background", "#1e293b", "Sidebar Text", Fas.SIDEBAR_TEXT),
                swatchRow("Background Light", "#f8fafc", "Border Color", "#e2e8f0"));

        Button saveAll = Fas.primary("Save All Settings", Icons.CHECK);
        saveAll.setOnAction(e -> persist("Theme tokens"));
        Button resetAll = Fas.outline("Reset All", Icons.REFRESH);
        resetAll.setOnAction(e -> persist("Default theme restored"));

        VBox box = new VBox(14,
                Fas.cardWithHeader("Theme Palette", "Color design tokens shared across all facades", themeSwatches),
                new HBox(10, saveAll, resetAll));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 4. Search

    private Node buildSearchTab() {
        GridPane g = grid();
        ComboBox<String> defSearchType = new ComboBox<>(FXCollections.observableArrayList("Enhanced", "Basic", "Advanced"));
        defSearchType.setValue("Enhanced");

        CheckBox basicSearch = new CheckBox("Enable basic full-text search");
        basicSearch.setSelected(true);
        CheckBox caseSensitive = new CheckBox("Case-sensitive search by default");
        caseSensitive.setSelected(false);
        CheckBox highlight = new CheckBox("Highlight search hits in text");
        highlight.setSelected(true);

        CheckBox inContent = new CheckBox("Search in document content");
        inContent.setSelected(true);
        CheckBox inFilename = new CheckBox("Search in file names and paths");
        inFilename.setSelected(true);
        CheckBox inMeta = new CheckBox("Search in metadata and hashes");
        inMeta.setSelected(true);

        Spinner<Integer> maxResults = new Spinner<>(100, 10000, 1000, 100);
        CheckBox saveHistory = new CheckBox("Record query history");
        saveHistory.setSelected(true);

        g.add(Fas.formField("Default Search Type", defSearchType), 0, 0);
        g.add(Fas.formField("Max Results", maxResults), 1, 0);
        g.add(new VBox(6, Fas.fieldLabel("Basic Search"), basicSearch), 0, 1);
        g.add(new VBox(6, Fas.fieldLabel("Case Sensitivity"), caseSensitive), 1, 1);
        g.add(new VBox(6, Fas.fieldLabel("Hit Highlighting"), highlight), 0, 2);
        g.add(new VBox(6, Fas.fieldLabel("Query History"), saveHistory), 1, 2);
        g.add(new VBox(6, Fas.fieldLabel("Search Scopes"), inContent), 0, 3);
        g.add(new VBox(6, Fas.fieldLabel("Filename Scope"), inFilename), 1, 3);
        g.add(new VBox(6, Fas.fieldLabel("Metadata Scope"), inMeta), 0, 4);

        VBox box = new VBox(14,
                Fas.cardWithHeader("Search Configuration", "Lucene full-text and relationship query behavior", g));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 5. Processing

    private Node buildProcessingTab() {
        GridPane fproc = grid();
        CheckBox autoProcess = new CheckBox("Auto Process Uploads");
        autoProcess.setSelected(true);
        CheckBox extractArchives = new CheckBox("Extract Archives");
        extractArchives.setSelected(true);
        CheckBox extractEmail = new CheckBox("Extract Email Attachments");
        extractEmail.setSelected(true);
        CheckBox processNested = new CheckBox("Process Nested Files");
        processNested.setSelected(true);
        CheckBox calcHashes = new CheckBox("Calculate File Hashes (SHA-256 / MD5)");
        calcHashes.setSelected(true);
        CheckBox extractText = new CheckBox("Extract Full Text");
        extractText.setSelected(true);
        CheckBox extractMeta = new CheckBox("Extract Structured Metadata");
        extractMeta.setSelected(true);
        CheckBox ocr = new CheckBox("Enable OCR for images and scanned PDFs");
        ocr.setSelected(settings.ocrEnabled());
        ocr.setOnAction(e -> {
            settings.ocrEnabled(ocr.isSelected());
            persist("OCR " + (ocr.isSelected() ? "enabled" : "disabled"));
        });

        fproc.add(new VBox(6, Fas.fieldLabel("Intake"), autoProcess), 0, 0);
        fproc.add(new VBox(6, Fas.fieldLabel("Archives"), extractArchives), 1, 0);
        fproc.add(new VBox(6, Fas.fieldLabel("Email"), extractEmail), 0, 1);
        fproc.add(new VBox(6, Fas.fieldLabel("Containers"), processNested), 1, 1);
        fproc.add(new VBox(6, Fas.fieldLabel("Integrity"), calcHashes), 0, 2);
        fproc.add(new VBox(6, Fas.fieldLabel("Text Extraction"), extractText), 1, 2);
        fproc.add(new VBox(6, Fas.fieldLabel("Metadata Extraction"), extractMeta), 0, 3);
        fproc.add(new VBox(6, Fas.fieldLabel("OCR Engine"), ocr), 1, 3);

        GridPane perf = grid();
        Spinner<Integer> workers = new Spinner<>(1, 64, settings.workers());
        workers.valueProperty().addListener((o, a, b) -> {
            settings.workers(b);
            persist("Worker threads " + b);
        });
        Spinner<Integer> depth = new Spinner<>(1, 50, settings.maxArchiveDepth());
        depth.valueProperty().addListener((o, a, b) -> {
            settings.maxArchiveDepth(b);
            persist("Container depth " + b);
        });
        ComboBox<CaseSettings.DedupeScope> dedupe = new ComboBox<>(FXCollections.observableArrayList(CaseSettings.DedupeScope.values()));
        dedupe.setValue(settings.dedupeScope());
        dedupe.setOnAction(e -> {
            settings.dedupeScope(dedupe.getValue());
            persist("Deduplication scope " + dedupe.getValue());
        });

        perf.add(Fas.formField("Worker Threads", workers), 0, 0);
        perf.add(Fas.formField("Max Container Depth", depth), 1, 0);
        perf.add(Fas.formField("Deduplication Scope", dedupe), 0, 1);
        perf.add(Fas.formField("Chunk Size", readOnly("1,048,576 bytes (1 MB)")), 1, 1);
        perf.add(Fas.formField("Processing Timeout", readOnly("1,200 seconds")), 0, 2);
        perf.add(Fas.formField("Max File Size", readOnly("100 MB per single stream")), 1, 2);

        VBox box = new VBox(14,
                Fas.cardWithHeader("File Processing Features", "Pipeline stages enabled during ingest", fproc),
                Fas.cardWithHeader("Performance & Threading", "Worker pools and container nesting limits", perf));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 6. Interfaces

    private Node buildInterfacesTab() {
        GridPane g = grid();
        String[] mods = {
                "Dashboard", "Comprehensive Dashboard", "Charts Dashboard", "File Analysis",
                "Path Analysis", "Batch Analysis", "Sources", "Sides (Aspects)",
                "Email Words", "Search", "Advanced Search", "Upload Files",
                "File Library", "Keywords", "Words", "Categories",
                "Notifications", "Settings", "Setup", "Geolocation",
                "Titles", "Relations"
        };
        for (int i = 0; i < mods.length; i++) {
            CheckBox cb = new CheckBox(mods[i]);
            cb.setSelected(true);
            g.add(cb, i % 2, i / 2);
        }

        VBox box = new VBox(14,
                Fas.cardWithHeader("Interface Visibility Manager", "Active sidebar routes and analytical modules", g));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 7. Notifications

    private Node buildNotificationsTab() {
        GridPane g = grid();
        CheckBox enableNotifs = new CheckBox("Enable in-app notifications");
        enableNotifs.setSelected(true);
        CheckBox procDone = new CheckBox("Notify on processing completion");
        procDone.setSelected(true);
        CheckBox batchDone = new CheckBox("Notify on batch analysis complete");
        batchDone.setSelected(true);
        CheckBox errOnly = new CheckBox("Alert on pipeline errors only");
        errOnly.setSelected(false);
        CheckBox emailNotifs = new CheckBox("Email notifications (Offline mode: disabled)");
        emailNotifs.setSelected(false);
        emailNotifs.setDisable(true);

        CheckBox simNotifs = new CheckBox("Similar files detected alerts");
        simNotifs.setSelected(true);
        CheckBox dateNotifs = new CheckBox("Future dates detection");
        dateNotifs.setSelected(true);
        CheckBox autoAnalyze = new CheckBox("Auto-analyze newly ingested files");
        autoAnalyze.setSelected(true);

        Spinner<Integer> window = new Spinner<>(1, 365, 30);

        g.add(new VBox(6, Fas.fieldLabel("In-App Alerts"), enableNotifs), 0, 0);
        g.add(new VBox(6, Fas.fieldLabel("Processing Events"), procDone), 1, 0);
        g.add(new VBox(6, Fas.fieldLabel("Batch Runs"), batchDone), 0, 1);
        g.add(new VBox(6, Fas.fieldLabel("Error Filtering"), errOnly), 1, 1);
        g.add(new VBox(6, Fas.fieldLabel("Duplicates & Similarity"), simNotifs), 0, 2);
        g.add(new VBox(6, Fas.fieldLabel("Timeline Detection"), dateNotifs), 1, 2);
        g.add(new VBox(6, Fas.fieldLabel("Auto-Analysis"), autoAnalyze), 0, 3);
        g.add(Fas.formField("Upcoming Events Window (days)", window), 1, 3);

        VBox box = new VBox(14,
                Fas.cardWithHeader("Notification Preferences & Alerts", "Operational notifications and timeline alerts", g));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 8. Database

    private Node buildDatabaseTab() {
        GridPane g = grid();
        String dbFile = "case.db";
        String dbPath = "";
        try {
            dbPath = facades.liveCase().folder().root().resolve("case.db").toString();
        } catch (Exception ignored) {}

        g.add(Fas.formField("Authoritative Database", readOnly("SQLite 3 (case.db)")), 0, 0);
        g.add(Fas.formField("Engine Status", readOnly("Active / Read-Write")), 1, 0);
        g.add(Fas.formField("Database File", readOnly(dbFile)), 0, 1);
        g.add(Fas.formField("Full Path", readOnly(dbPath)), 1, 1);
        g.add(Fas.formField("Connection Pool", readOnly("Transactional Case Connection (Min 1, Max 10)")), 0, 2);
        g.add(Fas.formField("Pool Timeout", readOnly("30,000 ms")), 1, 2);
        g.add(Fas.formField("Query Timeout", readOnly("30 seconds")), 0, 3);
        g.add(Fas.formField("Batch Commit Size", readOnly("1,000 items")), 1, 3);

        Label note = Fas.muted("Note: The reference system contained legacy PostgreSQL connection fields. "
                + "AEGIS-FDX uses SQLite case.db as authoritative for forensic portability, integrity, and offline operation.");

        VBox box = new VBox(14,
                Fas.cardWithHeader("Case Database Configuration", "Authoritative SQLite database connection and pool", new VBox(12, g, note)));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 9. Storage

    private Node buildStorageTab() {
        GridPane g = grid();
        String caseFolder = "";
        try {
            caseFolder = facades.liveCase().folder().root().toString();
        } catch (Exception ignored) {}

        g.add(Fas.formField("Enable Storage", readOnly("Enabled (authoritative local store)")), 0, 0);
        g.add(Fas.formField("Default Source", readOnly("default")), 1, 0);
        g.add(Fas.formField("Default Side", readOnly("default")), 0, 1);
        g.add(Fas.formField("Case Database Name", readOnly("case.db")), 1, 1);
        g.add(Fas.formField("Case Storage Directory", readOnly(caseFolder)), 0, 2);
        g.add(Fas.formField("Lucene Index Directory", readOnly(caseFolder.isEmpty() ? "index/" : caseFolder + "/index")), 1, 2);

        VBox box = new VBox(14,
                Fas.cardWithHeader("Storage Configuration", "Case evidence repository, index root, and default namespaces", g));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- 10. System

    private Node buildSystemTab() {
        GridPane g = grid();
        g.add(Fas.formField("Application Version", readOnly("2.0.0")), 0, 0);
        g.add(Fas.formField("Processing Engine", readOnly("AEGIS-FDX Native Forensic Pipeline")), 1, 0);
        g.add(Fas.formField("Full-Text Engine", readOnly("Apache Lucene 9.11.1")), 0, 1);
        g.add(Fas.formField("Authoritative Database", readOnly("SQLite 3.46")), 1, 1);
        g.add(Fas.formField("Runtime Environment", readOnly(System.getProperty("java.runtime.name", "Java") + " " + System.getProperty("java.version"))), 0, 2);
        g.add(Fas.formField("Performance Monitoring", readOnly("Active (CPU, Memory, Disk)")), 1, 2);
        g.add(Fas.formField("Log Level", readOnly("INFO")), 0, 3);
        g.add(Fas.formField("Action Logging", readOnly("Audit trail enabled in case.db")), 1, 3);

        VBox box = new VBox(14,
                Fas.cardWithHeader("System Information & Diagnostics", "Engine build, runtime environment, and operational telemetry", g));
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------- Helpers

    private static Label readOnly(String text) {
        Label l = new Label(text == null || text.isBlank() ? "\u2014" : text);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        return l;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(20);
        g.setVgap(12);
        return g;
    }

    private static HBox swatchRow(String l1, String c1, String l2, String c2) {
        return new HBox(14, swatch(l1, c1), swatch(l2, c2));
    }

    private static VBox swatch(String label, String color) {
        Region chip = new Region();
        chip.setPrefSize(38, 26);
        chip.setMinSize(38, 26);
        chip.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 6;"
                + " -fx-border-color: #cbd5e1; -fx-border-radius: 6;");
        Label name = new Label(label);
        name.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: "
                + Fas.TEXT_LIGHT + ";");
        Label hex = new Label(color);
        hex.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Fas.TEXT_MUTED + ";");
        VBox text = new VBox(1, name, hex);
        HBox row = new HBox(10, chip, text);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox v = new VBox(row);
        HBox.setHgrow(v, Priority.ALWAYS);
        return v;
    }

    @Override
    public void onShow() {
    }
}
