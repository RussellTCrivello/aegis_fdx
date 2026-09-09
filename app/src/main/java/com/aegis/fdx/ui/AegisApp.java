package com.aegis.fdx.ui;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.CaseStore;
import com.aegis.fdx.engine.EngineEvent;
import com.aegis.fdx.engine.Filters;
import com.aegis.fdx.engine.SearchHit;
import com.aegis.fdx.engine.IngestPipeline;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.model.Tag;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AEGIS-FDX — reviewer workstation prototype (F-13 … F-27).
 *
 * <p>Layout: title bar · left case/facet rail · centre search + result grid ·
 * right preview/metadata/tags · bottom ingest monitor + status bar. The UI never
 * touches the filesystem or the engine's internals; it consumes
 * {@link EngineEvent} on the FX thread and asks {@link CaseStore} for results.
 */
public class AegisApp extends Application {

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final CaseSettings settings = new CaseSettings();
    private final java.util.List<String> savedSearches = new java.util.ArrayList<>();
    private LiveCase liveCase;
    private String caseName = "Northwind-2024-017";
    private final Filters filters = new Filters();
    private final ObservableList<SearchHit> results = FXCollections.observableArrayList();
    private final ObservableList<String> logLines = FXCollections.observableArrayList();

    // widgets referenced across builders
    private TextField queryField;
    private Label resultCountLabel, statusLeft, statusMid, statusRight, ingestLabel, throughputLabel;
    private ProgressBar ingestBar;
    private TableView<SearchHit> table;
    private VBox previewBody, metaBody, tagBody;
    private Label previewTitle;
    private TextArea notesArea;
    private Button startBtn, pauseBtn, stopBtn;
    private ListView<String> logView;
    private TreeView<String> facetTree;
    private Label uiLatencyLabel;
    private CheckBox includeHidden;
    private ComboBox<String> savedSearchBox;
    private ComboBox<String> sortBox;
    private String lastCustodian = "Unassigned";
    /** Guards against a slow search overwriting a newer one (result race). */
    private final java.util.concurrent.atomic.AtomicLong searchGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private Item current;
    private long lastInteraction = System.nanoTime();

    @Override
    public void start(Stage stage) {
        try {
            java.nio.file.Path root = java.nio.file.Path.of(
                    System.getProperty("aegis.caseDir",
                            System.getProperty("user.home") + "/aegis-cases/" + caseName));
            liveCase = new LiveCase(root, caseName, settings);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open case folder", e);
        }

        BorderPane root = new BorderPane();
        root.setTop(buildTitleBar());
        root.setCenter(buildBody());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1680, 1000);
        scene.getStylesheets().add(getClass().getResource("aegis.css").toExternalForm());
        installDragAndDrop(scene, root);
        installShortcuts(scene);

        stage.setTitle("AEGIS-FDX — Forensic Discovery Workstation");
        stage.setScene(scene);
        stage.show();

