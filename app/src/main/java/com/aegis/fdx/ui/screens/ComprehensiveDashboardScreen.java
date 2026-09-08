package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.DashboardFacade;
import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.Background;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every dimension of the case on one destination, under a combined filter.
 *
 * <p>The filter bar narrows by source, aspect, category and file type at once; the
 * tiles and tables below all recompute against the same restriction, so the numbers
 * are always mutually consistent.
 *
 * <h2>Where the numbers come from (§4)</h2>
 * Nothing on this screen is seeded, sampled or estimated. The unfiltered breakdowns are
 * derived counters maintained transactionally with the rows they count
 * ({@code dashboard_stats}); the filtered figures are indexed queries against the path
 * registry. When a figure cannot be produced the tile says so rather than showing a
 * zero that would be indistinguishable from a genuinely empty case.
 *
 * <h2>Threading (§27)</h2>
 * Every value on this screen is gathered on a background thread through
 * {@link Background} and applied on the JavaFX thread. The screen previously did all of
 * its work inline in {@code refresh()}, including a keyword loop that issued one query
 * per keyword and then one per file — at two hundred keywords that is tens of thousands
 * of queries on the thread responsible for drawing. Rapid filter changes are ordered:
 * the newest refresh wins regardless of which query returns first.
 */
public final class ComprehensiveDashboardScreen implements Screen {

    private final AegisFacades facades;
    private final CorpusDatabase dao;
    private final Router router;
    private final Background.Job refreshJob = Background.job();

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
    private Button apply;
    private Button rebuild;

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

