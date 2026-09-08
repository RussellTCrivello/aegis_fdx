package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.HostMetrics;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.ChartPane;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage, index and query performance for this case.
 *
 * <p>The query timer runs a real search against the live index and reports what it
 * actually took, rather than quoting a benchmark figure from elsewhere. The host meters
 * are sampled from the operating system through {@link HostMetrics}; a figure this
 * platform does not expose is labelled as such instead of being filled in with a
 * convincing number.
 */
public final class PerformanceScreen implements Screen {

    private final AegisFacades facades;
    private final CorpusDatabase dao;

    private VBox tiles;
    private GridPane storage;
    private VBox typeChart;
    private TextField probeQuery;
    private TextArea probeOutput;
    private Label memory;
    private VBox hostMeters;
    private Label hostFootnote;
    private Timeline hostPoller;
    private long lastCaseBytes = -1;

    public PerformanceScreen(AegisFacades facades, CorpusDatabase dao, Router router) {
        this.facades = facades;
        this.dao = dao;
    }

    @Override public String title() { return "Performance"; }
    @Override public String breadcrumb() { return "Home / System / Performance"; }
    @Override public String icon() { return Icons.CHART; }

    @Override
    public Node build() {
        tiles = new VBox();
        storage = new GridPane();
        storage.setHgap(28);
        storage.setVgap(9);
        typeChart = new VBox();
        memory = Fas.muted("");
        hostMeters = new VBox(12);
        hostFootnote = Fas.muted("");

        probeQuery = Fas.field("Query to time, e.g. invoice");
        probeQuery.setPrefWidth(260);
        probeQuery.setText("*");
        probeOutput = new TextArea();
        probeOutput.setEditable(false);
        probeOutput.setPrefRowCount(7);
        probeOutput.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 11px;");
        probeOutput.setPromptText("Run a timed query against the live index.");

        Button run = Fas.primary("Run Timed Query", Icons.PLAY);
        run.setOnAction(e -> probe());
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        VBox content = new VBox(16,
                Fas.pageHeader("Performance", null, refresh),
                tiles,
                new HBox(14,
                        grow(Fas.cardWithHeader("Storage", "On-disk footprint", storage)),
                        grow(Fas.cardWithHeader("Indexed Types", null, typeChart))),
                Fas.cardWithHeader("Host Resources",
                        "Sampled from this machine every two seconds while this page is open",
                        new VBox(12, hostMeters, hostFootnote)),
                Fas.cardWithHeader("Query Timing",
                        "Measured against this case's index, right now",
                        new VBox(10, Fas.row(10, probeQuery, run), probeOutput)),
                memory);
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) { HBox.setHgrow(v, Priority.ALWAYS); return v; }