        seedDemo();
    }

    @Override
    public void stop() {
        if (liveCase != null) liveCase.close();
    }

    // =====================================================================
    // Title bar
    // =====================================================================

    private Node buildTitleBar() {
        Label mark = new Label("AEGIS");
        mark.getStyleClass().add("app-mark");
        Label mark2 = new Label("·FDX");
        mark2.getStyleClass().addAll("app-mark", "app-mark-sub");

        HBox brand = new HBox(UiParts.iconBox(UiParts.I_SHIELD, "#4f8ef7", 18), mark, mark2);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setSpacing(2);
        HBox.setMargin(mark, new Insets(0, 0, 0, 8));

        Label caseChip = new Label("Case: " + caseName);
        caseChip.getStyleClass().add("case-chip");

        Label ro = new Label("SOURCE READ-ONLY");
        ro.getStyleClass().add("readonly-chip");

        Button newCase = ghost("New case", UiParts.I_CASE);
        Button ingest = ghost("Add evidence", UiParts.I_ARCHIVE);
        Button reports = ghost("Reports", UiParts.I_CHART);
        Button export = ghost("Export", UiParts.I_EXPORT);
        Button settings = ghost("Settings", UiParts.I_GEAR);

        newCase.setOnAction(e -> info("New case", "Creates a self-contained case folder:\n"
                + "/data  /index  /text  /db  /logs  /exports  case.json\n\n"
                + "Moving or archiving the case is a plain folder copy (F-28)."));
        ingest.setOnAction(e -> showIngestDialog());
        reports.setOnAction(e -> showReports());
        Button verify = ghost("Verify", UiParts.I_SHIELD);
        verify.setTooltip(new Tooltip(
                "Re-hash every original and compare against the ingest baseline (F-06)"));
        verify.setOnAction(e -> showIntegrityCheck());
        export.setOnAction(e -> showExportDialog());
        settings.setOnAction(e -> showSettings());

        HBox bar = new HBox(brand, gap(18), caseChip, ro, UiParts.hSpacer(),
                newCase, ingest, reports, verify, export, settings);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setSpacing(6);
        bar.setPrefHeight(46);
        bar.getStyleClass().add("titlebar");
        return bar;
    }

    // =====================================================================
    // Body
    // =====================================================================

    private Node buildBody() {
        SplitPane outer = new SplitPane();
        outer.getItems().addAll(buildLeftRail(), buildCentre(), buildRightPanel());
        outer.setDividerPositions(0.17, 0.65);
        SplitPane.setResizableWithParent(outer.getItems().get(0), false);
        SplitPane.setResizableWithParent(outer.getItems().get(2), false);
        return outer;
    }

    // ---- left rail: cases + facets -------------------------------------

    private Node buildLeftRail() {
        VBox box = new VBox();
        box.setStyle("-fx-background-color: -bg-1; -fx-border-color: transparent -line transparent transparent; -fx-border-width: 0 1 0 0;");

        box.getChildren().add(header("Cases", UiParts.I_CASE));

        ListView<String> cases = new ListView<>(FXCollections.observableArrayList(
                caseName, "Halberd-2023-204", "Kestrel-2024-006 (archived)"));
        cases.getSelectionModel().selectFirst();
        cases.setPrefHeight(90);
        cases.setFixedCellSize(24);
        box.getChildren().add(cases);

        box.getChildren().add(header("Filters", UiParts.I_LIST));

        facetTree = new TreeView<>(buildFacetRoot());
        facetTree.setShowRoot(false);
        VBox.setVgrow(facetTree, Priority.ALWAYS);
        facetTree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b.isLeaf()) applyFacet(b);
        });
        box.getChildren().add(facetTree);

        Button clear = new Button("Clear all filters");
        clear.getStyleClass().add("ghost");
        clear.setMaxWidth(Double.MAX_VALUE);
        clear.setOnAction(e -> { filters.clear(); runSearch(); });
        VBox.setMargin(clear, new Insets(6));
        box.getChildren().add(clear);

        return box;
    }

    private TreeItem<String> buildFacetRoot() {
        TreeItem<String> root = new TreeItem<>("root");
        root.getChildren().addAll(List.of(
                facetGroup("File type", "pdf", "docx", "xlsx", "pptx", "msg", "eml",
                        "jpg", "tiff", "txt", "csv", "zip", "pst", "mp4", "dwg"),
                facetGroup("Custodian", "A. Farouk", "M. Weber", "L. Nguyen", "S. Okafor", "J. Kowalski"),
                facetGroup("Status", "Pending", "Processing", "Indexed", "Error", "Locked", "Unsupported"),
                facetGroup("Tag", "Responsive", "Featured", "Trending", "Needs Review", "Hidden"),
                facetGroup("Attachments", "Has attachments", "No attachments"),
                facetGroup("Date", "2024-Q1", "2024-Q2", "2024-Q3", "2024-Q4")));
        return root;
    }

    private TreeItem<String> facetGroup(String name, String... children) {
        TreeItem<String> g = new TreeItem<>(name);
        for (String c : children) g.getChildren().add(new TreeItem<>(c));
        g.setExpanded("File type".equals(name) || "Tag".equals(name));
        return g;
    }

    private void applyFacet(TreeItem<String> leaf) {
        String group = leaf.getParent() == null ? "" : leaf.getParent().getValue();
        String v = leaf.getValue();
        switch (group) {
            case "File type" -> toggle(filters.types, v);
            case "Custodian" -> toggle(filters.custodians, v);
            case "Tag" -> toggle(filters.tags, v);
            case "Status" -> {
                ItemStatus s = ItemStatus.valueOf(v.toUpperCase().replace(" ", "_"));
                if (!filters.statuses.remove(s)) filters.statuses.add(s);
            }
            case "Attachments" -> filters.hasAttachments =
                    "Has attachments".equals(v) ? Boolean.TRUE : Boolean.FALSE;
            case "Date" -> {
                int q = Integer.parseInt(v.substring(v.length() - 1));
                filters.dateFrom = Instant.parse(String.format("2024-%02d-01T00:00:00Z", (q - 1) * 3 + 1));
                filters.dateTo = filters.dateFrom.plusSeconds(90L * 86400);
            }
            default -> { }
        }
        runSearch();
    }

    private static void toggle(java.util.Set<String> set, String v) {
        if (!set.remove(v)) set.add(v);
    }

    // ---- centre: query + results ---------------------------------------

    private Node buildCentre() {
        VBox box = new VBox();
        box.setStyle("-fx-background-color: -bg-1;");

        // query bar
        queryField = new TextField();
        queryField.getStyleClass().add("search-field");
        queryField.setPromptText("settlement AND from:*@northwind* NOT tag:Hidden   ·   \"wire transfer\"~5   ·   /INV-\\d{5}/   ·   date:[2024-01-01 TO 2024-06-30]");
        HBox.setHgrow(queryField, Priority.ALWAYS);
        queryField.setOnAction(e -> runSearch());

        Button run = new Button("Search");
        run.getStyleClass().add("primary");
        run.setOnAction(e -> runSearch());

        Button save = ghost("Save", UiParts.I_TAG);
        save.setOnAction(e -> {
            String q = queryField.getText().trim();
            if (!q.isEmpty() && !savedSearches.contains(q)) {
                savedSearches.add(q);
                savedSearchBox.getItems().setAll(savedSearches);
                audit("SAVE_SEARCH", q);
                toast("Search saved");
            }
        });

        savedSearchBox = new ComboBox<>();
        savedSearchBox.setPromptText("Saved searches");
        savedSearchBox.setPrefWidth(190);
        savedSearchBox.setOnAction(e -> {
            String s = savedSearchBox.getValue();
            if (s != null) { queryField.setText(s); runSearch(); }
        });

        includeHidden = new CheckBox("Include hidden");
        includeHidden.setOnAction(e -> runSearch());

        HBox qbar = new HBox(8, queryField, run, save, savedSearchBox, includeHidden);
        qbar.setAlignment(Pos.CENTER_LEFT);
        qbar.setPadding(new Insets(10, 12, 8, 12));

        // syntax hints
        HBox hints = new HBox(6);
        hints.setPadding(new Insets(0, 12, 8, 12));
        for (String[] h : new String[][]{
                {"phrase", "\"board briefing\""},
                {"wildcard", "settle*"},
                {"fuzzy", "setlement~2"},
                {"proximity", "\"wire funds\"~5"},
                {"field", "subject:settlement"},
                {"regex", "/INVOICE\\s\\d{5}/"}}) {
            Hyperlink hl = new Hyperlink(h[0]);
            hl.setStyle("-fx-text-fill: -txt-2; -fx-font-size: 10.5px; -fx-padding: 0 4 0 0;");
            hl.setOnAction(e -> { queryField.setText(h[1]); runSearch(); });
            hints.getChildren().add(hl);
        }
        hints.getChildren().add(0, UiParts.dim("Try:"));
        hints.setAlignment(Pos.CENTER_LEFT);

        // result toolbar
        resultCountLabel = new Label("No search executed");
        resultCountLabel.getStyleClass().add("muted");

        sortBox = new ComboBox<>(FXCollections.observableArrayList(
                "Relevance", "Date ↓", "Date ↑", "Name", "Size ↓"));
        sortBox.setValue("Relevance");
        sortBox.setOnAction(e -> runSearch());

        TextField within = new TextField();
        within.setPromptText("Search within results");
        within.setPrefWidth(180);
        within.textProperty().addListener((o, a, b) -> searchWithin(b));

        Button csv = ghost("Export list (CSV)", UiParts.I_EXPORT);
        csv.setOnAction(e -> showExportDialog());

        HBox rbar = new HBox(8, resultCountLabel, UiParts.hSpacer(),
                within, UiParts.muted("Sort"), sortBox, csv);
        rbar.setAlignment(Pos.CENTER_LEFT);
        rbar.setPadding(new Insets(6, 12, 6, 12));
        rbar.setStyle("-fx-background-color: -bg-2; -fx-border-color: -line transparent -line transparent; -fx-border-width: 1 0 1 0;");

        table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        box.getChildren().addAll(qbar, hints, rbar, table, buildIngestMonitor());
        return box;
    }

    private TableView<SearchHit> buildTable() {
        TableView<SearchHit> t = new TableView<>(results);
        t.setPlaceholder(new Label("Run a query, or click “Add evidence” to ingest a folder."));
        t.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        t.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) showItem(b.item());
        });

        TableColumn<SearchHit, SearchHit> cName = new TableColumn<>("Name");
        cName.setPrefWidth(250);
        cName.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cName.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(SearchHit h, boolean empty) {
                super.updateItem(h, empty);
                if (empty || h == null) { setGraphic(null); setText(null); return; }
                Item it = h.item();
                Label name = new Label(it.name());
                HBox row = new HBox(6, UiParts.iconBox(UiParts.iconFor(it.extension()), "#7f8ba4", 13), name);
                if (it.depth() > 0) {
                    Label d = new Label("L" + it.depth());
                    d.getStyleClass().addAll("dim", "mono");
                    row.getChildren().add(d);
                }
                if (it.hasAttachments()) {
                    row.getChildren().add(UiParts.iconBox(UiParts.I_LINK, "#f59e0b", 12));
                }
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });

        TableColumn<SearchHit, String> cType = col("Type", 62, h -> h.item().extension().toUpperCase());
        TableColumn<SearchHit, String> cCust = col("Custodian", 96, h -> nz(h.item().custodian()));
        TableColumn<SearchHit, String> cDate = col("Modified", 120,
                h -> h.item().modified() == null ? "" : DT.format(h.item().modified()));
        TableColumn<SearchHit, String> cSize = col("Size", 74, h -> human(h.item().size()));
        TableColumn<SearchHit, String> cHits = col("Hits", 50, h -> String.valueOf(h.hitCount()));

        TableColumn<SearchHit, SearchHit> cStatus = new TableColumn<>("Status");
        cStatus.setPrefWidth(96);
        cStatus.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cStatus.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(SearchHit h, boolean empty) {
                super.updateItem(h, empty);
                setGraphic(empty || h == null ? null : UiParts.statusPill(h.item().status()));
                setText(null);
            }
        });

        TableColumn<SearchHit, SearchHit> cTags = new TableColumn<>("Tags");
        cTags.setPrefWidth(140);
        cTags.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cTags.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(SearchHit h, boolean empty) {
                super.updateItem(h, empty);
                if (empty || h == null) { setGraphic(null); return; }
                HBox box = new HBox(3);
                for (String tg : h.item().tags()) box.getChildren().add(UiParts.tagChip(tg, colorOf(tg)));
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
                setText(null);
            }
        });

        TableColumn<SearchHit, String> cPath = col("Path", 250,
                h -> h.item().containerPath() != null ? h.item().containerPath() : nz(h.item().sourcePath()));

        t.getColumns().addAll(List.of(cName, cType, cCust, cDate, cSize, cHits, cStatus, cTags, cPath));
        t.setContextMenu(buildRowMenu());
        return t;
    }

    private ContextMenu buildRowMenu() {
        ContextMenu m = new ContextMenu();
        for (Tag tg : Tag.FIXED) {
            MenuItem mi = new MenuItem("Tag: " + tg.name() + "   " + nz(tg.shortcut()));
            mi.setOnAction(e -> applyTagToSelection(tg.name()));
            m.getItems().add(mi);
        }
        m.getItems().add(new SeparatorMenuItem());
        MenuItem thread = new MenuItem("Show email thread");
        thread.setOnAction(e -> showThreads());
        MenuItem dupes = new MenuItem("Show duplicates of this item");
        dupes.setOnAction(e -> showDuplicates());
        MenuItem exp = new MenuItem("Export selected…");
        exp.setOnAction(e -> showExportDialog());
        m.getItems().addAll(thread, dupes, exp);
        return m;
    }

    // ---- ingest monitor -------------------------------------------------

    private Node buildIngestMonitor() {
        ingestBar = new ProgressBar(0);
        ingestBar.setPrefWidth(260);
        ingestLabel = new Label("Idle");
        ingestLabel.getStyleClass().add("muted");
        throughputLabel = new Label("");
        throughputLabel.getStyleClass().addAll("dim", "mono");

        startBtn = new Button("Start ingest");
        startBtn.getStyleClass().add("primary");
        startBtn.setOnAction(e -> showIngestDialog());

        pauseBtn = ghost("Pause", UiParts.I_PAUSE);
        pauseBtn.setDisable(true);
        pauseBtn.setOnAction(e -> {
            if (pauseBtn.getText().equals("Pause")) liveCase.pauseIngest(); else liveCase.resumeIngest();
        });

        stopBtn = ghost("Cancel", UiParts.I_STOP);
        stopBtn.setDisable(true);
        stopBtn.getStyleClass().add("danger");
        stopBtn.setOnAction(e -> liveCase.cancelIngest());

        for (Button b : List.of(startBtn, pauseBtn, stopBtn)) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        ingestLabel.setMinWidth(70);
        ingestBar.setMinWidth(160);
        throughputLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox controls = new HBox(8, ingestLabel, ingestBar, throughputLabel,
                UiParts.hSpacer(), startBtn, pauseBtn, stopBtn);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(6, 12, 6, 12));

        logView = new ListView<>(logLines);
        logView.setPrefHeight(112);
        logView.setFixedCellSize(15);
        logView.getStyleClass().add("log-list");
        logView.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty ? null : s);
                getStyleClass().add("log-line");
                if (s == null) { setStyle(""); return; }
                if (s.contains(" ERROR ")) setStyle("-fx-text-fill: #ff8080;");
                else if (s.contains(" WARN ")) setStyle("-fx-text-fill: #f5b74b;");
                else setStyle("-fx-text-fill: #8a93a8;");
            }
        });

        TitledPane tp = new TitledPane();
        tp.setText("Ingest pipeline  ·  Intake → Extract → Hash/Dedupe → Index → Store");
        tp.setContent(new VBox(controls, logView));
        tp.setExpanded(true);
        tp.setStyle("-fx-text-fill: -txt-1;");
        return tp;
    }

    // ---- right: preview / metadata / tags -------------------------------

    private Node buildRightPanel() {
        previewTitle = new Label("No element selected");
        previewTitle.setStyle("-fx-font-weight: bold;");

        previewBody = new VBox(6);
        previewBody.setPadding(new Insets(10));
        ScrollPane previewScroll = new ScrollPane(previewBody);
        previewScroll.setFitToWidth(true);

        metaBody = new VBox(3);
        metaBody.setPadding(new Insets(10));
        ScrollPane metaScroll = new ScrollPane(metaBody);
        metaScroll.setFitToWidth(true);

        tagBody = new VBox(8);
        tagBody.setPadding(new Insets(10));

        notesArea = new TextArea();
        notesArea.setPromptText("Free-text note for this element (indexed — F-21)");
        notesArea.setPrefRowCount(5);
        notesArea.focusedProperty().addListener((o, was, isNow) -> {
            if (!isNow && current != null && !notesArea.getText().equals(current.notes())) {
                try {
                    liveCase.setNotes(current, notesArea.getText(), "analyst");   // F-21
                } catch (Exception ex) {
                    log("ERROR", "Note save failed: " + ex.getMessage());
                }
            }
        });

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Preview", previewScroll),
                new Tab("Metadata", metaScroll),
                new Tab("Tags & notes", new VBox(tagBody, new Separator(),
                        pad(new VBox(4, UiParts.sectionTitle("Notes"), notesArea), 10))),
                new Tab("Audit", buildAuditView()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox box = new VBox(header("Element", UiParts.I_DOC), pad(previewTitle, 10), tabs);
        box.setStyle("-fx-background-color: -bg-1; -fx-border-color: transparent transparent transparent -line; -fx-border-width: 0 0 0 1;");
        return box;
    }

    private Node buildAuditView() {
        ListView<String> lv = new ListView<>();
        lv.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(String s, boolean e) {
                super.updateItem(s, e);
                setText(e ? null : s);
                getStyleClass().add("log-line");
            }
        });
        // refresh on tab selection is cheap enough to do on a timer-free listener
        lv.setOnMouseEntered(e -> {
            List<String> rows = new ArrayList<>();
            try {
                for (CaseDatabase.AuditRow a : liveCase.db().auditLog(500)) {
                    rows.add(DT.format(a.when()) + "  " + nz(a.user()) + "  "
                            + a.action() + "  " + nz(a.detail()));
                }
            } catch (Exception ex) {
                rows.add("audit read failed: " + ex.getMessage());
            }
            lv.getItems().setAll(rows);
        });
        return lv;
    }

    // =====================================================================
    // Status bar
    // =====================================================================

    private Node buildStatusBar() {
        statusLeft = new Label("Ready");
        statusMid = new Label("0 elements in case");
        statusRight = new Label("Offline · no telemetry · AES-256 case folder");
        uiLatencyLabel = new Label("UI 0 ms");
        uiLatencyLabel.getStyleClass().add("mono");

        HBox bar = new HBox(16, statusLeft, new Separator(javafx.geometry.Orientation.VERTICAL),
                statusMid, UiParts.hSpacer(), uiLatencyLabel,
                new Separator(javafx.geometry.Orientation.VERTICAL), statusRight);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("statusbar");
        return bar;
    }

    // =====================================================================
    // Behaviour
    // =====================================================================

    private void installShortcuts(Scene scene) {
        for (int i = 0; i < Tag.FIXED.size(); i++) {
            final String name = Tag.FIXED.get(i).name();
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.valueOf("DIGIT" + (i + 1)), KeyCombination.CONTROL_DOWN),
                    () -> applyTagToSelection(name));
        }
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
                () -> queryField.requestFocus());
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.E, KeyCombination.CONTROL_DOWN),
                this::showExportDialog);
    }

    private void seedDemo() {
        log("INFO", "Case opened: " + liveCase.folder().root()
                + "  (Lucene 9.11 index, SQLite/WAL database)");
        log("INFO", "Workers=" + settings.workers() + " · index buffer="
                + settings.indexMemoryMb() + " MB · stream threshold="
                + settings.streamThresholdMb() + " MB · max depth=" + settings.maxArchiveDepth());
        try {
            int n = liveCase.indexedCount();
            log("INFO", n + " elements already indexed in this case");
            statusMid.setText(String.format("%,d elements in case", n));
        } catch (Exception e) {
            log("WARN", "Index read failed: " + e.getMessage());
        }
        String auto = System.getProperty("aegis.autoIngest");
        if (auto != null && !auto.isBlank()) {
            startRealIngest(auto, "A. Farouk");
        } else {
            runSearch();
        }
    }

    private void onEngineEvent(EngineEvent ev) {
        long t0 = System.nanoTime();
        switch (ev) {
            case EngineEvent.Started s -> {
                startBtn.setDisable(true);
                pauseBtn.setDisable(false);
                stopBtn.setDisable(false);
                ingestLabel.setText("Ingesting…");
                statusLeft.setText("Ingest running");
            }
            case EngineEvent.ItemIndexed e -> {
                ingestBar.setProgress(e.total() == 0 ? 0 : (double) e.done() / e.total());
                statusMid.setText(String.format("%,d elements in case", e.done()));
                // F-13: refresh the live view only while the query box is empty,
                // otherwise the user's own search would be overwritten mid-ingest.
                if (e.done() % 20 == 0 && isBlank(queryField.getText())) runSearchQuiet();
            }
            case EngineEvent.Progress p -> {
                throughputLabel.setText(String.format(
                        "%,d/%,d · %.0f items/s (%,.0f/h) · CPU %.0f%% · heap %d MB · %d workers",
                        p.done(), p.total(), p.itemsPerSecond(), p.itemsPerSecond() * 3600,
                        p.cpuLoad() * 100, p.heapUsedMb(), p.activeWorkers()));
            }
            case EngineEvent.Log l -> log(l.level(), l.message());
            case EngineEvent.Failed f -> log("ERROR", f.itemName() + " — " + f.reason());
            case EngineEvent.Paused p -> { pauseBtn.setText("Resume"); ingestLabel.setText("Paused"); }
            case EngineEvent.Resumed r -> { pauseBtn.setText("Pause"); ingestLabel.setText("Ingesting…"); }
            case EngineEvent.Finished f -> {
                startBtn.setDisable(false);
                pauseBtn.setDisable(true);
                stopBtn.setDisable(true);
                pauseBtn.setText("Pause");
                ingestBar.setProgress(1);
                ingestLabel.setText("Complete");
                statusLeft.setText("Idle");
                log("INFO", String.format(
                        "Ingest finished: %,d indexed · %,d errors · %,d locked · %,d unsupported · %.1fs",
                        f.indexed(), f.errors(), f.locked(), f.unsupported(), f.durationMillis() / 1000.0));
                runSearch();
            }
        }
        uiLatencyLabel.setText(String.format("UI %.1f ms", (System.nanoTime() - t0) / 1e6));
    }

    private void runSearch() { runSearch(false); }

    /**
     * F-13/N-03: the query runs on the search thread; only the assembled page
     * touches the FX thread, so a slow query can never freeze the UI.
     */
    private void runSearch(boolean quiet) {
        final String q = queryField.getText();
        final boolean hidden = includeHidden.isSelected();
        final String sortMode = sortBox == null ? "Relevance" : sortBox.getValue();
        final long t0 = System.nanoTime();
        final long gen = searchGeneration.incrementAndGet();

        new Thread(() -> {
            try {
                LiveCase.SearchPage page =
                        liveCase.searchNow(q, filters, hidden, sortMode, 1000);
                if (gen != searchGeneration.get()) return;    // superseded, discard
                Platform.runLater(() -> {
                    if (gen != searchGeneration.get()) return;
                    results.setAll(page.hits());
                    double ms = (System.nanoTime() - t0) / 1e6;
                    resultCountLabel.setText(quiet
                            ? String.format("%,d results  ·  live (index updating)", page.totalHits())
                            : String.format("%,d results in %.0f ms%s", page.totalHits(), ms,
                                    filters.isEmpty() ? "" : "  ·  filters active"));
                    table.setPlaceholder(new Label(page.hits().isEmpty() && !isBlank(q)
                            ? "No elements match this query."
                            : "Run a query, or click \u201cAdd evidence\u201d to ingest a folder."));
                    if (!page.hits().isEmpty() && table.getSelectionModel().isEmpty()) {
                        table.getSelectionModel().selectFirst();
                    }
                });
                if (!quiet && q != null && !q.isBlank()) {
                    liveCase.auditSearch(q, page.totalHits(), "analyst");
                }
            } catch (com.aegis.fdx.index.QuerySyntaxException qse) {
                // A rejected query is user error, not a system failure: show the reason
                // inline and clear stale results so nothing is mistaken for a hit set.
                if (gen != searchGeneration.get()) return;
                Platform.runLater(() -> {
                    if (gen != searchGeneration.get()) return;
                    results.clear();
                    resultCountLabel.setText("Invalid query");
                    Label msg = new Label(qse.displayMessage());
                    msg.setWrapText(true);
                    msg.setStyle("-fx-text-fill: #ff8a80; -fx-padding: 12;");
                    table.setPlaceholder(msg);
                    log("WARN", "Query rejected: " + qse.displayMessage());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> log("ERROR", "Search failed: " + ex.getMessage()));
            }
        }, "aegis-ui-search").start();
    }

    private void runSearchQuiet() { runSearch(true); }

    private void searchWithin(String needle) {
        if (needle == null || needle.isBlank()) { runSearch(); return; }
        String n = needle.toLowerCase();
        List<SearchHit> keep = new ArrayList<>();
        for (SearchHit h : results) {
            if (h.item().name().toLowerCase().contains(n)
                    || h.item().extractedText().toLowerCase().contains(n)) keep.add(h);
        }
        results.setAll(keep);
        resultCountLabel.setText(String.format("%,d results (within)", keep.size()));
    }

    private void sortResults(String mode) { runSearch(); }

    private void applyTagToSelection(String tag) {
        List<SearchHit> sel = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (sel.isEmpty()) { toast("Select one or more elements first"); return; }
        try {
            liveCase.applyTag(sel.stream().map(SearchHit::item).toList(), tag, "analyst");
        } catch (Exception ex) {
            log("ERROR", "Tagging failed: " + ex.getMessage());
            return;
        }
        table.refresh();
        if (current != null) showItem(current);
        toast("Tag “" + tag + "” toggled on " + sel.size() + " element(s)");
    }

    // ---- element display -------------------------------------------------

    private void showItem(Item it) {
        current = it;
        previewTitle.setText(it.name());

        // Preview tab
        previewBody.getChildren().clear();
        String ext = it.extension();
        if (it.isEmail()) {
            previewBody.getChildren().addAll(
                    kvBig("Subject", nz(it.subject())),
                    kvBig("From", nz(it.from())),
                    kvBig("To", nz(it.to())),
                    kvBig("Cc", nz(it.cc())),
                    kvBig("Sent", it.sentDate() == null ? "" : DT.format(it.sentDate())),
                    new Separator());
            if (it.hasAttachments()) {
                previewBody.getChildren().add(UiParts.sectionTitle("Attachments (" + it.attachmentCount() + ")"));
                for (int i = 0; i < it.attachmentCount(); i++) {
                    HBox row = new HBox(6, UiParts.iconBox(UiParts.I_DOC, "#7f8ba4", 12),
                            new Label("attachment_" + (i + 1) + (i == 0 ? ".pdf" : ".xlsx")),
                            UiParts.dim("· indexed as separate element (F-10)"));
                    row.setAlignment(Pos.CENTER_LEFT);
                    previewBody.getChildren().add(row);
                }
                previewBody.getChildren().add(new Separator());
            }
        } else if (List.of("jpg", "jpeg", "png", "gif", "bmp", "tiff").contains(ext)) {
            previewBody.getChildren().add(imagePlaceholder(it));
            previewBody.getChildren().add(UiParts.dim(
                    settings.ocrEnabled()
                            ? "OCR layer present (Tesseract, eng) — text below is searchable"
                            : "OCR disabled for this case"));
        } else if (List.of("docx", "xlsx", "pptx", "doc", "xls", "ppt", "odt").contains(ext)) {
            previewBody.getChildren().add(UiParts.dim("Office format — rendered via converted preview (F-22)"));
        }

        String text = it.extractedText();
        previewBody.getChildren().add(UiParts.sectionTitle("Extracted text"));
        previewBody.getChildren().add(highlighted(text, CaseStore.firstKeyword(queryField.getText())));
        if (!it.errors().isEmpty()) {
            Label err = new Label("⚠ " + String.join("; ", it.errors()));
            err.setWrapText(true);
            err.setStyle("-fx-text-fill: #ff8a8a;");
            previewBody.getChildren().add(err);
        }
        if (it.status() == ItemStatus.LOCKED) {
            Label l = new Label("🔒 Password-protected — none of the "
                    + settings.passwords().size()
                    + " supplied passwords matched. Item retained and marked Locked (F-04).");
            l.setWrapText(true);
            l.setStyle("-fx-text-fill: #f5b74b;");
            previewBody.getChildren().add(l);
        }

        // Metadata tab (F-09)
        metaBody.getChildren().clear();
        addMeta("Element ID", it.id());
        addMeta("Name", it.name());
        addMeta("Extension", it.extension());
        addMeta("Media type", nz(it.mediaType()));
        addMeta("Size", human(it.size()) + "  (" + String.format("%,d", it.size()) + " bytes)");
        addMeta("Created", fmt(it.created()));
        addMeta("Modified", fmt(it.modified()));
        addMeta("Accessed", fmt(it.accessed()));
        addMeta("MD5", nz(it.md5()));
        addMeta("SHA-256", nz(it.sha256()));
        addMeta("Custodian", nz(it.custodian()));
        addMeta("Source path", nz(it.sourcePath()));
        addMeta("Container path", it.containerPath() == null ? "— (top level)" : it.containerPath());
        addMeta("Nesting depth", String.valueOf(it.depth()));
        addMeta("Parent element", nz(it.parentId()));
        addMeta("Geo location", it.geoLocation() == null ? "—" : it.geoLocation());
        addMeta("Status", it.status().label());
        if (it.isEmail()) {
            metaBody.getChildren().add(UiParts.sectionTitle("Email fields"));
            addMeta("From", nz(it.from()));
            addMeta("To", nz(it.to()));
            addMeta("Cc", nz(it.cc()));
            addMeta("Subject", nz(it.subject()));
            addMeta("Sent", fmt(it.sentDate()));
            addMeta("Message-ID", nz(it.messageId()));
            addMeta("Has attachments", String.valueOf(it.hasAttachments()));
            addMeta("Attachment count", String.valueOf(it.attachmentCount()));
        }

        // Tags tab
        tagBody.getChildren().clear();
        tagBody.getChildren().add(UiParts.sectionTitle("Fixed tags"));
        FlowPane fixed = new FlowPane(6, 6);
        for (Tag tg : Tag.FIXED) {
            ToggleButton b = new ToggleButton(tg.name());
            b.setSelected(it.tags().contains(tg.name()));
            b.setTooltip(new Tooltip(tg.shortcut()));
            b.setOnAction(e -> {
                try {
                    liveCase.applyTag(List.of(it), tg.name(), "analyst");
                } catch (Exception ex) {
                    log("ERROR", "Tagging failed: " + ex.getMessage());
                }
                table.refresh();
            });
            fixed.getChildren().add(b);
        }
        tagBody.getChildren().add(fixed);
        tagBody.getChildren().add(UiParts.sectionTitle("Applied"));
        FlowPane applied = new FlowPane(4, 4);
        for (String tg : it.tags()) applied.getChildren().add(UiParts.tagChip(tg, colorOf(tg)));
        tagBody.getChildren().add(applied);

        notesArea.setText(it.notes());
    }

    private Node imagePlaceholder(Item it) {
        int w = 300, h = 170;
        WritableImage img = new WritableImage(w, h);
        var pw = img.getPixelWriter();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double n = ((x * 13 + y * 7) % 23) / 23.0;
                double v = 0.14 + 0.06 * n + 0.10 * Math.sin(y / 18.0);
                pw.setColor(x, y, javafx.scene.paint.Color.color(v, v * 1.03, v * 1.18));
            }
        }
        javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);
        iv.setPreserveRatio(true);
        iv.setFitWidth(300);
        VBox box = new VBox(4, iv, UiParts.dim(it.name() + " · raster preview · "
                + (it.geoLocation() == null ? "no GPS" : "GPS " + it.geoLocation())));
        box.setStyle("-fx-border-color: -line; -fx-border-radius: 4; -fx-padding: 6;");
        return box;
    }

    /** F-17 hit highlighting inside the preview. */
    private Node highlighted(String text, String needle) {
        TextFlow flow = new TextFlow();
        flow.setPrefWidth(340);
        if (text == null || text.isEmpty()) {
            Text t = new Text("(no extracted text — element stored and marked)");
            t.setStyle("-fx-fill: -txt-2;");
            flow.getChildren().add(t);
            return flow;
        }
        if (needle == null || needle.isEmpty()) {
            Text t = new Text(text);
            t.setStyle("-fx-fill: -txt-0;");
            flow.getChildren().add(t);
            return flow;
        }
        String lower = text.toLowerCase();
        int i = 0;
        while (true) {
            int at = lower.indexOf(needle, i);
            if (at < 0) {
                Text tail = new Text(text.substring(i));
                tail.setStyle("-fx-fill: -txt-0;");
                flow.getChildren().add(tail);
                break;
            }
            Text before = new Text(text.substring(i, at));
            before.setStyle("-fx-fill: -txt-0;");
            Text hit = new Text(text.substring(at, at + needle.length()));
            hit.setStyle("-fx-fill: #0d1017; -fx-font-weight: bold;");
            StackPane mark = new StackPane(hit);
            mark.setStyle("-fx-background-color: #ffd766; -fx-background-radius: 2; -fx-padding: 0 1 0 1;");
            flow.getChildren().addAll(before, mark);
            i = at + needle.length();
        }
        return flow;
    }

    // ---- dialogs ---------------------------------------------------------

    private void showIngestDialog() {
        Dialog<ButtonType> d = baseDialog("Add evidence", 560);
        TextField src = new TextField(System.getProperty("aegis.defaultSource", ""));
        src.setPromptText("Folder or file to ingest (opened read-only)");
        Button browse = new Button("Browse…");
        browse.setOnAction(ev -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("Select evidence folder");
            java.io.File f = dc.showDialog(table.getScene().getWindow());
            if (f != null) src.setText(f.getAbsolutePath());
        });
        HBox srcRow = new HBox(6, src, browse);
        HBox.setHgrow(src, Priority.ALWAYS);
        TextField cust = new TextField("A. Farouk");
        Spinner<Integer> depth = new Spinner<>(1, 50, settings.maxArchiveDepth());
        CheckBox ocr = new CheckBox("Enable OCR (Tesseract, eng)");
        ocr.setSelected(settings.ocrEnabled());
        ComboBox<String> dedupe = new ComboBox<>(FXCollections.observableArrayList(
                "Off (highlight only)", "Per custodian", "Global"));
        dedupe.setValue("Off (highlight only)");
        TextArea pwds = new TextArea("Passw0rd!\nnorthwind2024\ncase017");
        pwds.setPrefRowCount(3);
        pwds.setPromptText("One candidate password per line (F-04)");

        GridPane g = form();
        int r = 0;
        g.addRow(r++, new Label("Source folder"), srcRow);
        g.addRow(r++, new Label("Custodian"), cust);
        g.addRow(r++, new Label("Max nesting depth"), depth);
        g.addRow(r++, new Label("Deduplication"), dedupe);
        g.addRow(r++, new Label(""), ocr);
        g.addRow(r++, new Label("Passwords"), pwds);
        Label note = UiParts.dim("Source is opened read-only; originals are never modified, moved or renamed (F-06).");
        note.setWrapText(true);
        g.add(note, 0, r, 2, 1);

        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().setAll(
                new ButtonType("Start ingest", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        d.showAndWait().ifPresent(bt -> {
            if (bt.getButtonData() != ButtonBar.ButtonData.OK_DONE) return;
            settings.maxArchiveDepth(depth.getValue());
            settings.ocrEnabled(ocr.isSelected());
            settings.dedupeScope(switch (dedupe.getValue()) {
                case "Per custodian" -> CaseSettings.DedupeScope.PER_CUSTODIAN;
                case "Global" -> CaseSettings.DedupeScope.GLOBAL;
                default -> CaseSettings.DedupeScope.OFF;
            });
            settings.passwords().clear();
            for (String p : pwds.getText().split("\\R")) if (!p.isBlank()) settings.passwords().add(p.trim());
            startRealIngest(src.getText(), cust.getText());
        });
    }

    private void showExportDialog() {
        Dialog<ButtonType> d = baseDialog("Export", 620);
        ToggleGroup scope = new ToggleGroup();
        int selCount = table.getSelectionModel().getSelectedItems().size();
        RadioButton s1 = radio("Selected (" + selCount + ")", scope, selCount > 0);
        RadioButton s2 = radio("All results (" + results.size() + ")", scope, selCount == 0);
        RadioButton s3 = radio("By tag", scope, false);
        ComboBox<String> tagPick = new ComboBox<>(FXCollections.observableArrayList(
                Tag.FIXED.stream().map(Tag::name).toList()));
        tagPick.setValue("Responsive");
        tagPick.disableProperty().bind(s3.selectedProperty().not());

        ComboBox<String> format = new ComboBox<>(FXCollections.observableArrayList(
                "Native (original files)", "EML (emails)", "PDF (rendered)", "Text only"));
        format.setValue("Native (original files)");

        ComboBox<String> folders = new ComboBox<>(FXCollections.observableArrayList(
                "Flat", "By type", "By custodian", "Preserve container hierarchy"));
        folders.setValue("By custodian");

        CheckBox fCsv = new CheckBox("Load file (CSV, UTF-8, "
                + com.aegis.fdx.export.Exporter.COLUMNS.length + " fixed columns)");
        fCsv.setSelected(true);
        CheckBox fText = new CheckBox("Extracted text sidecars");
        fText.setSelected(true);
        CheckBox fManifest = new CheckBox("SHA-256 manifest");
        fManifest.setSelected(true);
        CheckBox fVerify = new CheckBox("Re-verify every hash after writing");
        fVerify.setSelected(true);

        TextField destField = new TextField(
                liveCase.folder().exports().resolve("export-" + System.currentTimeMillis() / 1000)
                        .toString());
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("Export destination");
            java.io.File f = dc.showDialog(destField.getScene().getWindow());
            if (f != null) destField.setText(f.getAbsolutePath());
        });
        HBox destRow = new HBox(8, destField, browse);
        HBox.setHgrow(destField, Priority.ALWAYS);

        GridPane g = form();
        int r = 0;
        g.add(UiParts.sectionTitle("Scope"), 0, r++, 2, 1);
        g.add(new VBox(4, s1, s2, new HBox(8, s3, tagPick)), 0, r++, 2, 1);
        g.add(UiParts.sectionTitle("Format"), 0, r++, 2, 1);
        g.addRow(r++, new Label("Export as"), format);
        g.addRow(r++, new Label("Folder layout"), folders);
        g.add(new VBox(4, fCsv, fText, fManifest, fVerify), 0, r++, 2, 1);
        g.add(UiParts.sectionTitle("Destination"), 0, r++, 2, 1);
        g.add(destRow, 0, r++, 2, 1);
        Label note = UiParts.dim("Hidden-tagged elements are always excluded. "
                + "Every write is hash-verified and recorded in the audit log (F-24, F-26, F-27).");
        note.setWrapText(true);
        g.add(note, 0, r, 2, 1);

        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().setAll(
                new ButtonType("Export", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);

        d.showAndWait().ifPresent(bt -> {
            if (bt.getButtonData() != ButtonBar.ButtonData.OK_DONE) return;

            var req = new com.aegis.fdx.export.ExportRequest()
                    .destination(java.nio.file.Path.of(destField.getText().trim()))
                    .format(switch (format.getValue()) {
                        case "EML (emails)" -> com.aegis.fdx.export.ExportRequest.Format.EML;
                        case "PDF (rendered)" -> com.aegis.fdx.export.ExportRequest.Format.PDF;
                        case "Text only" -> com.aegis.fdx.export.ExportRequest.Format.TEXT;
                        default -> com.aegis.fdx.export.ExportRequest.Format.NATIVE;
                    })
                    .layout(switch (folders.getValue()) {
                        case "Flat" -> com.aegis.fdx.export.ExportRequest.Layout.FLAT;
                        case "By type" -> com.aegis.fdx.export.ExportRequest.Layout.BY_TYPE;
                        case "Preserve container hierarchy" ->
                                com.aegis.fdx.export.ExportRequest.Layout.HIERARCHY;
                        default -> com.aegis.fdx.export.ExportRequest.Layout.BY_CUSTODIAN;
                    })
                    .writeLoadFile(fCsv.isSelected())
                    .writeText(fText.isSelected())
                    .writeManifest(fManifest.isSelected())
                    .verifyHashes(fVerify.isSelected());

            // Resolve the scope to actual elements off the FX thread.
            new Thread(() -> {
                try {
                    List<Item> items;
                    if (s1.isSelected()) {
                        items = new java.util.ArrayList<>();
                        for (SearchHit h : table.getSelectionModel().getSelectedItems()) {
                            items.add(h.item());
                        }
                        req.scope(com.aegis.fdx.export.ExportRequest.Scope.SELECTED);
                    } else if (s3.isSelected()) {
                        String tag = tagPick.getValue();
                        items = liveCase.allItems().stream()
                                .filter(i -> i.tags().contains(tag)).toList();
                        req.scope(com.aegis.fdx.export.ExportRequest.Scope.BY_TAG).tag(tag);
                    } else {
                        items = liveCase.allItems();
                        req.scope(com.aegis.fdx.export.ExportRequest.Scope.ALL);
                    }

                    Platform.runLater(() -> log("INFO",
                            "Export started: " + items.size() + " element(s) → " + req.destination()));

                    var res = liveCase.export(req, items,
                            m -> Platform.runLater(() -> log("INFO", m))).get();

                    Platform.runLater(() -> {
                        log(res.clean() ? "INFO" : "WARN", "Export finished: " + res);
                        info(res.clean() ? "Export complete" : "Export completed with problems",
                                res.written() + " element(s) written to\n" + res.destination()
                                + "\n\n"
                                + (res.loadFile() != null
                                        ? "loadfile.csv — " + res.candidates() + " rows, UTF-8\n" : "")
                                + res.hashVerified() + " hash(es) re-verified after write\n"
                                + res.skippedHidden() + " Hidden element(s) excluded\n"
                                + (res.failed() > 0 ? res.failed() + " failed — see the log\n" : "")
                                + (res.hashMismatched() > 0
                                        ? "\nWARNING: " + res.hashMismatched()
                                          + " hash MISMATCH — do not produce this set."
                                        : ""));
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        log("ERROR", "Export failed: " + ex.getMessage());
                        info("Export failed", String.valueOf(ex.getMessage()));
                    });
                }
            }, "aegis-export").start();
        });
    }

    /** M3-D. Re-hashes every original and reports any drift from the ingest baseline. */
    private void showIntegrityCheck() {
        log("INFO", "Integrity verification started…");
        new Thread(() -> {
            try {
                var rep = liveCase.verifyIntegrity(
                        m -> Platform.runLater(() -> log("INFO", m))).get();
                Platform.runLater(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append(rep.verified()).append(" original(s) re-hashed and matched\n");
                    sb.append(rep.embedded()).append(" embedded element(s) covered by their container\n");
                    if (rep.modified() > 0) sb.append(rep.modified()).append(" MODIFIED\n");
                    if (rep.missing() > 0) sb.append(rep.missing()).append(" missing\n");
                    if (rep.unreadable() > 0) sb.append(rep.unreadable()).append(" unreadable\n");
                    if (rep.textMissing() > 0) sb.append(rep.textMissing()).append(" missing extracted text\n");
                    sb.append("\n").append(String.format("%.1f MB hashed in %d ms",
                            rep.bytesHashed() / 1048576.0, rep.millis()));
                    if (!rep.clean()) {
                        sb.append("\n\nFindings:\n");
                        rep.problems().stream().limit(12).forEach(f ->
                                sb.append("  • ").append(f.name()).append(" — ")
                                  .append(f.kind()).append('\n'));
                    }
                    log(rep.clean() ? "INFO" : "ERROR", "Integrity: " + rep);
                    info(rep.clean() ? "Evidence integrity verified"
                                     : "INTEGRITY PROBLEMS FOUND", sb.toString());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> log("ERROR", "Verification failed: " + ex.getMessage()));
            }
        }, "aegis-verify").start();
    }

    /** M3-B. Writes the three F-27 reports to the case exports folder. */
    private void generateReports() {
        new Thread(() -> {
            try {
                var all = liveCase.allItems();
                var reports = liveCase.reports();
                java.nio.file.Path proc = reports.processing(
                        all, liveCase.ocrSummary(), 0);
                java.util.Map<String, Item> byId = new java.util.HashMap<>();
                for (Item i : all) byId.put(i.id(), i);
                java.nio.file.Path dup = reports.duplicates(liveCase.duplicateClusters(), byId);
                java.util.List<Item> hits = new java.util.ArrayList<>();
                for (SearchHit h : results) hits.add(h.item());
                java.nio.file.Path sr = reports.search(
                        nz(queryField.getText()), hits, 0, "analyst");
                Platform.runLater(() -> {
                    log("INFO", "Reports written to " + proc.getParent());
                    info("Reports generated",
                            "Written to " + proc.getParent() + "\n\n"
                            + "• " + proc.getFileName() + "\n"
                            + "• " + dup.getFileName() + "\n"
                            + "• " + sr.getFileName() + "\n\n"
                            + "Each report also has a matching .csv.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> log("ERROR", "Report generation failed: " + ex.getMessage()));
            }
        }, "aegis-reports").start();
    }

    private void showReports() {
        Dialog<ButtonType> d = baseDialog("Reports", 700);
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Processing report
        VBox proc = new VBox(6);
        proc.setPadding(new Insets(12));
        proc.getChildren().add(UiParts.sectionTitle("Elements by status"));
        Map<ItemStatus, Integer> counts;
        try { counts = liveCase.statusCounts(); } catch (Exception ex) { counts = Map.of(); }
        int totalItems = counts.values().stream().mapToInt(Integer::intValue).sum();
        for (Map.Entry<ItemStatus, Integer> e : counts.entrySet()) {
            HBox row = new HBox(8, UiParts.statusPill(e.getKey()),
                    new Label(String.format("%,d", e.getValue())));
            row.setAlignment(Pos.CENTER_LEFT);
            proc.getChildren().add(row);
        }
        proc.getChildren().add(UiParts.sectionTitle("Elements by type"));
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (SearchHit h : results) byType.merge(h.item().extension(), 1, Integer::sum);
        final int denom = Math.max(totalItems, 1);
        byType.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(14)
                .forEach(e -> proc.getChildren().add(bar(e.getKey().toUpperCase(), e.getValue(), denom)));
        proc.getChildren().add(UiParts.sectionTitle("Errors"));
        // Uses the case's own connection rather than opening a second one to the same
        // file. A separate DriverManager connection got none of the configured pragmas
        // (WAL, busy_timeout, cache), took its own lock on a database an ingest run may
        // be writing, and was never closed — the statement was closed, the connection
        // behind it leaked on every report.
        try (var st = liveCase.db().connection().createStatement();
             var rs = st.executeQuery(
                     "SELECT name, error FROM item WHERE status='ERROR' LIMIT 20")) {
            while (rs.next()) {
                Label l = new Label("• " + rs.getString(1) + " — " + nz(rs.getString(2)));
                l.setWrapText(true);
                l.getStyleClass().add("dim");
                proc.getChildren().add(l);
            }
        } catch (Exception ex) {
            proc.getChildren().add(UiParts.dim("error list unavailable: " + ex.getMessage()));
        }

        // Duplicates report
        VBox dup = new VBox(6);
        dup.setPadding(new Insets(12));
        Map<String, List<String>> dupes;
        try { dupes = liveCase.duplicateClusters(); } catch (Exception ex) { dupes = Map.of(); }
        dup.getChildren().add(new Label(dupes.size() + " duplicate cluster(s); scope = "
                + settings.dedupeScope() + " — duplicates are retained and marked, never deleted (F-05)."));
        dupes.forEach((sha, ids) -> {
            VBox cluster = new VBox(2);
            cluster.setPadding(new Insets(6, 0, 6, 0));
            Label h = new Label(sha.substring(0, Math.min(24, sha.length())) + "…  ×" + ids.size());
            h.getStyleClass().add("mono");
            cluster.getChildren().add(h);
            ids.stream().limit(6).forEach(id -> {
                try {
                    Item i = liveCase.byId(id);
                    if (i != null) cluster.getChildren().add(UiParts.dim(
                            "   " + nz(i.custodian()) + "  ·  " + nz(i.sourcePath())));
                } catch (Exception ignored) { }
            });
            dup.getChildren().add(cluster);
        });

        // Search report
        VBox sr = new VBox(4);
        sr.setPadding(new Insets(12));
        try {
            liveCase.db().auditLog(300).stream()
                    .filter(a -> "SEARCH".equals(a.action()))
                    .forEach(a -> sr.getChildren().add(
                            UiParts.dim(DT.format(a.when()) + "  " + nz(a.detail()))));
        } catch (Exception ex) {
            sr.getChildren().add(UiParts.dim("audit unavailable: " + ex.getMessage()));
        }
        if (sr.getChildren().isEmpty()) sr.getChildren().add(UiParts.dim("No searches recorded yet."));

        // Threads
        VBox th = new VBox(4);
        th.setPadding(new Insets(12));
        Map<String, List<Item>> threads;
        try { threads = liveCase.emailThreads(2000); } catch (Exception ex) { threads = Map.of(); }
        threads.forEach((subj, list) -> {
            if (list.size() < 2) return;
            th.getChildren().add(new Label("▾ " + subj + "  (" + list.size() + " messages)"));
            list.stream().limit(6).forEach(i -> th.getChildren().add(
                    UiParts.dim("    " + fmt(i.sentDate()) + "  " + nz(i.from()) + " → " + nz(i.to()))));
        });

        tabs.getTabs().addAll(
                new Tab("Processing", scroll(proc)),
                new Tab("Duplicates", scroll(dup)),
                new Tab("Searches", scroll(sr)),
                new Tab("Email threads", scroll(th)));
        tabs.setPrefHeight(520);
        d.getDialogPane().setContent(tabs);
        ButtonType writeBt = new ButtonType("Write reports to /exports",
                ButtonBar.ButtonData.APPLY);
        d.getDialogPane().getButtonTypes().setAll(writeBt, ButtonType.CLOSE);
        d.showAndWait().ifPresent(bt -> {
            if (bt == writeBt) generateReports();
        });
    }

    private void showThreads() { showReports(); }
    private void showDuplicates() { showReports(); }

    private void showSettings() {
        Dialog<ButtonType> d = baseDialog("Case settings", 520);
        CaseSettings s = settings;
        CheckBox ocr = new CheckBox("OCR enabled");
        ocr.setSelected(s.ocrEnabled());
        TextField langs = new TextField(String.join(",", s.ocrLanguages()));
        Spinner<Integer> depth = new Spinner<>(1, 50, s.maxArchiveDepth());
        Spinner<Integer> mem = new Spinner<>(512, 65536, s.indexMemoryMb(), 512);
        Spinner<Integer> workers = new Spinner<>(1, 128, s.workers());
        ComboBox<String> dd = new ComboBox<>(FXCollections.observableArrayList(
                "OFF", "PER_CUSTODIAN", "GLOBAL"));
        dd.setValue(s.dedupeScope().name());
        CheckBox aes = new CheckBox("AES-256 encrypt case folder");
        aes.setSelected(s.encryptCaseFolder());

        GridPane g = form();
        int r = 0;
        g.addRow(r++, new Label("OCR"), ocr);
        g.addRow(r++, new Label("OCR languages"), langs);

        // Report what the OCR engine can actually do right now, rather than
        // letting an analyst enable a language that is not installed.
        var probe = new com.aegis.fdx.ocr.OcrEngine();
        Label ocrStatus = UiParts.dim(probe.available()
                ? probe.version() + " · installed: " + String.join(", ", probe.installedLanguages())
                : "No Tesseract found — OCR will be skipped and reported in the "
                  + "processing report.");
        ocrStatus.setWrapText(true);
        g.add(ocrStatus, 1, r++);
        g.addRow(r++, new Label("Max archive depth"), depth);
        g.addRow(r++, new Label("Index memory (MB)"), mem);
        g.addRow(r++, new Label("Worker threads"), workers);
        g.addRow(r++, new Label("Dedupe scope"), dd);
        g.addRow(r++, new Label("Encryption"), aes);
        Label n = UiParts.dim("Fully offline. No telemetry. Files > "
                + s.streamThresholdMb() + " MB are streamed, never fully loaded (N-04, N-06).");
        n.setWrapText(true);
        g.add(n, 0, r, 2, 1);

        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().setAll(
                new ButtonType("Save", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        d.showAndWait().ifPresent(bt -> {
            if (bt.getButtonData() != ButtonBar.ButtonData.OK_DONE) return;
            s.ocrEnabled(ocr.isSelected());
            s.maxArchiveDepth(depth.getValue());
            s.indexMemoryMb(mem.getValue());
            s.workers(workers.getValue());
            s.dedupeScope(CaseSettings.DedupeScope.valueOf(dd.getValue()));
            s.encryptCaseFolder(aes.isSelected());
            log("INFO", "Case settings updated");
        });
    }

    // ---- small helpers ---------------------------------------------------

    private void addMeta(String k, String v) {
        Label key = new Label(k);
        key.getStyleClass().add("dim");
        key.setMinWidth(112);
        key.setPrefWidth(112);
        Label val = new Label(v == null || v.isEmpty() ? "—" : v);
        val.setWrapText(true);
        if (k.equals("MD5") || k.equals("SHA-256")) val.getStyleClass().add("mono");
        HBox row = new HBox(6, key, val);
        HBox.setHgrow(val, Priority.ALWAYS);
        metaBody.getChildren().add(row);
    }

    private Node kvBig(String k, String v) {
        Label key = new Label(k);
        key.getStyleClass().add("dim");
        key.setMinWidth(56);
        Label val = new Label(v.isEmpty() ? "—" : v);
        val.setWrapText(true);
        HBox row = new HBox(6, key, val);
        HBox.setHgrow(val, Priority.ALWAYS);
        return row;
    }

    private Node bar(String label, int value, int total) {
        Label l = new Label(label);
        l.setMinWidth(64);
        l.getStyleClass().add("dim");
        ProgressBar pb = new ProgressBar(total == 0 ? 0 : (double) value / total);
        pb.setPrefWidth(260);
        Label n = new Label(String.valueOf(value));
        n.getStyleClass().add("mono");
        HBox row = new HBox(8, l, pb, n);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node header(String title, String icon) {
        Label l = new Label(title);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 11.5px;");
        HBox h = new HBox(6, UiParts.iconBox(icon, "#6f7a92", 13), l);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("panel-header");
        return h;
    }

    private Button ghost(String text, String icon) {
        Button b = new Button(text);
        b.setGraphic(UiParts.iconBox(icon, "#9aa5bc", 13));
        b.getStyleClass().add("ghost");
        return b;
    }

    private static RadioButton radio(String text, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(text);
        r.setToggleGroup(g);
        r.setSelected(sel);
        return r;
    }

    private static GridPane form() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        ColumnConstraints c0 = new ColumnConstraints(150);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c0, c1);
        return g;
    }

    private Dialog<ButtonType> baseDialog(String title, double width) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(null);
        d.getDialogPane().setPrefWidth(width);
        d.getDialogPane().getStylesheets().add(getClass().getResource("aegis.css").toExternalForm());
        d.getDialogPane().setStyle("-fx-background-color: -bg-1;");
        return d;
    }

    private void info(String title, String body) {
        Dialog<ButtonType> d = baseDialog(title, 460);
        Label l = new Label(body);
        l.setWrapText(true);
        l.setPadding(new Insets(14));
        d.getDialogPane().setContent(l);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.OK);
        d.showAndWait();
    }

    private void toast(String msg) {
        statusLeft.setText(msg);
        PauseTransition p = new PauseTransition(Duration.seconds(3));
        p.setOnFinished(e -> statusLeft.setText(liveCase.ingestRunning() ? "Ingest running" : "Idle"));
        p.play();
    }

    private void log(String level, String msg) {
        logLines.add(String.format("%s %-5s %s", DT.format(Instant.now()), level, msg));
        if (logLines.size() > 500) logLines.remove(0, 100);
        logView.scrollTo(logLines.size() - 1);
    }

    private static ScrollPane scroll(Node n) {
        ScrollPane s = new ScrollPane(n);
        s.setFitToWidth(true);
        return s;
    }

    private static Node pad(Node n, double p) {
        VBox b = new VBox(n);
        b.setPadding(new Insets(p));
        return b;
    }

    private static Region gap(double w) {
        Region r = new Region();
        r.setMinWidth(w);
        return r;
    }

    private static <T> TableColumn<SearchHit, String> col(String title, double w,
                                                          java.util.function.Function<SearchHit, String> f) {
        TableColumn<SearchHit, String> c = new TableColumn<>(title);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new SimpleStringProperty(f.apply(cd.getValue())));
        return c;
    }

    static String colorOf(String tag) {
        for (Tag t : Tag.FIXED) if (t.name().equals(tag)) return t.color();
        return "#5b6b8c";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * F-01 drag-and-drop ingest. Files or folders dropped anywhere on the window are
     * ingested exactly as if chosen through the Add-evidence dialog.
     *
     * <p>Dropping several paths at once is common (a reviewer selects a batch in
     * Explorer), so each is queued in turn rather than only the first being taken.
     */
    private void installDragAndDrop(Scene scene, javafx.scene.Parent root) {
        scene.setOnDragOver(e -> {
            if (e.getGestureSource() == null && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                root.setStyle("-fx-border-color: #4f8cff; -fx-border-width: 3;");
            }
            e.consume();
        });
        scene.setOnDragExited(e -> { root.setStyle(""); e.consume(); });
        scene.setOnDragDropped(e -> {
            var db = e.getDragboard();
            boolean ok = false;
            if (db.hasFiles()) {
                java.util.List<java.io.File> files = db.getFiles();
                ok = true;
                String custodian = askCustodianForDrop(files.size());
                if (custodian == null) {
                    ok = false;                       // reviewer cancelled
                } else {
                    log("INFO", "Drag-and-drop: " + files.size() + " path(s) received");
                    for (java.io.File f : files) {
                        startRealIngest(f.getAbsolutePath(), custodian);
                    }
                }
            }
            root.setStyle("");
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /**
     * Custodian attribution is mandatory, so a drop must not silently guess one.
     * Returns null when the reviewer cancels.
     */
    private String askCustodianForDrop(int count) {
        TextInputDialog d = new TextInputDialog(lastCustodian);
        d.setTitle("Add evidence");
        d.setHeaderText(count + " item(s) dropped");
        d.setContentText("Custodian");
        d.getDialogPane().getStylesheets().add(
                getClass().getResource("aegis.css").toExternalForm());
        d.getDialogPane().setStyle("-fx-background-color: -bg-1;");
        var res = d.showAndWait();
        if (res.isEmpty() || res.get().isBlank()) return null;
        lastCustodian = res.get().trim();
        return lastCustodian;
    }

    /** Kicks off a real ingest; all work happens on the LiveCase ingest thread. */
    private void startRealIngest(String sourcePath, String custodian) {
        if (sourcePath == null || sourcePath.isBlank()) {
            toast("Choose a source folder first");
            return;
        }
        java.nio.file.Path src = java.nio.file.Path.of(sourcePath.trim());
        if (!java.nio.file.Files.exists(src)) {
            info("Source not found", "No such file or folder:\n" + src);
            return;
        }
        audit("INGEST_START", src + " (custodian=" + custodian + ")");
        liveCase.startIngest(src, custodian,
                ev -> Platform.runLater(() -> onEngineEvent(ev)));
    }

    private void audit(String action, String detail) {
        try { liveCase.db().audit("analyst", action, detail, null); }
        catch (Exception ignored) { }
    }

    private int countByTag(String tag) {
        try {
            Filters f = new Filters();
            f.tags.add(tag);
            return (int) liveCase.searchNow("", f, false, "Relevance", 10_000).totalHits();
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String fmt(Instant i) { return i == null ? "—" : DT.format(i); }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format("%.1f %s", v, u[i]);
    }

    public static void main(String[] args) { launch(args); }
}
