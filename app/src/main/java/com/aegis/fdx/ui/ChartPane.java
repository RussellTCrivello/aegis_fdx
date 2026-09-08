package com.aegis.fdx.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Charts drawn natively on a JavaFX canvas.
 *
 * <p>Bar, horizontal-bar and donut forms, sharing one palette and one data shape.
 * Drawn directly rather than pulled in from a charting library so the application
 * keeps its fixed offline dependency set.
 *
 * <p>Segments carry tooltips and an optional click handler, so a chart is a way into
 * the data rather than a static picture.
 */
public final class ChartPane {

    /** Palette, ordered so adjacent series stay distinguishable. */
    private static final String[] PALETTE = {
            "#4f46e5", "#06b6d4", "#10b981", "#f59e0b", "#ef4444",
            "#8b5cf6", "#3b82f6", "#14b8a6", "#f97316", "#ec4899"
    };

    private ChartPane() {
    }

    /** One labelled value. */
    public record Slice(String label, double value) {
    }

    public static List<Slice> slices(Map<String, Integer> data) {
        List<Slice> out = new ArrayList<>();
        data.forEach((k, v) -> out.add(new Slice(k, v)));
        return out;
    }

    // ------------------------------------------------------------- donut

    /**
     * A donut chart with a legend.
     *
     * @param onSelect called with the slice label when a legend entry is clicked;
     *                 may be null
     */
    public static Node donut(List<Slice> data, double size, Consumer<String> onSelect) {
        if (data == null || data.isEmpty()) {
            return emptyChart("No data to chart yet.");
        }
        double total = data.stream().mapToDouble(Slice::value).sum();
        if (total <= 0) {
            return emptyChart("All values are zero.");
        }

        Canvas canvas = new Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();
        double pad = 6;
        double d = size - pad * 2;
        double start = 90;   // start at twelve o'clock

        for (int i = 0; i < data.size(); i++) {
            double extent = -360.0 * (data.get(i).value() / total);
            g.setFill(Color.web(PALETTE[i % PALETTE.length]));
            g.fillArc(pad, pad, d, d, start, extent, javafx.scene.shape.ArcType.ROUND);
            start += extent;
        }
        // punch the middle out
        g.setFill(Color.web("#ffffff"));
        double hole = d * 0.55;
        double ho = pad + (d - hole) / 2;
        g.fillOval(ho, ho, hole, hole);

        g.setFill(Color.web("#0f172a"));
        g.setFont(Font.font("System", FontWeight.BOLD, 15));
        String totalText = String.format("%,d", (long) total);
        g.fillText(totalText, size / 2 - totalText.length() * 4.2, size / 2 + 2);
        g.setFill(Color.web("#94a3b8"));
        g.setFont(Font.font("System", 10));
        g.fillText("total", size / 2 - 12, size / 2 + 16);

        HBox row = new HBox(16, canvas, legend(data, total, onSelect));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Node legend(List<Slice> data, double total, Consumer<String> onSelect) {
        VBox box = new VBox(5);
        for (int i = 0; i < data.size(); i++) {
            Slice s = data.get(i);
            Region swatch = new Region();
            swatch.setPrefSize(11, 11);
            swatch.setMinSize(11, 11);
            swatch.setStyle("-fx-background-color: " + PALETTE[i % PALETTE.length]
                    + "; -fx-background-radius: 3;");
            Label name = new Label(s.label());
            name.setStyle("-fx-font-size: 11px; -fx-text-fill: #1e293b;");
            Label pct = new Label(String.format("%,d  (%.0f%%)",
                    (long) s.value(), 100 * s.value() / total));
            pct.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");

            HBox entry = new HBox(8, swatch, name, Fas.spacer(), pct);
            entry.setAlignment(Pos.CENTER_LEFT);
            entry.setMinWidth(230);
            if (onSelect != null) {
                entry.setStyle("-fx-cursor: hand;");
                entry.setOnMouseClicked(e -> onSelect.accept(s.label()));
                Tooltip.install(entry, new Tooltip("Show " + s.label()));
            }
            box.getChildren().add(entry);
        }
        return box;
    }

    // --------------------------------------------------------- vertical bars

    /** A vertical bar chart with value labels and an axis baseline. */
    public static Node bars(List<Slice> data, double width, double height,
                            Consumer<String> onSelect) {
        if (data == null || data.isEmpty()) {
            return emptyChart("No data to chart yet.");
        }
        double max = data.stream().mapToDouble(Slice::value).max().orElse(1);
        if (max <= 0) {
            max = 1;
        }

        HBox chart = new HBox(10);
        chart.setAlignment(Pos.BOTTOM_LEFT);
        chart.setPrefHeight(height);
        chart.setPadding(new Insets(4, 4, 0, 4));

        for (int i = 0; i < data.size(); i++) {
            Slice s = data.get(i);
            double h = Math.max(3, (height - 46) * (s.value() / max));

            Label value = new Label(String.format("%,d", (long) s.value()));
            value.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b;");

            Region bar = new Region();
            bar.setPrefSize(38, h);
            bar.setMinSize(38, h);
            bar.setMaxSize(38, h);
            bar.setStyle("-fx-background-color: " + PALETTE[i % PALETTE.length]
                    + "; -fx-background-radius: 5 5 0 0;");
            Tooltip.install(bar, new Tooltip(s.label() + ": " + (long) s.value()));

            Label name = new Label(trim(s.label(), 9));
            name.setStyle("-fx-font-size: 10px; -fx-text-fill: #1e293b;");
            name.setMaxWidth(48);

            VBox col = new VBox(3, value, bar, name);
            col.setAlignment(Pos.BOTTOM_CENTER);
            if (onSelect != null) {
                col.setStyle("-fx-cursor: hand;");
                col.setOnMouseClicked(e -> onSelect.accept(s.label()));
            }
            chart.getChildren().add(col);
        }

        Region axis = new Region();
        axis.setPrefHeight(1);
        axis.setMaxWidth(Double.MAX_VALUE);
        axis.setStyle("-fx-background-color: #e2e8f0;");

        VBox out = new VBox(0, chart, axis);
        out.setPrefWidth(width);
        return out;
    }

    // ------------------------------------------------------- horizontal bars

    /** A horizontal bar list, which reads better for long labels. */
    public static Node hbars(List<Slice> data, double barWidth, Consumer<String> onSelect) {
        if (data == null || data.isEmpty()) {
            return emptyChart("No data yet.");
        }
        double max = data.stream().mapToDouble(Slice::value).max().orElse(1);
        if (max <= 0) {
            max = 1;
        }
        VBox box = new VBox(6);
        for (int i = 0; i < data.size(); i++) {
            Slice s = data.get(i);
            Label name = new Label(trim(s.label(), 22));
            name.setStyle("-fx-font-size: 11px; -fx-text-fill: #1e293b;");
            name.setMinWidth(140);
            name.setMaxWidth(140);

            Region bar = new Region();
            double w = Math.max(4, barWidth * (s.value() / max));
            bar.setPrefSize(w, 10);
            bar.setMinSize(w, 10);
            bar.setStyle("-fx-background-color: " + PALETTE[i % PALETTE.length]
                    + "; -fx-background-radius: 5;");
            Tooltip.install(bar, new Tooltip(s.label() + ": " + (long) s.value()));

            Label value = new Label(String.format("%,d", (long) s.value()));
            value.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");

            HBox row = new HBox(10, name, bar, Fas.spacer(), value);
            row.setAlignment(Pos.CENTER_LEFT);
            if (onSelect != null) {
                row.setStyle("-fx-cursor: hand;");
                row.setOnMouseClicked(e -> onSelect.accept(s.label()));
            }
            box.getChildren().add(row);
        }
        return box;
    }

    /** Small coloured tiles, for compact distributions. */
    public static Node chips(Map<String, Integer> data, Consumer<String> onSelect) {
        if (data == null || data.isEmpty()) {
            return emptyChart("No data yet.");
        }
        FlowPane pane = new FlowPane(8, 8);
        int i = 0;
        for (Map.Entry<String, Integer> e : data.entrySet()) {
            Label chip = new Label(e.getKey() + "  " + e.getValue());
            chip.setStyle("-fx-background-color: " + PALETTE[i++ % PALETTE.length]
                    + "22; -fx-text-fill: #1e293b; -fx-font-size: 11px;"
                    + " -fx-padding: 5 11 5 11; -fx-background-radius: 20;");
            if (onSelect != null) {
                chip.setStyle(chip.getStyle() + " -fx-cursor: hand;");
                chip.setOnMouseClicked(ev -> onSelect.accept(e.getKey()));
            }
            pane.getChildren().add(chip);
        }
        return pane;
    }

    /** Sorts descending and keeps the top {@code n}, folding the rest into "Other". */
    public static List<Slice> top(Map<String, Integer> data, int n) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(data.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Slice> out = new ArrayList<>();
        int other = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (i < n) {
                out.add(new Slice(entries.get(i).getKey(), entries.get(i).getValue()));
            } else {
                other += entries.get(i).getValue();
            }
        }
        if (other > 0) {
            out.add(new Slice("Other", other));
        }
        return out;
    }

    /**
     * As {@link #top(Map, int)}, for the {@code long} counters the derived dashboard
     * statistics produce. Slice values saturate rather than wrap: a chart is a
     * proportion, and a negative bar from an overflowed int would be a lie about the
     * data rather than a rendering glitch.
     */
    public static List<Slice> topLong(Map<String, Long> data, int n) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(data.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<Slice> out = new ArrayList<>();
        long other = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (i < n) {
                out.add(new Slice(entries.get(i).getKey(), saturate(entries.get(i).getValue())));
            } else {
                other += entries.get(i).getValue();
            }
        }
        if (other > 0) {
            out.add(new Slice("Other", saturate(other)));
        }
        return out;
    }

    private static int saturate(long v) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, v));
    }

    public static Map<String, Integer> emptyMap() {
        return new LinkedHashMap<>();
    }

    private static Node emptyChart(String message) {
        Label l = new Label(message);
        l.getStyleClass().add("empty-state");
        StackPane sp = new StackPane(l);
        sp.setPrefHeight(120);
        HBox.setHgrow(sp, Priority.ALWAYS);
        return sp;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }
}
