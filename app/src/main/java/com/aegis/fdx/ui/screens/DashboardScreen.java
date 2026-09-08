package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.DashboardFacade;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Map;

/**
 * Python parity: {@code templates/Analysis/dashboard.html} and the
 * {@code /api/dashboard/*} routes.
 *
 * <p>Reproduces the Python stat grid in the same order with the same captions —
 * Total Files, Processed Files, File Types, Total Words, Categories, Keywords,
 * Storage Used, Database Size — followed by the breakdown panels.
 */
public final class DashboardScreen implements Screen {

    private final AegisFacades facades;

    private VBox cTotal, cProcessed, cTypes, cWords, cCategories, cKeywords, cStorage, cDbSize;
    private VBox typeBreakdown, sourceBreakdown, statusBreakdown;

    public DashboardScreen(AegisFacades facades) {
        this.facades = facades;
    }

    @Override
    public String title() {
        return "Dashboard";
    }

    @Override
    public String breadcrumb() {
        return "Home / Dashboard";
    }

    @Override
    public String icon() {
        return Icons.SPEEDOMETER2;
    }

    @Override
    public Node build() {
        // Python stat grid, same order and captions as dashboard.html
        cTotal = Fas.statCard(Icons.FILES, Fas.PRIMARY, "0", "Total Files");
        cProcessed = Fas.statCard(Icons.CHECK_CIRCLE, Fas.SUCCESS, "0", "Processed Files");
        cTypes = Fas.statCard(Icons.FOLDER, Fas.WARNING, "0", "File Types");
        cWords = Fas.statCard(Icons.FONTS, Fas.INFO, "0", "Total Words");
        cCategories = Fas.statCard(Icons.TAGS, Fas.PRIMARY, "0", "Categories");
        cKeywords = Fas.statCard(Icons.KEY, Fas.WARNING, "0", "Keywords");
        cStorage = Fas.statCard(Icons.DATABASE, Fas.SUCCESS, "0 B", "Storage Used");
        cDbSize = Fas.statCard(Icons.HDD_STACK, Fas.INFO, "0", "Contents Stored");

        HBox rowA = Fas.statsGrid(cTotal, cProcessed, cTypes, cWords);
        HBox rowB = Fas.statsGrid(cCategories, cKeywords, cStorage, cDbSize);

        typeBreakdown = new VBox(7);
        sourceBreakdown = new VBox(7);
        statusBreakdown = new VBox(7);

        HBox panels = new HBox(14,
                grow(Fas.cardWithHeader("File Types", "Distribution by extension", typeBreakdown)),
                grow(Fas.cardWithHeader("Sources", "Files per source", sourceBreakdown)),
                grow(Fas.cardWithHeader("Processing Status", "Pipeline outcome", statusBreakdown)));

        VBox content = new VBox(16,
                Fas.pageHeader("Dashboard", "Home / Dashboard"),
                rowA, rowB, panels);
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
            DashboardFacade.Stats s = facades.dashboard().getStats();
            Fas.setStat(cTotal, String.format("%,d", s.totalFiles()));
            Fas.setStat(cProcessed, String.format("%,d", s.indexed()));
            Fas.setStat(cStorage, humanBytes(s.totalBytes()));

            Map<String, Integer> types = facades.dashboard().getFileTypeBreakdown();
            Fas.setStat(cTypes, String.valueOf(types.size()));
            fill(typeBreakdown, types, Fas.PRIMARY);

            fill(sourceBreakdown, facades.dashboard().getSourcesFiltered(), Fas.SECONDARY);

            Fas.setStat(cCategories,
                    String.valueOf(facades.categories().listCategories(1, 0).totalCount()));
            Fas.setStat(cKeywords,
                    String.valueOf(facades.keywords().listKeywords(1, 0).totalCount()));
            Fas.setStat(cWords,
                    String.format("%,d", facades.words().getWords(1, 0).totalCount()));
            Fas.setStat(cDbSize,
                    String.format("%,d", facades.contents().getPaths(null, null, null, null, 1, 0)
                            .totalCount()));

            statusBreakdown.getChildren().setAll(
                    statusRow("Indexed", s.indexed(), Fas.SUCCESS),
                    statusRow("Errors", s.errors(), Fas.DANGER),
                    statusRow("Locked", s.locked(), Fas.WARNING),
                    statusRow("Unsupported", s.unsupported(), Fas.INFO),
                    statusRow("Duplicate clusters", s.duplicateClusters(), Fas.TEXT_LIGHT));
        } catch (RuntimeException e) {
            typeBreakdown.getChildren().setAll(Fas.emptyState("No data yet — ingest files first."));
        }
    }

    private static Node statusRow(String label, long value, String color) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        Label v = new Label(String.format("%,d", value));
        v.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        return Fas.row(8, l, Fas.spacer(), v);
    }

    private static void fill(VBox box, Map<String, Integer> data, String color) {
        box.getChildren().clear();
        if (data.isEmpty()) {
            box.getChildren().add(Fas.emptyState("No data yet."));
            return;
        }
        int max = data.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int shown = 0;
        for (Map.Entry<String, Integer> e : data.entrySet()) {
            if (shown++ >= 8) {
                break;
            }
            Label name = new Label(e.getKey());
            name.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
            name.setMinWidth(90);
            name.setMaxWidth(90);

            Region bar = new Region();
            double frac = Math.max(0.04, e.getValue() / (double) max);
            bar.setPrefWidth(140 * frac);
            bar.setMinWidth(140 * frac);
            bar.setPrefHeight(8);
            bar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 4;");

            Label count = new Label(String.format("%,d", e.getValue()));
            count.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Fas.TEXT_LIGHT + ";");

            box.getChildren().add(Fas.row(8, name, bar, Fas.spacer(), count));
        }
    }

    /** Python renders "Storage Used" in human units. */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, u[i]);
    }
}
