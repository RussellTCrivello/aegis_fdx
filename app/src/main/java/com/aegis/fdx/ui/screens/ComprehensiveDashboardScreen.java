package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.ChartPane;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Every dimension of the case on one destination, under a combined filter.
 *
 * <p>The filter bar narrows by source, aspect, category and file type at once; the
 * tiles and tables below all recompute against the same restriction, so the numbers
 * are always mutually consistent.
 */
public final class ComprehensiveDashboardScreen implements Screen {

    private final AegisFacades facades;
    private final CorpusDatabase dao;
    private final Router router;

    private ComboBox<String> sourceBox;
    private ComboBox<String> aspectBox;
    private ComboBox<String> categoryBox;
    private ComboBox<String> typeBox;

    private VBox tiles;
    private VBox typeTable;
    private VBox sourceTable;
    private VBox aspectTable;
    private VBox categoryTable;
    private VBox keywordTable;
    private Label filterSummary;

    public ComprehensiveDashboardScreen(AegisFacades facades, CorpusDatabase dao, Router router) {
        this.facades = facades;
        this.dao = dao;
        this.router = router;
    }

    @Override public String title() { return "Comprehensive"; }
    @Override public String breadcrumb() { return "Home / Analysis / Comprehensive"; }
    @Override public String icon() { return Icons.ARCHIVE; }

