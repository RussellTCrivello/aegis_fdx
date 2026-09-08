package com.aegis.fdx.ui.screens;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Python parity: {@code templates/Settings/settings.html} — the theme colour palette,
 * application identity and processing options.
 *
 * <p>The colour swatches show the live design tokens this UI is built from, which are
 * the same tokens the Python stylesheet defines.
 *
 * <p>Every control on this page either changes something or is presented as
 * information. Processing options are written to {@code settings.properties} in the
 * case folder the moment they change, and reloaded when the case is reopened, so a
 * setting an examiner chose is still in force after a restart. Values the application
 * cannot yet act on — an alternative interface language, for instance — are shown as
 * facts about the installation rather than as controls that quietly do nothing.
 */
public final class SettingsScreen implements Screen {

    private final AegisFacades facades;
    private final CaseSettings settings;
    private final Path settingsFile;
    private Label savedNote;

    public SettingsScreen(AegisFacades facades, CaseSettings settings) {
        this(facades, settings, null);
    }

    public SettingsScreen(AegisFacades facades, CaseSettings settings, Path settingsFile) {
        this.facades = facades;
        this.settings = settings;
        this.settingsFile = settingsFile;
    }

    /**
     * Persists the current settings and reports the outcome on screen.
     *
     * <p>Silence would be indistinguishable from a control that does nothing, so both
     * success and failure are stated, with the path the file was written to.
     */
    private void persist(String what) {
        if (savedNote == null) {
            return;
        }
        if (settingsFile == null) {
            savedNote.setText(what + " applied to this session; no case folder to save into");
            return;
        }
        try {
            settings.saveTo(settingsFile);
            savedNote.setText(what + " saved to " + settingsFile + " at "
                    + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        } catch (Exception e) {
            savedNote.setText("Could not save settings to " + settingsFile + ": "
                    + e.getMessage());
        }
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public String icon() {
        return Icons.GEAR;
    }

    @Override
    public Node build() {
        // ---- installation facts (Python: System settings) ----
        // The reference offers an editable application name and a language picker. The
        // name is fixed here, and only the English catalogue is installed, so both are
        // shown as read-only facts instead of controls that would change nothing.
        String caseName;
        String casePath;
        try {
            caseName = facades.liveCase().folder().name();
            casePath = facades.liveCase().folder().root().toString();
        } catch (RuntimeException e) {
            caseName = "(no case open)";
            casePath = "";
        }
        GridPane sys = grid();
        sys.add(Fas.formField("Application", readOnly("File Analysis System")), 0, 0);
        sys.add(Fas.formField("Interface Language",
                readOnly("English (the only catalogue installed)")), 1, 0);
        sys.add(Fas.formField("Open Case", readOnly(caseName)), 0, 1);
        sys.add(Fas.formField("Case Folder", readOnly(casePath)), 1, 1);

        // ---- theme colours (Python: Theme settings) ----
        VBox theme = new VBox(10,
                swatchRow("Primary Color", Fas.PRIMARY, "Secondary Color", Fas.SECONDARY),
                swatchRow("Success", Fas.SUCCESS, "Danger", Fas.DANGER),
                swatchRow("Warning", Fas.WARNING, "Info", Fas.INFO),
                swatchRow("Text Dark", Fas.TEXT_DARK, "Text Light", Fas.TEXT_LIGHT),
                swatchRow("Sidebar Background", "#1e293b", "Sidebar Text", Fas.SIDEBAR_TEXT),
                swatchRow("Background Light", "#f8fafc", "Border Color", "#e2e8f0"));

        // ---- processing (AEGIS engine settings, surfaced Python-style) ----
        CheckBox ocr = new CheckBox("Enable OCR for images and scanned PDFs");
        ocr.setSelected(settings.ocrEnabled());
        ocr.setOnAction(e -> {
            settings.ocrEnabled(ocr.isSelected());
            persist("OCR " + (ocr.isSelected() ? "enabled" : "disabled") + ",");
        });

        ComboBox<CaseSettings.DedupeScope> dedupe = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(
                        CaseSettings.DedupeScope.values()));
        dedupe.setValue(settings.dedupeScope());
        dedupe.setOnAction(e -> {
            settings.dedupeScope(dedupe.getValue());
            persist("Deduplication scope " + dedupe.getValue() + ",");
        });

        Spinner<Integer> depth = new Spinner<>(1, 50, settings.maxArchiveDepth());
        depth.setEditable(true);
        depth.valueProperty().addListener((o, a, b) -> {
            settings.maxArchiveDepth(b);
            persist("Container depth " + b + ",");
        });
        Spinner<Integer> workers = new Spinner<>(1, 64, settings.workers());
        workers.setEditable(true);
        workers.valueProperty().addListener((o, a, b) -> {
            settings.workers(b);
            persist("Worker threads " + b + ",");
        });

        GridPane proc = grid();
        proc.add(Fas.formField("Deduplication Scope", dedupe), 0, 0);
        proc.add(Fas.formField("Max Container Depth", depth), 1, 0);
        proc.add(Fas.formField("Worker Threads", workers), 0, 1);
        proc.add(new VBox(5, Fas.fieldLabel("OCR"), ocr), 1, 1);

        savedNote = Fas.muted(settingsFile == null
                ? "Processing options apply to this session."
                : "Processing options are saved to " + settingsFile
                        + " and reapplied when this case is reopened.");

        VBox content = new VBox(16,
                Fas.pageHeader("Settings", "Home / Settings"),
                Fas.cardWithHeader("System", "Application identity and language", sys),
                Fas.cardWithHeader("Theme Colors",
                        "Design tokens shared with the reference stylesheet", theme),
                Fas.cardWithHeader("Processing",
                        "Ingest engine options \u2014 applied to the next run, and kept "
                                + "with the case",
                        new VBox(12, proc, savedNote)));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    /** A value the operator cannot change, presented as a value rather than a field. */
    private static Label readOnly(String text) {
        Label l = new Label(text == null || text.isBlank() ? "\u2014" : text);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        return l;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(18);
        g.setVgap(12);
        return g;
    }

    private static HBox swatchRow(String l1, String c1, String l2, String c2) {
        HBox h = new HBox(14, swatch(l1, c1), swatch(l2, c2));
        return h;
    }

    private static VBox swatch(String label, String color) {
        Region chip = new Region();
        chip.setPrefSize(38, 26);
        chip.setMinSize(38, 26);
        chip.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 6;"
                + " -fx-border-color: #cbd5e1; -fx-border-radius: 6;");
        Label name = new Label(label);
        name.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: "
                + Fas.TEXT_LIGHT + ";");
        Label hex = new Label(color);
        hex.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Fas.TEXT_MUTED + ";");
        VBox text = new VBox(1, name, hex);
        HBox row = new HBox(10, chip, text);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox v = new VBox(row);
        HBox.setHgrow(v, Priority.ALWAYS);
        return v;
    }

    @Override
    public void onShow() {
    }
}
