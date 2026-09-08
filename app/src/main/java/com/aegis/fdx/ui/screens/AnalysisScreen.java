package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * Python parity: {@code templates/Analysis/*} — {@code charts_dashboard.html},
 * {@code comprehensive_dashboard.html}, {@code file_classification.html} and
 * {@code path_analysis.html}, plus {@code /analytics/path-analysis}.
 *
 * <p>Charts in the Python project are Chart.js canvases. JavaFX has no Chart.js, so the
 * same series are drawn with native bar rows — same data, same groupings, same captions.
 */
public final class AnalysisScreen implements Screen {

    private final AegisFacades facades;
    private VBox typeChart;
    private VBox sourceChart;
    private VBox sideChart;
    private VBox tagChart;
    private TableView<Map.Entry<String, List<String>>> dupTable;
    private Label dupLabel;

    public AnalysisScreen(AegisFacades facades) {
        this.facades = facades;
    }

    @Override
    public String title() {
        return "Analysis";
    }

    @Override
    public String icon() {
        return Icons.ARCHIVE;
    }

    @Override
    public Node build() {
        typeChart = new VBox(7);
        sourceChart = new VBox(7);
        sideChart = new VBox(7);
        tagChart = new VBox(7);

        HBox rowA = new HBox(14,
                grow(Fas.cardWithHeader("File Classification",
                        "Elements grouped by extension", typeChart)),
                grow(Fas.cardWithHeader("Sources", "Elements per source", sourceChart)));

        HBox rowB = new HBox(14,
                grow(Fas.cardWithHeader("Path Analysis",
                        "Elements per container path", sideChart)),
                grow(Fas.cardWithHeader("Categories", "Elements per tag", tagChart)));

        dupTable = new TableView<>();
        dupTable.setPlaceholder(Fas.emptyState("No duplicate clusters found."));
        dupTable.setPrefHeight(220);

        TableColumn<Map.Entry<String, List<String>>, String> cHash = new TableColumn<>("SHA-256");
        cHash.setPrefWidth(420);
        cHash.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getKey()));

        TableColumn<Map.Entry<String, List<String>>, String> cCount = new TableColumn<>("Copies");
        cCount.setPrefWidth(90);
        cCount.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().getValue().size())));

        TableColumn<Map.Entry<String, List<String>>, String> cIds = new TableColumn<>("Element IDs");
        cIds.setPrefWidth(320);
        cIds.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.join(", ", c.getValue().getValue())));

        dupTable.getColumns().addAll(cHash, cCount, cIds);
        dupLabel = Fas.muted("0 clusters");

        VBox content = new VBox(16,
                Fas.pageHeader("Analysis", "Home / Analysis"),
                rowA, rowB,
                Fas.cardWithHeader("Duplicate Analysis",
                        "Exact SHA-256 matches \u2014 duplicates are marked, never deleted",
                        new VBox(10, dupLabel, dupTable)));
        content.setPadding(new Insets(20));

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static Region grow(Region r) {
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    @Override
    public void onShow() {
        try {
            bars(typeChart, facades.dashboard().getFileTypeBreakdown(), Fas.PRIMARY);
            bars(sourceChart, facades.dashboard().getSourcesFiltered(), Fas.SECONDARY);
            bars(sideChart, facades.dashboard().getSidesFiltered(), Fas.INFO);
            bars(tagChart, facades.dashboard().getCategoriesFiltered(), Fas.WARNING);

            Map<String, List<String>> dupes = facades.dashboard().getSimilarFiles();
            dupTable.getItems().setAll(dupes.entrySet());
            dupLabel.setText(dupes.size() + (dupes.size() == 1 ? " cluster" : " clusters"));
        } catch (RuntimeException e) {
            typeChart.getChildren().setAll(Fas.emptyState("No data yet."));
        }
    }

    private static void bars(VBox box, Map<String, Integer> data, String color) {
        box.getChildren().clear();
        if (data.isEmpty()) {
            box.getChildren().add(Fas.emptyState("No data yet."));
            return;
        }
        int max = data.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int shown = 0;
        for (Map.Entry<String, Integer> e : data.entrySet()) {
            if (shown++ >= 10) break;
            Label name = new Label(e.getKey());
            name.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
            name.setMinWidth(110);
            name.setMaxWidth(110);
            Region bar = new Region();
            double frac = Math.max(0.04, e.getValue() / (double) max);
            bar.setPrefWidth(190 * frac);
            bar.setMinWidth(190 * frac);
            bar.setPrefHeight(9);
            bar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 5;");
            Label count = new Label(String.format("%,d", e.getValue()));
            count.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Fas.TEXT_LIGHT + ";");
            box.getChildren().add(Fas.row(8, name, bar, Fas.spacer(), count));
        }
    }
}