    @Override
    public Node build() {
        sourceBox = combo("All Sources");
        aspectBox = combo("All Aspects");
        categoryBox = combo("All Categories");
        typeBox = combo("All File Types");

        Button apply = Fas.primary("Apply Filters", Icons.FUNNEL);
        apply.setOnAction(e -> refresh());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            sourceBox.setValue("All Sources");
            aspectBox.setValue("All Aspects");
            categoryBox.setValue("All Categories");
            typeBox.setValue("All File Types");
            refresh();
        });

        filterSummary = Fas.muted("");
        tiles = new VBox();
        typeTable = new VBox(6);
        sourceTable = new VBox(6);
        aspectTable = new VBox(6);
        categoryTable = new VBox(6);
        keywordTable = new VBox(6);

        VBox filters = Fas.card(
                new HBox(10,
                        Fas.formField("Source", sourceBox),
                        Fas.formField("Aspect", aspectBox),
                        Fas.formField("Category", categoryBox),
                        Fas.formField("File Type", typeBox),
                        Fas.spacer()),
                new HBox(10, apply, clear, Fas.spacer(), filterSummary));

        VBox content = new VBox(16,
                Fas.pageHeader("Comprehensive Dashboard", null),
                filters,
                tiles,
                new HBox(14,
                        grow(Fas.cardWithHeader("File Types", null, typeTable)),
                        grow(Fas.cardWithHeader("Sources", null, sourceTable))),
                new HBox(14,
                        grow(Fas.cardWithHeader("Aspects", null, aspectTable)),
                        grow(Fas.cardWithHeader("Categories", null, categoryTable))),
                Fas.cardWithHeader("Keywords", "Across all classified material", keywordTable));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) { HBox.setHgrow(v, Priority.ALWAYS); return v; }

    private ComboBox<String> combo(String all) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(all));
        c.setValue(all);
        c.setPrefWidth(160);
        return c;
    }

    @Override
    public void onShow() {
        reloadFilterOptions();
        refresh();
    }

    private void reloadFilterOptions() {
        try {
            String keepS = sourceBox.getValue();
            sourceBox.getItems().setAll("All Sources");
            facades.sources().listSources().forEach(s -> sourceBox.getItems().add(s.name()));
            sourceBox.setValue(sourceBox.getItems().contains(keepS) ? keepS : "All Sources");

            String keepA = aspectBox.getValue();
            aspectBox.getItems().setAll("All Aspects");
            facades.aspects().listAspects().forEach(a -> aspectBox.getItems().add(a.name()));
            aspectBox.setValue(aspectBox.getItems().contains(keepA) ? keepA : "All Aspects");

            String keepC = categoryBox.getValue();
            categoryBox.getItems().setAll("All Categories");
            facades.categories().listCategories(500, 0).results()
                    .forEach(c -> categoryBox.getItems().add(c.word()));
            categoryBox.setValue(categoryBox.getItems().contains(keepC) ? keepC : "All Categories");

            String keepT = typeBox.getValue();
            typeBox.getItems().setAll("All File Types");
            dao.countPathsByType().keySet().forEach(t -> typeBox.getItems().add(t));
            typeBox.setValue(typeBox.getItems().contains(keepT) ? keepT : "All File Types");
        } catch (Exception ignored) {
            // filter lists are a convenience; the dashboard still renders without them
        }
    }

    private void refresh() {
        try {
            Integer sourceId = idOf(sourceBox.getValue(), "All Sources", true);
            Integer aspectId = idOf(aspectBox.getValue(), "All Aspects", false);
            Integer categoryId = categoryIdOf(categoryBox.getValue());
            String type = "All File Types".equals(typeBox.getValue()) ? null : typeBox.getValue();

            var counts = facades.analytics().filteredCounts(sourceId, aspectId, categoryId, type);
            var review = facades.analytics().reviewProgress();
            int read = review.getOrDefault("Read", 0);
            int all = read + review.getOrDefault("Unread", 0);

            tiles.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.FILES, Fas.PRIMARY,
                            String.format("%,d", counts.files()), "Files Matching"),
                    Fas.statCard(Icons.DATABASE, Fas.SUCCESS,
                            DashboardScreen.humanBytes(counts.bytes()), "Size Matching"),
                    Fas.statCard(Icons.CHECK_CIRCLE, Fas.INFO,
                            read + " / " + all, "Reviewed"),
                    Fas.statCard(Icons.TAGS, Fas.WARNING,
                            String.valueOf(facades.categories()
                                    .listCategories(1, 0).totalCount()), "Categories")));

            boolean filtered = sourceId != null || aspectId != null
                    || categoryId != null || type != null;
            filterSummary.setText(filtered
                    ? counts.files() + " file(s) match the current filter"
                    : "Showing everything on the case");

            typeTable.getChildren().setAll(
                    ChartPane.hbars(ChartPane.top(dao.countPathsByType(), 8), 160, null));
            sourceTable.getChildren().setAll(
                    ChartPane.hbars(ChartPane.top(dao.countPathsBySource(), 8), 160, null));
            aspectTable.getChildren().setAll(
                    ChartPane.hbars(ChartPane.top(dao.countPathsByAspect(), 8), 160, null));
            categoryTable.getChildren().setAll(
                    ChartPane.hbars(ChartPane.top(dao.countPathsByCategory(), 8), 160, null));

            var kwMap = ChartPane.emptyMap();
            for (var k : facades.keywords().listKeywords(200, 0).results()) {
                int hits = 0;
                for (var fh : facades.analytics().filesForKeyword(k.id(), 500)) {
                    hits += fh.hits();
                }
                if (hits > 0) {
                    kwMap.put(k.keyword(), hits);
                }
            }
            keywordTable.getChildren().setAll(
                    kwMap.isEmpty() ? Fas.emptyState("No keyword hits recorded yet.")
                            : ChartPane.hbars(ChartPane.top(kwMap, 10), 240, null));
        } catch (Exception e) {
            tiles.getChildren().setAll(Fas.emptyState("Could not load: " + e.getMessage()));
        }
    }

    private Integer idOf(String display, String all, boolean source) {
        if (display == null || all.equals(display)) return null;
        try {
            if (source) {
                return facades.sources().listSources().stream()
                        .filter(s -> display.equals(s.name())).map(SourceDto::id)
                        .findFirst().orElse(null);
            }
            return facades.aspects().listAspects().stream()
                    .filter(a -> display.equals(a.name())).map(AspectDto::id)
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer categoryIdOf(String display) {
        if (display == null || "All Categories".equals(display)) return null;
        try {
            return facades.categories().listCategories(500, 0).results().stream()
                    .filter(c -> display.equals(c.word())).map(CategoryDto::id)
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
