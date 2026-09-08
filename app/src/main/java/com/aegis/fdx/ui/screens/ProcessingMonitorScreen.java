package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.ui.ChartPane;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Live state of the ingest pipeline: queue depth, worker activity and outcomes.
 *
 * <p>Polls while visible so an operator can watch a long run progress, and stops
 * polling when the destination is left so it costs nothing in the background.
 */
public final class ProcessingMonitorScreen implements Screen {

    private final AegisFacades facades;

    private VBox tiles;
    private ProgressBar progress;
    private Label progressLabel;
    private Label runState;
    private GridPane workers;
    private VBox statusChart;
    private Timeline poller;
    private Button pauseBtn;
    private Button cancelBtn;

    public ProcessingMonitorScreen(AegisFacades facades, Router router) {
        this.facades = facades;
    }

    @Override public String title() { return "Processing"; }
    @Override public String breadcrumb() { return "Home / System / Processing"; }
    @Override public String icon() { return Icons.PLAY; }

    @Override
    public Node build() {
        tiles = new VBox();
        progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progressLabel = Fas.muted("");
        runState = Fas.muted("Idle");
        workers = new GridPane();
        workers.setHgap(28);
        workers.setVgap(9);
        statusChart = new VBox();

        pauseBtn = Fas.outline("Pause", Icons.PAUSE);
        pauseBtn.setDisable(true);
        pauseBtn.setOnAction(e -> {
            facades.liveCase().pauseIngest();
            runState.setText("Paused");
        });

        cancelBtn = Fas.danger("Cancel", Icons.STOP);
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> {
            facades.liveCase().cancelIngest();
            runState.setText("Cancelling\u2026");
        });

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> poll());

        VBox content = new VBox(16,
                Fas.pageHeader("Processing Monitor", null, pauseBtn, cancelBtn, refresh),
                tiles,
                Fas.cardWithHeader("Current Run", "Live while the pipeline is working",
                        new VBox(10, Fas.row(10, runState), progress, progressLabel)),
                new HBox(14,
                        grow(Fas.cardWithHeader("Queue and Workers", null, workers)),
                        grow(Fas.cardWithHeader("Outcomes", null, statusChart))));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) { HBox.setHgrow(v, Priority.ALWAYS); return v; }

    @Override
    public void onShow() {
        poll();
        if (poller == null) {
            poller = new Timeline(new KeyFrame(Duration.seconds(2), e -> poll()));
            poller.setCycleCount(Animation.INDEFINITE);
        }
        poller.play();
    }

    /** Stops polling when the operator leaves this destination. */
    public void onHide() {
        if (poller != null) {
            poller.pause();
        }
    }

    /**
     * Stops the poller for good.
     *
     * <p>An INDEFINITE {@link Timeline} keeps the JavaFX toolkit alive, so leaving it
     * running would stop the application from exiting cleanly.
     */
    public void dispose() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
    }

    private void poll() {
        try {
            var counts = facades.liveCase().statusCounts();
            int indexed = counts.getOrDefault(ItemStatus.INDEXED, 0);
            int pending = counts.getOrDefault(ItemStatus.PENDING, 0);
            int processing = counts.getOrDefault(ItemStatus.PROCESSING, 0);
            int errors = counts.getOrDefault(ItemStatus.ERROR, 0);
            int locked = counts.getOrDefault(ItemStatus.LOCKED, 0);
            int unsupported = counts.getOrDefault(ItemStatus.UNSUPPORTED, 0);
            int done = indexed + errors + locked + unsupported;
            int total = done + pending + processing;

            boolean running = facades.liveCase().ingestRunning();
            pauseBtn.setDisable(!running);
            cancelBtn.setDisable(!running);
            runState.setText(running
                    ? "Running \u2014 " + processing + " in flight"
                    : (total == 0 ? "Idle \u2014 nothing ingested yet" : "Idle"));

            progress.setProgress(total == 0 ? 0 : (double) done / total);
            progressLabel.setText(total == 0 ? ""
                    : String.format("%,d of %,d elements finished (%,d queued)",
                            done, total, pending));

            tiles.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.CHECK_CIRCLE, Fas.SUCCESS,
                            String.format("%,d", indexed), "Indexed"),
                    Fas.statCard(Icons.PLAY, Fas.INFO,
                            String.format("%,d", processing), "In Flight"),
                    Fas.statCard(Icons.LIST, Fas.WARNING,
                            String.format("%,d", pending), "Queued"),
                    Fas.statCard(Icons.INFO, Fas.DANGER,
                            String.format("%,d", errors + locked + unsupported),
                            "Not Indexed")));

            workers.getChildren().clear();
            int r = 0;
            r = wrow(r, "Worker threads", String.valueOf(
                    facades.liveCase().settings().workers()));
            r = wrow(r, "Available processors",
                    String.valueOf(Runtime.getRuntime().availableProcessors()));
            r = wrow(r, "OCR enabled",
                    facades.liveCase().settings().ocrEnabled() ? "yes" : "no");
            r = wrow(r, "Deduplication",
                    String.valueOf(facades.liveCase().settings().dedupeScope()));
            r = wrow(r, "Max container depth",
                    String.valueOf(facades.liveCase().settings().maxArchiveDepth()));
            wrow(r, "Queue state", running ? "active" : "drained");

            var chart = ChartPane.emptyMap();
            if (indexed > 0) chart.put("Indexed", indexed);
            if (errors > 0) chart.put("Error", errors);
            if (locked > 0) chart.put("Locked", locked);
            if (unsupported > 0) chart.put("Unsupported", unsupported);
            if (pending > 0) chart.put("Pending", pending);
            statusChart.getChildren().setAll(chart.isEmpty()
                    ? Fas.emptyState("Nothing processed yet.")
                    : ChartPane.donut(ChartPane.slices(chart), 140, null));
        } catch (Exception e) {
            runState.setText("Could not read pipeline state: " + e.getMessage());
        }
    }

    private int wrow(int r, String label, String value) {
        workers.add(Fas.fieldLabel(label), 0, r);
        Label v = new Label(value);
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        workers.add(v, 1, r);
        return r + 1;
    }
}