    @Override
    public void onShow() {
        try {
            var folder = facades.liveCase().folder();
            long dbSize = Files.exists(folder.database()) ? Files.size(folder.database()) : 0;
            long indexSize = dirSize(folder.index());
            long textSize = dirSize(folder.text());
            long dataSize = dirSize(folder.data());
            int indexed = facades.liveCase().indexedCount();
            tiles.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.DATABASE, Fas.PRIMARY,
                            String.format("%,d", indexed), "Indexed Items"),
                    Fas.statCard(Icons.HDD_STACK, Fas.SUCCESS,
                            DashboardScreen.humanBytes(indexSize), "Index Size"),
                    Fas.statCard(Icons.FILE_TEXT, Fas.WARNING,
                            DashboardScreen.humanBytes(textSize), "Extracted Text"),
                    Fas.statCard(Icons.FILES, Fas.INFO,
                            DashboardScreen.humanBytes(dbSize), "Database")));

            storage.getChildren().clear();
            int r = 0;
            r = srow(r, "Database", dbSize);
            r = srow(r, "Lucene index", indexSize);
            r = srow(r, "Extracted text", textSize);
            r = srow(r, "Stored payloads", dataSize);
            srow(r, "Total", dbSize + indexSize + textSize + dataSize);

            typeChart.getChildren().setAll(
                    ChartPane.hbars(ChartPane.top(dao.countPathsByType(), 8), 180, null));

            lastCaseBytes = dbSize + indexSize + textSize + dataSize;

            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            memory.setText(String.format(
                    "JVM heap: %s used of %s allocated, %s maximum \u00b7 %d processor(s)",
                    DashboardScreen.humanBytes(used),
                    DashboardScreen.humanBytes(rt.totalMemory()),
                    DashboardScreen.humanBytes(rt.maxMemory()),
                    rt.availableProcessors()));
        } catch (Exception e) {
            tiles.getChildren().setAll(Fas.emptyState("Could not read: " + e.getMessage()));
        }
        sampleHost();
        startPolling();
    }

    /**
     * Stops sampling the host when the operator navigates away.
     *
     * <p>A page nobody is looking at has no business waking up twice a second.
     */
    @Override
    public void onHide() {
        if (hostPoller != null) {
            hostPoller.pause();
        }
    }

    @Override
    public void dispose() {
        if (hostPoller != null) {
            hostPoller.stop();
            hostPoller = null;
        }
    }

    private void startPolling() {
        if (hostPoller == null) {
            hostPoller = new Timeline(new KeyFrame(Duration.seconds(2), e -> sampleHost()));
            hostPoller.setCycleCount(Animation.INDEFINITE);
        }
        hostPoller.play();
    }

    /** Reads the host counters and redraws the meters. Never invents a figure. */
    private void sampleHost() {
        if (hostMeters == null) {
            return;
        }
        Path caseRoot;
        try {
            caseRoot = facades.liveCase().folder().root();
        } catch (RuntimeException e) {
            caseRoot = null;
        }
        HostMetrics.Reading r = HostMetrics.read(caseRoot, lastCaseBytes);

        List<Node> rows = new ArrayList<>();
        rows.add(meter("Process CPU", r.processCpu(),
                r.hasProcessCpu()
                        ? "this application across " + r.processors() + " processor(s)"
                        : "not reported by this operating system",
                Fas.PRIMARY));
        rows.add(meter("System CPU", r.systemCpu(),
                r.hasSystemCpu()
                        ? "whole machine"
                        : "not reported by this operating system",
                Fas.INFO));
        rows.add(meter("JVM heap", r.heapFraction(),
                DashboardScreen.humanBytes(r.heapUsed()) + " used of "
                        + DashboardScreen.humanBytes(r.heapMax()) + " maximum",
                Fas.WARNING));
        rows.add(meter("System memory", r.physicalUsedFraction(),
                r.hasPhysicalMemory()
                        ? DashboardScreen.humanBytes(r.physicalUsed()) + " used of "
                                + DashboardScreen.humanBytes(r.physicalTotal())
                        : "not reported by this Java runtime",
                Fas.SECONDARY));
        rows.add(meter("Case volume", r.volumeUsedFraction(),
                r.hasVolume()
                        ? DashboardScreen.humanBytes(r.volumeTotal() - r.volumeUsable())
                                + " used of " + DashboardScreen.humanBytes(r.volumeTotal())
                                + " \u00b7 " + DashboardScreen.humanBytes(r.volumeUsable())
                                + " free"
                        : "capacity not reported for this filesystem",
                r.hasVolume() && r.volumeUsedFraction() > 0.9 ? Fas.DANGER : Fas.SUCCESS));
        hostMeters.getChildren().setAll(rows);

        StringBuilder foot = new StringBuilder();
        if (r.volumeName() != null) {
            foot.append("Volume ").append(r.volumeName());
        }
        if (lastCaseBytes >= 0 && r.hasVolume()) {
            if (foot.length() > 0) {
                foot.append(" \u00b7 ");
            }
            foot.append("this case occupies ")
                .append(DashboardScreen.humanBytes(lastCaseBytes))
                .append(" (")
                .append(HostMetrics.percent(r.caseFractionOfVolume(), "unknown"))
                .append(" of the volume)");
        }
        if (r.hasLoadAverage()) {
            if (foot.length() > 0) {
                foot.append(" \u00b7 ");
            }
            foot.append(String.format("load average %.2f", r.loadAverage()));
        }
        hostFootnote.setText(foot.length() == 0
                ? "This platform reports no host counters; storage and query figures above are still measured."
                : foot.toString());
    }

    /**
     * One labelled bar.
     *
     * <p>An unmeasured value draws no bar at all — a zero-width bar would read as
     * "0%", which is a claim this application has not earned.
     */
    private Node meter(String label, double fraction, String detail, String accent) {
        Label name = Fas.fieldLabel(label);
        Label value = new Label(HostMetrics.percent(fraction, "not available"));
        value.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: "
                + Fas.TEXT_DARK + ";");
        HBox head = new HBox(8, name, Fas.spacer(), value);
        head.setAlignment(Pos.CENTER_LEFT);

        Region track = new Region();
        track.setPrefHeight(8);
        track.setMinHeight(8);
        track.setStyle("-fx-background-color: #e2e8f0; -fx-background-radius: 4;");

        StackPane bar = new StackPane(track);
        bar.setAlignment(Pos.CENTER_LEFT);
        if (fraction >= 0) {
            Region fill = new Region();
            fill.setPrefHeight(8);
            fill.setMinHeight(8);
            fill.setMaxWidth(Region.USE_PREF_SIZE);
            fill.prefWidthProperty().bind(track.widthProperty().multiply(fraction));
            fill.setStyle("-fx-background-color: " + accent + "; -fx-background-radius: 4;");
            bar.getChildren().add(fill);
        }

        Label sub = Fas.muted(detail);
        return new VBox(4, head, bar, sub);
    }

    private int srow(int r, String label, long bytes) {
        storage.add(Fas.fieldLabel(label), 0, r);
        Label v = new Label(DashboardScreen.humanBytes(bytes));
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        storage.add(v, 1, r);
        return r + 1;
    }

    /** Runs the query several times and reports the spread, not a single sample. */
    private void probe() {
        String q = probeQuery.getText();
        if (q == null || q.isBlank()) {
            return;
        }
        List<Double> timings = new ArrayList<>();
        int total = 0;
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                var page = facades.search().search(SearchCriteria.of(q).limit(100));
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                timings.add(ms);
                total = page.totalCount();
            }
            timings.sort(Double::compareTo);
            double best = timings.get(0);
            double median = timings.get(timings.size() / 2);
            double worst = timings.get(timings.size() - 1);
            sb.append("query      : ").append(q).append('\n')
              .append("matches    : ").append(String.format("%,d", total)).append('\n')
              .append("runs       : ").append(timings.size()).append('\n')
              .append(String.format("best       : %.2f ms%n", best))
              .append(String.format("median     : %.2f ms%n", median))
              .append(String.format("worst      : %.2f ms%n", worst));
        } catch (RuntimeException e) {
            sb.append("query failed: ").append(e.getMessage());
        }
        probeOutput.setText(sb.toString());
    }

    private static long dirSize(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return 0;
            try (var walk = Files.walk(dir)) {
                return walk.filter(Files::isRegularFile).mapToLong(p -> {
                    try { return Files.size(p); } catch (Exception e) { return 0; }
                }).sum();
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
