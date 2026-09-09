package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.BatchAnalysisFacade;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.BatchRun;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Run an analysis over a chosen set of files, and inspect what previous runs found.
 *
 * <p>Distinct from the Processing Monitor: that shows what the ingest pipeline is doing
 * right now, whereas this selects material, applies an analysis template to it, and
 * keeps a persisted history of the outcomes. They share the engine underneath.
 *
 * <p>Analysis operates on text the engine has already extracted; it never re-reads
 * original evidence.
 */
public final class BatchAnalysisScreen implements Screen {

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AegisFacades facades;
    private final Router router;

    private ComboBox<BatchAnalysisFacade.Template> templateBox;
    private ComboBox<BatchAnalysisFacade.Priority> priorityBox;
    private ComboBox<BatchAnalysisFacade.ErrorHandling> errorBox;
    private ComboBox<String> sourceBox;
    private ComboBox<String> aspectBox;
    private ComboBox<String> typeBox;
    private Label templateHint;

    private final ObservableList<PathDto> selection = FXCollections.observableArrayList();
    private TableView<PathDto> selectionTable;
    private Label selectionCount;
    private CheckBox selectAll;

    private VBox tiles;
    private ProgressBar progress;
    private Label progressText;
    private Label currentFile;
    private Button startBtn;
    private Button cancelBtn;

    private final ObservableList<BatchRun> history = FXCollections.observableArrayList();
    private final ObservableList<BatchRun.ItemOutcome> runItems =
            FXCollections.observableArrayList();
    private Label runDetailLabel;

