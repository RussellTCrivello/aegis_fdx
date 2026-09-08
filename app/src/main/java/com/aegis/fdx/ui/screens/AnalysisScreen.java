package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * Analysis hub: the entity navigation centre of the case.
 *
 * <p>Rather than reporting only "there are N files", this destination shows every
 * analytical dimension extracted from those files — Categories, Keywords, Titles,
 * Sources, Sides (Aspects), Relations and Geolocation — each with its live count.
 * Clicking a card opens the corresponding collection. Below the hub, distribution
 * charts and duplicate analysis summarise the underlying material.
 */
public final class AnalysisScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private VBox cardCategories;
    private VBox cardKeywords;
    private VBox cardTitles;
    private VBox cardSources;
    private VBox cardSides;
    private VBox cardRelations;
    private VBox cardGeo;

    private VBox typeChart;
    private VBox sourceChart;
    private VBox sideChart;
    private VBox tagChart;
    private TableView<Map.Entry<String, List<String>>> dupTable;
    private Label dupLabel;

    public AnalysisScreen(AegisFacades facades) {
        this(facades, null);
    }

    public AnalysisScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Analysis";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis";
    }

    @Override
    public String icon() {
        return Icons.ARCHIVE;
    }

    @Override
    public Node build() {
        cardCategories = Fas.hubCard(Icons.TAGS, Fas.PRIMARY, "Categories",
                "0 items", "Classification taxonomy across the case",
                () -> router.open("Categories"));
        cardKeywords = Fas.hubCard(Icons.KEY, Fas.WARNING, "Keywords",
                "0 items", "Important terms associated with documents",
                () -> router.open("Keywords"));
        cardTitles = Fas.hubCard(Icons.CARD_HEADING, Fas.INFO, "Titles",
                "0 items", "Distinct document titles",
                () -> router.open("Titles"));
        cardSources = Fas.hubCard(Icons.BUILDING, Fas.SECONDARY, "Sources",
                "0 items", "Originators of the material",
                () -> router.open("Sources"));
        cardSides = Fas.hubCard(Icons.DIAGRAM3, "#8b5cf6", "Sides",
                "0 items", "Parties and groupings (Aspects)",
                () -> router.open("Aspects"));
        cardRelations = Fas.hubCard(Icons.SHARE, Fas.SUCCESS, "Relations",
                "0 items", "Relationships between extracted entities",
                () -> router.open("Relations"));
        cardGeo = Fas.hubCard(Icons.GEO, "#ec4899", "Geolocation",
                "0 items", "Locations extracted from documents",
                () -> router.open("Geolocation"));

        FlowPane hub = new FlowPane(14, 14,
                cardCategories, cardKeywords, cardTitles, cardSources,
                cardSides, cardRelations, cardGeo);
        hub.setPrefWrapLength(1200);

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
        dupTable.setPrefHeight(200);

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
                Fas.cardWithHeader("Analytical Dimensions",
                        "Every dimension extracted from the case — click a card to explore it",
                        hub),
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
        // Hub counts — each from the authoritative store, never seeded.
        try {
            int cats = facades.categories().listCategories(1, 0).totalCount();
            Fas.setHubCount(cardCategories, cats + (cats == 1 ? " item" : " items"));
        } catch (RuntimeException e) {
            Fas.setHubCount(cardCategories, "0 items");
        }
        try {
            int kws = facades.keywords().listKeywords(1, 0).totalCount();
            Fas.setHubCount(cardKeywords, kws + (kws == 1 ? " item" : " items"));
        } catch (RuntimeException e) {
            Fas.setHubCount(cardKeywords, "0 items");
        }
        // Titles and geolocation share one registry scan: distinct file names and
        // distinct non-blank coordinates.
        try {
            var paths = facades.contents().getPaths(null, null, null, null, 100_000, 0).results();
            java.util.Set<String> titles = new java.util.HashSet<>();
            java.util.Set<String> coords = new java.util.HashSet<>();
            for (var p : paths) {
                if (p.fileName() != null && !p.fileName().isBlank()) {
                    titles.add(p.fileName());
                }
                if (p.coordinates() != null && !p.coordinates().isBlank()) {
                    coords.add(p.coordinates().trim());
                }
            }
            Fas.setHubCount(cardTitles, titles.size() + (titles.size() == 1 ? " item" : " items"));
            Fas.setHubCount(cardGeo, coords.size() + (coords.size() == 1 ? " item" : " items"));
        } catch (RuntimeException e) {
            Fas.setHubCount(cardTitles, "0 items");
            Fas.setHubCount(cardGeo, "0 items");
        }
        try {
            int sources = facades.sources().listSources().size();
            Fas.setHubCount(cardSources, sources + (sources == 1 ? " item" : " items"));
        } catch (RuntimeException e) {
            Fas.setHubCount(cardSources, "0 items");
        }
        try {
            int sides = facades.aspects().listAspects().size();
            Fas.setHubCount(cardSides, sides + (sides == 1 ? " item" : " items"));
        } catch (RuntimeException e) {
            Fas.setHubCount(cardSides, "0 items");
        }
        try {
            Map<String, Integer> totals = facades.relationships().totals();
            int edges = totals.getOrDefault("keyword_edges", 0)
                    + totals.getOrDefault("word_edges", 0)
                    + totals.getOrDefault("category_edges", 0);
            Fas.setHubCount(cardRelations, edges + (edges == 1 ? " item" : " items"));
        } catch (RuntimeException e) {
            Fas.setHubCount(cardRelations, "0 items");
        }

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
