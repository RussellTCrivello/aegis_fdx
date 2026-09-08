package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.ErrorReport;
import com.aegis.fdx.facade.dto.ProcessingResultDto;
import com.aegis.fdx.ui.ChartPane;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Everything that did not process cleanly, grouped so recurring problems stand out.
 *
 * <p>Surfaces {@code Item.errors()} and the non-indexed statuses, which the engine has
 * always recorded but which no destination previously displayed.
 */
public final class ErrorDashboardScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private VBox tiles;
    private VBox statusChart;
    private VBox causeChart;
    private Label cleanNote;
    private Label retryStatus;
    private Button retryButton;
    private TableView<ErrorReport.Entry> table;
    private final ObservableList<ErrorReport.Entry> entries = FXCollections.observableArrayList();

    public ErrorDashboardScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router;
    }

    @Override public String title() { return "Errors"; }
    @Override public String breadcrumb() { return "Home / System / Errors"; }
    @Override public String icon() { return Icons.INFO; }

    @Override
    public Node build() {
        tiles = new VBox();
        statusChart = new VBox();
        causeChart = new VBox();
        cleanNote = Fas.emptyState(
                "Nothing failed. Every ingested element indexed cleanly.");

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        // A failure is often about the moment rather than the file: a document held
        // open by another program, a share that dropped. Trying it again is the first
        // thing a reviewer wants, and it runs the same pipeline the case was built
        // with — nothing about the element's identity or the reviewer's own notes
        // changes, only what the engine can now read.
        retryButton = Fas.primary("Retry", Icons.REFRESH);
        retryButton.setDisable(true);
        retryButton.setOnAction(e -> retrySelected());
        retryStatus = Fas.muted("");

        table = new TableView<>(entries);
        table.setPlaceholder(Fas.emptyState("No processing failures."));
        table.setPrefHeight(280);
        table.getColumns().add(col("File", 220, ErrorReport.Entry::name));
        table.getColumns().add(col("Type", 70, ErrorReport.Entry::extension));
        table.getColumns().add(col("Status", 110, ErrorReport.Entry::status));
        table.getColumns().add(col("Cause", 280, ErrorReport.Entry::cause));
        table.getColumns().add(col("Path", 300, ErrorReport.Entry::path));
        table.getSelectionModel().selectedItemProperty().addListener(
                (o, was, now) -> retryButton.setDisable(now == null));

        VBox content = new VBox(16,
                Fas.pageHeader("Error Dashboard", null, refresh),
                tiles,
                new HBox(14,
                        grow(Fas.cardWithHeader("By Status", null, statusChart)),
                        grow(Fas.cardWithHeader("Recurring Causes",
                                "Grouped by the first part of the message", causeChart))),
                Fas.cardWithHeader("All Failures",
                        "Every element that did not index cleanly",
                        new VBox(10, table, new HBox(12, retryButton, retryStatus))),
                cleanNote);
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) { HBox.setHgrow(v, Priority.ALWAYS); return v; }

    /** Runs the selected element through the pipeline again, off the interface thread. */
    private void retrySelected() {
        ErrorReport.Entry entry = table.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        retryButton.setDisable(true);
        retryStatus.setText("Retrying " + entry.name() + "\u2026");

        Thread worker = new Thread(() -> {
            ProcessingResultDto result =
                    facades.processing().retryFile(entry.itemId());
            Platform.runLater(() -> {
                if (result.success()) {
                    retryStatus.setText(entry.name() + " processed on retry \u2014 "
                            + result.contentLength() + " characters extracted.");
                } else {
                    retryStatus.setText("Still failing: " + result.error());
                }
                // Whatever happened, the report is now out of date.
                onShow();
                retryButton.setDisable(table.getSelectionModel().getSelectedItem() == null);
            });
        }, "fas-retry");
        worker.setDaemon(true);
        worker.start();
    }

    private static TableColumn<ErrorReport.Entry, String> col(
            String n, double w, java.util.function.Function<ErrorReport.Entry, String> f) {
        TableColumn<ErrorReport.Entry, String> c = new TableColumn<>(n);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    @Override
    public void onShow() {
        try {
            ErrorReport r = facades.analytics().errorReport();
            entries.setAll(r.entries());

            tiles.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.INFO, Fas.DANGER,
                            String.valueOf(r.byStatus().getOrDefault("Error", 0)), "Errors"),
                    Fas.statCard(Icons.SHIELD, Fas.WARNING,
                            String.valueOf(r.byStatus().getOrDefault("Locked", 0)), "Locked"),
                    Fas.statCard(Icons.FOLDER, Fas.INFO,
                            String.valueOf(r.byStatus().getOrDefault("Unsupported", 0)),
                            "Unsupported"),
                    Fas.statCard(Icons.LIST, Fas.TEXT_LIGHT,
                            String.valueOf(r.byCause().size()), "Distinct Causes")));

            statusChart.getChildren().setAll(r.byStatus().isEmpty()
                    ? Fas.emptyState("Nothing failed.")
                    : ChartPane.donut(ChartPane.slices(r.byStatus()), 140, null));

            causeChart.getChildren().setAll(r.byCause().isEmpty()
                    ? Fas.emptyState("No failure causes recorded.")
                    : ChartPane.hbars(ChartPane.top(r.byCause(), 8), 200, null));

            cleanNote.setVisible(r.isClean());
            cleanNote.setManaged(r.isClean());
        } catch (Exception e) {
            tiles.getChildren().setAll(Fas.emptyState("Could not load: " + e.getMessage()));
        }
    }
}
