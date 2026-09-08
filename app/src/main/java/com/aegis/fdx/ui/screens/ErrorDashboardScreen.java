package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.ErrorReport;
import com.aegis.fdx.ui.ChartPane;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

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

        TableView<ErrorReport.Entry> table = new TableView<>(entries);
        table.setPlaceholder(Fas.emptyState("No processing failures."));
        table.setPrefHeight(280);
        table.getColumns().add(col("File", 220, ErrorReport.Entry::name));
        table.getColumns().add(col("Type", 70, ErrorReport.Entry::extension));
        table.getColumns().add(col("Status", 110, ErrorReport.Entry::status));
        table.getColumns().add(col("Cause", 280, ErrorReport.Entry::cause));
        table.getColumns().add(col("Path", 300, ErrorReport.Entry::path));

        VBox content = new VBox(16,
                Fas.pageHeader("Error Dashboard", null, refresh),
                tiles,
                new HBox(14,
                        grow(Fas.cardWithHeader("By Status", null, statusChart)),
                        grow(Fas.cardWithHeader("Recurring Causes",
                                "Grouped by the first part of the message", causeChart))),
                Fas.cardWithHeader("All Failures",
                        "Every element that did not index cleanly", table),
                cleanNote);
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) { HBox.setHgrow(v, Priority.ALWAYS); return v; }

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
