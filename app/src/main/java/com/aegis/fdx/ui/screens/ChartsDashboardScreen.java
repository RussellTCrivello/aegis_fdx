package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.ChartPane;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Distribution charts across every dimension of the case.
 *
 * <p>Clicking a segment drills through: a file type opens the library filtered to it,
 * a source opens that source's detail destination.
 */
public final class ChartsDashboardScreen implements Screen {

    private final AegisFacades facades;
    private final CorpusDatabase dao;
    private final Router router;

    private VBox typeChart;
    private VBox sourceChart;
    private VBox aspectChart;
    private VBox categoryChart;
    private VBox statusChart;
    private VBox reviewChart;

    public ChartsDashboardScreen(AegisFacades facades, CorpusDatabase dao, Router router) {
        this.facades = facades;
        this.dao = dao;
        this.router = router;
    }

    @Override public String title() { return "Charts"; }
    @Override public String breadcrumb() { return "Home / Analysis / Charts"; }
    @Override public String icon() { return Icons.CHART; }

    @Override
    public Node build() {
        typeChart = new VBox();
        sourceChart = new VBox();
        aspectChart = new VBox();
        categoryChart = new VBox();
        statusChart = new VBox();
        reviewChart = new VBox();

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        VBox content = new VBox(16,
                Fas.pageHeader("Charts", null, refresh),
                new HBox(14,
                        grow(Fas.cardWithHeader("File Types",
                                "Click a slice to open the library filtered", typeChart)),
                        grow(Fas.cardWithHeader("Processing Status",
                                "Outcome of the ingest pipeline", statusChart))),
                new HBox(14,
                        grow(Fas.cardWithHeader("Files by Source",
                                "Click to open a source", sourceChart)),
                        grow(Fas.cardWithHeader("Files by Aspect", null, aspectChart))),
                new HBox(14,
                        grow(Fas.cardWithHeader("Categories",
                                "Click to see the files", categoryChart)),
                        grow(Fas.cardWithHeader("Review Progress", null, reviewChart))));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) { HBox.setHgrow(v, Priority.ALWAYS); return v; }

    @Override
    public void onShow() {
        try {
            var types = facades.dashboard().getFileTypeBreakdown();
            typeChart.getChildren().setAll(ChartPane.donut(
                    ChartPane.top(types, 7), 150, this::openTypeFilter));

            var stats = facades.dashboard().getStats();
            var statusMap = ChartPane.emptyMap();
            statusMap.put("Indexed", clamp(stats.indexed()));
            statusMap.put("Errors", clamp(stats.errors()));
            statusMap.put("Locked", clamp(stats.locked()));
            statusMap.put("Unsupported", clamp(stats.unsupported()));
            statusMap.values().removeIf(v -> v == 0);
            statusChart.getChildren().setAll(ChartPane.donut(
                    ChartPane.slices(statusMap), 150, null));

            sourceChart.getChildren().setAll(ChartPane.bars(
                    ChartPane.top(dao.countPathsBySource(), 6), 420, 170,
                    this::openSourceByName));

            aspectChart.getChildren().setAll(ChartPane.bars(
                    ChartPane.top(dao.countPathsByAspect(), 6), 420, 170, null));

            categoryChart.getChildren().setAll(ChartPane.hbars(
                    ChartPane.top(dao.countPathsByCategory(), 8), 200, null));

            reviewChart.getChildren().setAll(ChartPane.donut(
                    ChartPane.slices(facades.analytics().reviewProgress()), 150, null));
        } catch (Exception e) {
            typeChart.getChildren().setAll(Fas.emptyState(
                    "Charts unavailable: " + e.getMessage()));
        }
    }

    private void openTypeFilter(String type) {
        router.open("File Library");
        router.openSearch("ext:" + type);
    }

    private void openSourceByName(String name) {
        try {
            facades.sources().listSources().stream()
                    .filter(s -> name.equals(s.name()))
                    .findFirst()
                    .ifPresent(s -> router.openSource(s.id()));
        } catch (RuntimeException ignored) {
            // a chart click that cannot resolve is a no-op, not an error dialog
        }
    }

    /**
     * Chart slices are drawn from an int-keyed map; a case cannot realistically hold
     * more than two billion items in one status, but saturating is still better than
     * silently wrapping negative if one ever did.
     */
    private static int clamp(long v) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, v));
    }
}