    public BatchAnalysisScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router;
    }

    @Override
    public String title() {
        return "Batch Analysis";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Batch Analysis";
    }

    @Override
    public String icon() {
        return Icons.PLAY;
    }

    @Override
    public Node build() {
        templateBox = new ComboBox<>(FXCollections.observableArrayList(
                BatchAnalysisFacade.Template.values()));
        templateBox.setValue(BatchAnalysisFacade.Template.KEYWORD_SCAN);
        templateBox.setPrefWidth(180);
        // Show the human label, not the enum constant name.
        templateBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(BatchAnalysisFacade.Template t) {
                return t == null ? "" : t.label();
            }
            @Override public BatchAnalysisFacade.Template fromString(String s) {
                return BatchAnalysisFacade.Template.fromLabel(s);
            }
        });
        templateHint = Fas.muted(BatchAnalysisFacade.Template.KEYWORD_SCAN.description());
        templateBox.setOnAction(e -> {
            if (templateBox.getValue() != null) {
                templateHint.setText(templateBox.getValue().description());
            }
        });

        priorityBox = new ComboBox<>(FXCollections.observableArrayList(
                BatchAnalysisFacade.Priority.values()));
        priorityBox.setValue(BatchAnalysisFacade.Priority.MEDIUM);
        priorityBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(BatchAnalysisFacade.Priority p) {
                return p == null ? "" : p.label();
            }
            @Override public BatchAnalysisFacade.Priority fromString(String s) {
                return BatchAnalysisFacade.Priority.fromLabel(s);
            }
        });

        errorBox = new ComboBox<>(FXCollections.observableArrayList(
                BatchAnalysisFacade.ErrorHandling.values()));
        errorBox.setValue(BatchAnalysisFacade.ErrorHandling.SKIP_FAILED);
        errorBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(BatchAnalysisFacade.ErrorHandling e) {
                return e == null ? "" : e.label();
            }
            @Override public BatchAnalysisFacade.ErrorHandling fromString(String s) {
                return BatchAnalysisFacade.ErrorHandling.fromLabel(s);
            }
        });

        sourceBox = combo("All Sources");
        aspectBox = combo("All Aspects");
        typeBox = combo("All Types");
        sourceBox.setOnAction(e -> refreshSelection());
        aspectBox.setOnAction(e -> refreshSelection());
        typeBox.setOnAction(e -> refreshSelection());

        selectAll = new CheckBox("Select all matching");
        selectAll.setSelected(true);
        selectAll.setOnAction(e -> {
            if (selectAll.isSelected()) {
                selectionTable.getSelectionModel().selectAll();
            } else {
                selectionTable.getSelectionModel().clearSelection();
            }
            updateSelectionCount();
        });

        selectionCount = Fas.muted("0 files selected");
        selectionTable = new TableView<>(selection);
        selectionTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        selectionTable.setPlaceholder(Fas.emptyState(
                "No registered files match these filters."));
        selectionTable.setPrefHeight(200);
        selectionTable.getColumns().add(col("File Name", 230, PathDto::fileName));
        selectionTable.getColumns().add(col("Type", 70, PathDto::fileType));
        selectionTable.getColumns().add(col("Size", 90,
                p -> DashboardScreen.humanBytes(p.fileSize())));
        selectionTable.getColumns().add(col("Source", 140, PathDto::sourceName));
        selectionTable.getColumns().add(col("Aspect", 120, PathDto::aspectName));
        selectionTable.getSelectionModel().getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<PathDto>)
                        c -> updateSelectionCount());

        tiles = new VBox();
        progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progressText = Fas.muted("Idle");
        currentFile = Fas.muted("");

        startBtn = Fas.primary("Start Analysis", Icons.PLAY);
        startBtn.setOnAction(e -> start());
        cancelBtn = Fas.danger("Stop", Icons.STOP);
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> facades.batch().cancel());

        // ---- history ----
        TableView<BatchRun> historyTable = new TableView<>(history);
        historyTable.setPlaceholder(Fas.emptyState("No analysis runs yet."));
        historyTable.setPrefHeight(190);
        historyTable.getColumns().add(hcol("Run", 55, r -> "#" + r.id()));
        historyTable.getColumns().add(hcol("Template", 130, BatchRun::template));
        historyTable.getColumns().add(hcol("State", 95, BatchRun::state));
        historyTable.getColumns().add(hcol("Files", 60,
                r -> String.valueOf(r.selected())));
        historyTable.getColumns().add(hcol("Failed", 60,
                r -> String.valueOf(r.failed())));
        historyTable.getColumns().add(hcol("Success", 80,
                r -> String.format("%.0f%%", r.successRate() * 100)));
        historyTable.getColumns().add(hcol("Avg", 75,
                r -> String.format("%.1f ms", r.averageMillis())));
        historyTable.getColumns().add(hcol("Started", 130,
                r -> r.startedAt() == null ? "" : DT.format(r.startedAt())));
        historyTable.getSelectionModel().selectedItemProperty()
                .addListener((o, a, b) -> showRun(b));

        Button rerun = Fas.outline("Re-run", Icons.REFRESH);
        rerun.setOnAction(e -> {
            BatchRun sel = historyTable.getSelectionModel().getSelectedItem();
            if (sel != null) {
                rerun(sel.id());
            }
        });
        Button deleteRun = Fas.ghost("Delete Run", Icons.TRASH);
        deleteRun.setOnAction(e -> {
            BatchRun sel = historyTable.getSelectionModel().getSelectedItem();
            if (sel != null) {
                facades.batch().deleteRun(sel.id());
                onShow();
            }
        });

        runDetailLabel = Fas.muted("Select a run to see its per-file outcomes");
        TableView<BatchRun.ItemOutcome> itemTable = new TableView<>(runItems);
        itemTable.setPlaceholder(Fas.emptyState("No per-file outcomes."));
        itemTable.setPrefHeight(190);
        TableColumn<BatchRun.ItemOutcome, String> i1 = new TableColumn<>("File");
        i1.setPrefWidth(220);
        i1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().fileName()));
        TableColumn<BatchRun.ItemOutcome, String> i2 = new TableColumn<>("Outcome");
        i2.setPrefWidth(100);
        i2.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().outcome()));
        TableColumn<BatchRun.ItemOutcome, String> i3 = new TableColumn<>("Detail");
        i3.setPrefWidth(240);
        i3.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().detail() == null ? "" : c.getValue().detail()));
        TableColumn<BatchRun.ItemOutcome, String> i4 = new TableColumn<>("Time");
        i4.setPrefWidth(70);
        i4.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().millis() + " ms"));
        itemTable.getColumns().addAll(List.of(i1, i2, i3, i4));
        itemTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<BatchRun.ItemOutcome> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openFile(row.getItem().pathId());
                }
            });
            return row;
        });

        HBox filters = new HBox(10,
                Fas.formField("Template", templateBox),
                Fas.formField("Priority", priorityBox),
                Fas.formField("On Error", errorBox),
                Fas.formField("Source", sourceBox),
                Fas.formField("Aspect", aspectBox),
                Fas.formField("Type", typeBox));

        VBox content = new VBox(16,
                Fas.pageHeader("Batch Analysis", null, startBtn, cancelBtn),
                tiles,
                Fas.cardWithHeader("Configure", "Choose what to run and over which files",
                        new VBox(10, filters, templateHint)),
                Fas.cardWithHeader("Selection",
                        "Rows selected here are the ones analysed",
                        new VBox(10, Fas.row(10, selectAll, Fas.spacer(), selectionCount),
                                selectionTable)),
                Fas.cardWithHeader("Progress", null,
                        new VBox(8, progress, progressText, currentFile)),
                new HBox(14,
                        grow(Fas.cardWithHeader("Analysis History",
                                "Persisted across restarts",
                                new VBox(10, Fas.row(8, rerun, deleteRun), historyTable))),
                        grow(Fas.cardWithHeader("Run Detail",
                                "Double-click a file to open it",
                                new VBox(10, runDetailLabel, itemTable)))));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) {
        HBox.setHgrow(v, Priority.ALWAYS);
        return v;
    }

    private ComboBox<String> combo(String all) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(all));
        c.setValue(all);
        c.setPrefWidth(150);
        return c;
    }

    private static TableColumn<PathDto, String> col(
            String n, double w, java.util.function.Function<PathDto, String> f) {
        TableColumn<PathDto, String> c = new TableColumn<>(n);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    private static TableColumn<BatchRun, String> hcol(
            String n, double w, java.util.function.Function<BatchRun, String> f) {
        TableColumn<BatchRun, String> c = new TableColumn<>(n);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    @Override
    public void onShow() {
        try {
            reloadFilters();
            refreshSelection();
            history.setAll(facades.batch().history(50));
            refreshTiles();
        } catch (RuntimeException e) {
            progressText.setText("Could not load: " + e.getMessage());
        }
    }

    private void reloadFilters() {
        String keepS = sourceBox.getValue();
        sourceBox.getItems().setAll("All Sources");
        facades.sources().listSources().forEach(s -> sourceBox.getItems().add(s.name()));
        sourceBox.setValue(sourceBox.getItems().contains(keepS) ? keepS : "All Sources");

        String keepA = aspectBox.getValue();
        aspectBox.getItems().setAll("All Aspects");
        facades.aspects().listAspects().forEach(a -> aspectBox.getItems().add(a.name()));
        aspectBox.setValue(aspectBox.getItems().contains(keepA) ? keepA : "All Aspects");

        String keepT = typeBox.getValue();
        typeBox.getItems().setAll("All Types");
        facades.contents().getPaths(null, null, null, null, 10_000, 0).results()
                .stream().map(PathDto::fileType).distinct().sorted()
                .forEach(t -> typeBox.getItems().add(t));
        typeBox.setValue(typeBox.getItems().contains(keepT) ? keepT : "All Types");
    }

    private void refreshSelection() {
        try {
            selection.setAll(facades.batch().resolveSelection(currentRequest()));
            if (selectAll.isSelected()) {
                selectionTable.getSelectionModel().selectAll();
            }
            updateSelectionCount();
        } catch (FacadeException e) {
            selection.clear();
            selectionCount.setText(e.getMessage());
        }
    }

    private void updateSelectionCount() {
        int n = selectionTable.getSelectionModel().getSelectedItems().size();
        selectionCount.setText(n + " of " + selection.size() + " files selected");
    }

    private BatchAnalysisFacade.Request currentRequest() {
        return new BatchAnalysisFacade.Request(templateBox.getValue())
                .priority(priorityBox.getValue())
                .errorHandling(errorBox.getValue())
                .source(resolve(sourceBox.getValue(), "All Sources", true))
                .aspect(resolve(aspectBox.getValue(), "All Aspects", false))
                .fileType("All Types".equals(typeBox.getValue()) ? null : typeBox.getValue());
    }

    private Integer resolve(String display, String all, boolean source) {
        if (display == null || all.equals(display)) {
            return null;
        }
        try {
            if (source) {
                return facades.sources().listSources().stream()
                        .filter(s -> display.equals(s.name())).map(s -> s.id())
                        .findFirst().orElse(null);
            }
            return facades.aspects().listAspects().stream()
                    .filter(a -> display.equals(a.name())).map(a -> a.id())
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void refreshTiles() {
        List<BatchRun> runs = history;
        int total = runs.size();
        int clean = (int) runs.stream().filter(BatchRun::isClean).count();
        double avg = runs.stream().mapToDouble(BatchRun::averageMillis)
                .filter(d -> d > 0).average().orElse(0);
        int analysed = runs.stream().mapToInt(BatchRun::completed).sum();

        tiles.getChildren().setAll(Fas.statsGrid(
                Fas.statCard(Icons.LIST, Fas.PRIMARY, String.valueOf(total), "Runs Recorded"),
                Fas.statCard(Icons.CHECK_CIRCLE, Fas.SUCCESS,
                        String.valueOf(clean), "Clean Runs"),
                Fas.statCard(Icons.FILES, Fas.INFO,
                        String.format("%,d", analysed), "Files Analysed"),
                Fas.statCard(Icons.CHART, Fas.WARNING,
                        String.format("%.1f ms", avg), "Avg per File")));
    }

    private void showRun(BatchRun run) {
        if (run == null) {
            runItems.clear();
            runDetailLabel.setText("Select a run to see its per-file outcomes");
            return;
        }
        try {
            BatchRun full = facades.batch().getRun(run.id());
            runItems.setAll(full.items());
            runDetailLabel.setText(String.format(
                    "Run #%d \u00b7 %s \u00b7 %s \u00b7 %d analysed, %d failed \u00b7 %d ms%s",
                    full.id(), full.template(), full.state(), full.completed(),
                    full.failed(), full.millis(),
                    full.note() == null ? "" : " \u00b7 " + full.note()));
        } catch (FacadeException e) {
            runItems.clear();
            runDetailLabel.setText(e.getMessage());
        }
    }

    private void start() {
        List<PathDto> chosen =
                new ArrayList<>(selectionTable.getSelectionModel().getSelectedItems());
        if (chosen.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Select at least one file to analyse.", ButtonType.OK);
            a.setHeaderText("Nothing selected");
            a.showAndWait();
            return;
        }
        List<Integer> ids = new ArrayList<>();
        for (PathDto p : chosen) {
            ids.add(p.id());
        }
        BatchAnalysisFacade.Request req = currentRequest().paths(ids);
        runInBackground(() -> facades.batch().run(req, this::publishProgress));
    }

    private void rerun(int runId) {
        runInBackground(() -> facades.batch().rerun(runId, this::publishProgress));
    }

    private void runInBackground(java.util.function.Supplier<BatchRun> job) {
        startBtn.setDisable(true);
        cancelBtn.setDisable(false);
        progress.setProgress(0);
        progressText.setText("Analysing\u2026");
        currentFile.setText("");

        Thread worker = new Thread(() -> {
            try {
                BatchRun result = job.get();
                Platform.runLater(() -> {
                    progress.setProgress(1);
                    progressText.setText(String.format(
                            "%s \u2014 %d analysed, %d failed in %d ms",
                            result.state(), result.completed(), result.failed(),
                            result.millis()));
                    currentFile.setText("");
                    finish();
                    onShow();
                });
            } catch (FacadeException e) {
                Platform.runLater(() -> {
                    progressText.setText(e.getMessage());
                    finish();
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    progressText.setText(String.valueOf(e.getMessage()));
                    finish();
                });
            }
        }, "fas-batch");
        worker.setDaemon(true);
        worker.start();
    }

    private void publishProgress(BatchAnalysisFacade.Progress p) {
        Platform.runLater(() -> {
            progress.setProgress(p.fraction());
            progressText.setText(String.format("%d of %d analysed, %d failed",
                    p.done(), p.total(), p.failed()));
            currentFile.setText(p.currentFile() == null ? "" : p.currentFile());
        });
    }

    private void finish() {
        startBtn.setDisable(false);
        cancelBtn.setDisable(true);
    }
}
