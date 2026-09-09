package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.FileProcessingFacade;
import com.aegis.fdx.facade.dto.ProcessingResultDto;
import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.facade.dto.StatisticsDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Python parity: {@code templates/file/upload.html} — the drop zone, the mandatory
 * Source/Side selectors and the per-file processing result table.
 *
 * <p>The Python {@code IntegratedFileReader} requires {@code storage_source} and
 * {@code aspect}, so this screen refuses to start until both are chosen, which
 * is the same interface-level rule expressed in the UI.
 *
 * <p>All reading, extraction, hashing and metadata work is done by the existing AEGIS
 * ingest pipeline — unchanged. This screen only drives it and then registers the
 * results into the Python relational model.
 */
public final class UploadScreen implements Screen {

    private final AegisFacades facades;
    private final ObservableList<ProcessingResultDto> results = FXCollections.observableArrayList();

    private ComboBox<String> sourceBox;
    private ComboBox<String> aspectBox;
    private ProgressBar progress;
    private Label statusLabel;
    private Label dropLabel;
    private StackPane dropZone;
    private Button startBtn;
    private Button pauseBtn;
    private Button cancelBtn;
    private final List<File> queued = new ArrayList<>();
    private Label queuedLabel;
    private VBox cTotal;
    private VBox cDone;
    private VBox cFailed;
    private VBox cDupes;

    public UploadScreen(AegisFacades facades) {
        this.facades = facades;
    }

    @Override
    public String title() {
        return "Upload Files";
    }

    @Override
    public String icon() {
        return Icons.CLOUD_UPLOAD;
    }

    @Override
    public Node build() {
        sourceBox = new ComboBox<>();
        sourceBox.setPromptText("Select source (required)");
        sourceBox.setPrefWidth(220);
        aspectBox = new ComboBox<>();
        aspectBox.setPromptText("Select aspect (required)");
        aspectBox.setPrefWidth(220);

        // ---- drop zone (Python .upload-dropzone) ----
        dropLabel = new Label("Drag and drop files or folders here");
        dropLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Fas.TEXT_LIGHT + ";");
        Label hint = Fas.muted("or use the buttons below \u2014 archives, mailboxes and "
                + "nested containers are expanded automatically");

        VBox inner = new VBox(10, Icons.box(Icons.CLOUD_UPLOAD, Fas.PRIMARY, 34),
                dropLabel, hint);
        inner.setAlignment(Pos.CENTER);