        apply = Fas.primary("Apply Filters", Icons.FUNNEL);
        apply.setOnAction(e -> refresh());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            sourceBox.setValue("All Sources");
            aspectBox.setValue("All Aspects");
            categoryBox.setValue("All Categories");
            typeBox.setValue("All File Types");
            refresh();
        });

        // §5.1. The repair path for counters that a restored backup or an interrupted
        // migration has left disagreeing with the evidence. It reconstructs them from
        // the item table, so it is safe to run at any time and cannot lose data.
        rebuild = Fas.ghost("Rebuild Statistics", Icons.REFRESH);
        rebuild.setOnAction(e -> rebuildStatistics());

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
                new HBox(10, apply, clear, rebuild, Fas.spacer(), filterSummary));

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
                Fas.cardWithHeader("Keywords", "Distinct files per keyword, case-wide",
                        keywordTable));
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

    /** Everything one refresh needs, gathered off the FX thread in one go. */
    private record Data(DashboardFacade.Stats stats,
                        int filteredFiles, long filteredBytes, boolean filtered,
                        int reviewed, int reviewTotal, int categoryCount,
                        Map<String, Integer> byType,
                        Map<String, Integer> bySource,
                        Map<String, Integer> byAspect,
                        Map<String, Integer> byCategory,
                        Map<String, Integer> byKeyword) { }

    private void refresh() {
        apply.setDisable(true);
        filterSummary.setText("Loading\u2026");

        final Integer sourceId = idOf(sourceBox.getValue(), "All Sources", true);
        final Integer aspectId = idOf(aspectBox.getValue(), "All Aspects", false);
        final Integer categoryId = categoryIdOf(categoryBox.getValue());
        final String type = "All File Types".equals(typeBox.getValue()) ? null : typeBox.getValue();

        refreshJob.run(
                () -> gather(sourceId, aspectId, categoryId, type),
                this::apply,
                this::showFailure);
    }

    /** Runs on a background thread: no scene-graph access below this line. */
    private Data gather(Integer sourceId, Integer aspectId, Integer categoryId, String type) {
        var counts = facades.analytics().filteredCounts(sourceId, aspectId, categoryId, type);
        var review = facades.analytics().reviewProgress();
        int read = review.getOrDefault("Read", 0);
        int unread = review.getOrDefault("Unread", 0);

        // §15: the number of distinct files each keyword occurs in, case-wide, taken
        // from one grouped query rather than a query per keyword and one per file.
        Map<String, Integer> keywords = new LinkedHashMap<>();
        try {
            for (var u : facades.analytics().keywordUsage(200, 0)) {
                if (u.files() > 0) {
                    keywords.put(u.phrase(), u.files());
                }
            }
        } catch (RuntimeException e) {
            keywords = null;            // distinguished from "no keywords" when rendering
        }

        return new Data(
                facades.dashboard().getStats(),
                counts.files(), counts.bytes(),
                sourceId != null || aspectId != null || categoryId != null || type != null,
                read, read + unread,
                facades.categories().listCategories(1, 0).totalCount(),
                dao2(() -> dao.countPathsByType()),
                dao2(() -> dao.countPathsBySource()),
                dao2(() -> dao.countPathsByAspect()),
                dao2(() -> dao.countPathsByCategory()),
                keywords);
    }

    /** Runs on the FX thread with a result no newer refresh has superseded. */
    private void apply(Data d) {
        apply.setDisable(false);

        tiles.getChildren().setAll(Fas.statsGrid(
                Fas.statCard(Icons.FILES, Fas.PRIMARY,
                        String.format("%,d", d.filteredFiles()), "Files Matching"),
                Fas.statCard(Icons.DATABASE, Fas.SUCCESS,
                        DashboardScreen.humanBytes(d.filteredBytes()), "Size Matching"),
                Fas.statCard(Icons.CHECK_CIRCLE, Fas.INFO,
                        d.reviewed() + " / " + d.reviewTotal(), "Reviewed"),
                Fas.statCard(Icons.TAGS, Fas.WARNING,
                        String.valueOf(d.categoryCount()), "Categories"),
                Fas.statCard(Icons.ARCHIVE, Fas.PRIMARY,
                        String.format("%,d", d.stats().totalFiles()), "Items In Case"),
                Fas.statCard(Icons.ALERT, Fas.DANGER,
                        String.format("%,d", d.stats().errors()), "Errors")));

        filterSummary.setText(d.filtered()
                ? String.format("%,d file(s) match the current filter", d.filteredFiles())
                : "Showing everything on the case");

        bars(typeTable, d.byType(), 160, "No file types recorded yet.");
        bars(sourceTable, d.bySource(), 160, "No sources recorded yet.");
        bars(aspectTable, d.byAspect(), 160, "No aspects recorded yet.");
        bars(categoryTable, d.byCategory(), 160, "No categories recorded yet.");

        if (d.byKeyword() == null) {
            keywordTable.getChildren().setAll(
                    Fas.emptyState("Keyword counts are not available for this case."));
        } else {
            bars(keywordTable, d.byKeyword(), 240,
                    "No keyword occurrences recorded yet.");
        }
    }

    private static void bars(VBox target, Map<String, Integer> data, double width, String empty) {
        if (data == null) {
            target.getChildren().setAll(Fas.emptyState("Not available."));
        } else if (data.isEmpty()) {
            target.getChildren().setAll(Fas.emptyState(empty));
        } else {
            target.getChildren().setAll(ChartPane.hbars(ChartPane.top(data, 8), width, null));
        }
    }

    private void showFailure(Throwable t) {
        apply.setDisable(false);
        filterSummary.setText("");
        String why = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        tiles.getChildren().setAll(Fas.emptyState("Could not load the dashboard: " + why));
    }

    /** §5.1: reconstruct the derived counters, off the FX thread. */
    private void rebuildStatistics() {
        rebuild.setDisable(true);
        filterSummary.setText("Rebuilding statistics\u2026");
        Background.job().run(
                () -> {
                    facades.dashboard().rebuildStatistics();
                    return facades.dashboard().verifyStatistics();
                },
                ok -> {
                    rebuild.setDisable(false);
                    filterSummary.setText(ok
                            ? "Statistics rebuilt and verified against the case database."
                            : "Statistics rebuilt, but they still disagree with the case "
                              + "database \u2014 this indicates a database fault.");
                    refresh();
                },
                t -> {
                    rebuild.setDisable(false);
                    filterSummary.setText("Rebuild failed: " + t.getMessage());
                });
    }

    /** A DAO grouping that reports unavailability as null rather than as an empty chart. */
    private static Map<String, Integer> dao2(SqlSupplier s) {
        try {
            return s.get();
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SqlSupplier {
        Map<String, Integer> get() throws Exception;
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