        dropZone = new StackPane(inner);
        dropZone.setPrefHeight(170);
        dropZone.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 12;"
                + " -fx-border-color: #cbd5e1; -fx-border-radius: 12;"
                + " -fx-border-style: segments(7, 6) line-cap round;"
                + " -fx-border-width: 2;");

        dropZone.setOnDragOver(this::onDragOver);
        dropZone.setOnDragExited(e -> resetDropStyle());
        dropZone.setOnDragDropped(this::onDragDropped);

        Button chooseFiles = Fas.outline("Choose Files", Icons.FILES);
        chooseFiles.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select files to ingest");
            List<File> f = fc.showOpenMultipleDialog(dropZone.getScene().getWindow());
            if (f != null) {
                queued.addAll(f);
                refreshQueue();
            }
        });

        Button chooseFolder = Fas.outline("Choose Folder", Icons.FOLDER_OPEN);
        chooseFolder.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select a folder to ingest");
            File d = dc.showDialog(dropZone.getScene().getWindow());
            if (d != null) {
                queued.add(d);
                refreshQueue();
            }
        });

        Button clearQueue = Fas.ghost("Clear", Icons.CLOSE);
        clearQueue.setOnAction(e -> {
            queued.clear();
            refreshQueue();
        });

        queuedLabel = Fas.muted("Nothing queued");

        startBtn = Fas.primary("Start Processing", Icons.PLAY);
        startBtn.setOnAction(e -> start());
        pauseBtn = Fas.outline("Pause", Icons.PAUSE);
        pauseBtn.setDisable(true);
        pauseBtn.setOnAction(e -> togglePause());
        cancelBtn = Fas.danger("Cancel", Icons.STOP);
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> facades.processing(safeSource(), safeAspect()).cancel());

        progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        statusLabel = Fas.muted("Idle");

        // ---- stats (Python upload page summary) ----
        cTotal = Fas.statCard(Icons.FILES, Fas.PRIMARY, "0", "Total Files Found");
        cDone = Fas.statCard(Icons.CHECK_CIRCLE, Fas.SUCCESS, "0", "Files Processed");
        cFailed = Fas.statCard(Icons.INFO, Fas.DANGER, "0", "Failed Files");
        cDupes = Fas.statCard(Icons.FILES, Fas.WARNING, "0", "Duplicate Files");

        // ---- result table ----
        TableView<ProcessingResultDto> table = new TableView<>(results);
        table.setPlaceholder(Fas.emptyState("Processing results will appear here."));
        table.setPrefHeight(240);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<ProcessingResultDto, String> cName = new TableColumn<>("File Name");
        cName.setPrefWidth(220);
        cName.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().fileName()));

        TableColumn<ProcessingResultDto, String> cType = new TableColumn<>("Type");
        cType.setPrefWidth(75);
        cType.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().extension()));

        TableColumn<ProcessingResultDto, String> cSize = new TableColumn<>("Size");
        cSize.setPrefWidth(90);
        cSize.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                DashboardScreen.humanBytes(c.getValue().fileSize())));

        TableColumn<ProcessingResultDto, ProcessingResultDto> cOk = new TableColumn<>("Result");
        cOk.setPrefWidth(110);
        cOk.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cOk.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(ProcessingResultDto item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null
                        : Fas.badge(item.success() ? "Processed" : "Error",
                        item.success() ? "success" : "danger"));
            }
        });

        TableColumn<ProcessingResultDto, String> cErr = new TableColumn<>("Details");
        cErr.setPrefWidth(280);
        cErr.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().error() == null ? "" : c.getValue().error()));

        table.getColumns().addAll(List.of(cName, cType, cSize, cOk, cErr));

        VBox content = new VBox(16,
                Fas.pageHeader("Upload Files", "Home / Upload Files"),
                Fas.cardWithHeader("Storage Assignment",
                        "Source and side are mandatory \u2014 every file is stored against them",
                        new HBox(14, Fas.formField("Source *", sourceBox),
                                Fas.formField("Aspect *", aspectBox))),
                Fas.card(dropZone,
                        Fas.row(10, chooseFiles, chooseFolder, clearQueue,
                                Fas.spacer(), queuedLabel)),
                Fas.cardWithHeader("Processing", null,
                        new VBox(10,
                                Fas.row(10, startBtn, pauseBtn, cancelBtn,
                                        Fas.spacer(), statusLabel),
                                progress)),
                Fas.statsGrid(cTotal, cDone, cFailed, cDupes),
                Fas.cardWithHeader("Results", "Every discovered file is recorded, "
                        + "including failures", table));
        content.setPadding(new Insets(20));

        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private void onDragOver(DragEvent e) {
        if (e.getDragboard().hasFiles()) {
            e.acceptTransferModes(TransferMode.COPY);
            dropZone.setStyle("-fx-background-color: #eef2ff; -fx-background-radius: 12;"
                    + " -fx-border-color: #4f46e5; -fx-border-radius: 12;"
                    + " -fx-border-style: segments(7, 6) line-cap round; -fx-border-width: 2;");
            dropLabel.setText("Release to add these items");
        }
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        Dragboard db = e.getDragboard();
        boolean ok = db.hasFiles();
        if (ok) {
            queued.addAll(db.getFiles());
            refreshQueue();
        }
        e.setDropCompleted(ok);
        resetDropStyle();
        e.consume();
    }

    private void resetDropStyle() {
        dropZone.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 12;"
                + " -fx-border-color: #cbd5e1; -fx-border-radius: 12;"
                + " -fx-border-style: segments(7, 6) line-cap round; -fx-border-width: 2;");
        dropLabel.setText("Drag and drop files or folders here");
    }

    private void refreshQueue() {
        queuedLabel.setText(queued.isEmpty() ? "Nothing queued"
                : queued.size() + " item(s) queued");
    }

    private void start() {
        if (sourceBox.getValue() == null || aspectBox.getValue() == null) {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Source and Side are both required before processing can start.",
                    ButtonType.OK);
            a.setHeaderText("Storage assignment missing");
            a.showAndWait();
            return;
        }
        if (queued.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Add at least one file or folder first.", ButtonType.OK);
            a.setHeaderText("Nothing to process");
            a.showAndWait();
            return;
        }

        String src = sourceBox.getValue();
        String side = aspectBox.getValue();
        List<File> batch = List.copyOf(queued);

        startBtn.setDisable(true);
        pauseBtn.setDisable(false);
        cancelBtn.setDisable(false);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText("Processing\u2026");
        results.clear();

        Thread worker = new Thread(() -> {
            List<ProcessingResultDto> all = new ArrayList<>();
            try {
                FileProcessingFacade proc = facades.processing(src, side);
                for (File f : batch) {
                    if (f.isDirectory()) {
                        all.addAll(proc.processFolder(f.getAbsolutePath()));
                    } else {
                        all.add(proc.processSingleFile(f.getAbsolutePath()));
                    }
                }
                // Project the pipeline's output into the Python relational model.
                Integer srcId = idOfSource(src);
                Integer sideId = idOfAspect(side);
                facades.contents().registerIngestedItems(srcId, sideId);

                StatisticsDto stats = proc.getStatistics();
                Platform.runLater(() -> {
                    results.setAll(all);
                    Fas.setStat(cTotal, String.format("%,d", stats.total()));
                    Fas.setStat(cDone, String.format("%,d", stats.completed()));
                    Fas.setStat(cFailed, String.format("%,d", stats.failed()));
                    Fas.setStat(cDupes, String.format("%,d", stats.duplicates()));
                    statusLabel.setText("Complete \u2014 " + all.size() + " item(s)");
                    finish();
                });
            } catch (FacadeException ex) {
                Platform.runLater(() -> {
                    statusLabel.setText(ex.getMessage());
                    finish();
                });
            } catch (RuntimeException ex) {
                Platform.runLater(() -> {
                    statusLabel.setText(String.valueOf(ex.getMessage()));
                    finish();
                });
            }
        }, "fas-upload");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish() {
        progress.setProgress(1);
        startBtn.setDisable(false);
        pauseBtn.setDisable(true);
        cancelBtn.setDisable(true);
        queued.clear();
        refreshQueue();
    }

    private boolean paused;

    private void togglePause() {
        FileProcessingFacade proc = facades.processing(safeSource(), safeAspect());
        if (paused) {
            proc.resume();
            pauseBtn.setText("Pause");
            statusLabel.setText("Processing\u2026");
        } else {
            proc.pause();
            pauseBtn.setText("Resume");
            statusLabel.setText("Paused");
        }
        paused = !paused;
    }

    private String safeSource() {
        return sourceBox.getValue() == null ? "Unassigned" : sourceBox.getValue();
    }

    private String safeAspect() {
        return aspectBox.getValue() == null ? "Unassigned" : aspectBox.getValue();
    }

    private Integer idOfSource(String name) {
        try {
            return facades.sources().listSources().stream()
                    .filter(s -> name.equals(s.name())).map(SourceDto::id)
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer idOfAspect(String name) {
        try {
            return facades.aspects().listAspects().stream()
                    .filter(s -> name.equals(s.name())).map(AspectDto::id)
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void onShow() {
        try {
            String keepS = sourceBox.getValue();
            sourceBox.getItems().setAll(
                    facades.sources().listSources().stream().map(SourceDto::name).toList());
            if (keepS != null && sourceBox.getItems().contains(keepS)) {
                sourceBox.setValue(keepS);
            }
            String keepD = aspectBox.getValue();
            aspectBox.getItems().setAll(
                    facades.aspects().listAspects().stream().map(AspectDto::name).toList());
            if (keepD != null && aspectBox.getItems().contains(keepD)) {
                aspectBox.setValue(keepD);
            }
        } catch (RuntimeException ignored) {
            // the selectors stay empty until sources/sides exist
        }
    }
}
