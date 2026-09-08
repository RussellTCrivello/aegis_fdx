package com.aegis.fdx.ui.screens;

import com.aegis.fdx.engine.IntegrityVerifier;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Case and database status, plus integrity verification.
 *
 * <p>The reference application's setup destination configures a database connection.
 * A case here is self-contained, so this shows the equivalent information — where the
 * case lives, what the schema holds, whether the stored evidence still verifies —
 * rather than asking for connection details that do not exist.
 */
public final class SetupScreen implements Screen {

    private final AegisFacades facades;
    private final CorpusDatabase dao;

    private GridPane info;
    private VBox tables;
    private TextArea verifyOutput;
    private ProgressIndicator spinner;
    private Button verifyButton;
    private Label statusLabel;

    public SetupScreen(AegisFacades facades, CorpusDatabase dao, Router router) {
        this.facades = facades;
        this.dao = dao;
    }

    @Override public String title() { return "Setup"; }
    @Override public String breadcrumb() { return "Home / System / Setup"; }
    @Override public String icon() { return Icons.GEAR; }

    @Override
    public Node build() {
        info = new GridPane();
        info.setHgap(28);
        info.setVgap(9);
        tables = new VBox(5);
        statusLabel = Fas.muted("");

        verifyOutput = new TextArea();
        verifyOutput.setEditable(false);
        verifyOutput.setPrefRowCount(10);
        verifyOutput.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 11px;");
        verifyOutput.setPromptText("Run a verification to check stored evidence "
                + "against its recorded hashes.");

        spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);

        verifyButton = Fas.primary("Verify Integrity", Icons.SHIELD);
        verifyButton.setOnAction(e -> verify());

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        VBox content = new VBox(16,
                Fas.pageHeader("Setup", null, refresh),
                Fas.cardWithHeader("Case", "Where this case lives and what it holds", info),
                Fas.cardWithHeader("Database Schema",
                        "One database holds both the engine tables and the added concepts",
                        tables),
                Fas.cardWithHeader("Integrity Verification",
                        "Re-hashes stored evidence and compares against the recorded values",
                        new VBox(10, Fas.row(10, verifyButton, spinner, statusLabel),
                                verifyOutput)));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    @Override
    public void onShow() {
        try {
            var folder = facades.liveCase().folder();
            Path db = folder.database();
            long dbSize = Files.exists(db) ? Files.size(db) : 0;
            long indexSize = dirSize(folder.index());
            long textSize = dirSize(folder.text());

            info.getChildren().clear();
            int r = 0;
            r = row(r, "Case name", facades.liveCase().name());
            r = row(r, "Case folder", folder.root().toString());
            r = row(r, "Database", db.toString());
            r = row(r, "Database size", DashboardScreen.humanBytes(dbSize));
            r = row(r, "Index size", DashboardScreen.humanBytes(indexSize));
            r = row(r, "Stored text", DashboardScreen.humanBytes(textSize));
            r = row(r, "Indexed items", String.valueOf(facades.liveCase().indexedCount()));
            row(r, "Second database", "none \u2014 one case.db holds everything");

            tables.getChildren().clear();
            addCount("Registered files", dao.countPathsAll());
            addCount("Stored contents", dao.countContents());
            addCount("Sources", facades.sources().listSources().size());
            addCount("Aspects", facades.aspects().listAspects().size());
            addCount("Categories", facades.categories().listCategories(1, 0).totalCount());
            addCount("Keywords", facades.keywords().listKeywords(1, 0).totalCount());
            addCount("Words", facades.words().getWords(1, 0).totalCount());
        } catch (Exception e) {
            statusLabel.setText("Could not read case status: " + e.getMessage());
        }
    }

    private void addCount(String label, int n) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        Label v = new Label(String.format("%,d", n));
        v.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: "
                + Fas.PRIMARY + ";");
        tables.getChildren().add(Fas.row(8, l, Fas.spacer(), v));
    }

    private int row(int r, String label, String value) {
        info.add(Fas.fieldLabel(label), 0, r);
        Label v = new Label(value == null || value.isBlank() ? "\u2014" : value);
        v.setWrapText(true);
        v.setMaxWidth(520);
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        info.add(v, 1, r);
        return r + 1;
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

    /** Runs the engine's verifier off the UI thread. */
    private void verify() {
        verifyButton.setDisable(true);
        spinner.setVisible(true);
        statusLabel.setText("Verifying\u2026");
        verifyOutput.clear();

        Thread worker = new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                IntegrityVerifier.Report rep =
                        facades.liveCase().verifyIntegrity(sb::append).get();
                Platform.runLater(() -> {
                    verifyOutput.setText(String.format(
                            "verified: %d%nmodified: %d%nmissing: %d%nfindings: %d%n%n%s",
                            rep.verified(), rep.modified(), rep.missing(),
                            rep.findings().size(), sb));
                    statusLabel.setText(rep.findings().isEmpty()
                            ? "All stored evidence verified"
                            : rep.findings().size() + " finding(s)");
                    spinner.setVisible(false);
                    verifyButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    verifyOutput.setText("Verification failed: " + e.getMessage());
                    statusLabel.setText("Failed");
                    spinner.setVisible(false);
                    verifyButton.setDisable(false);
                });
            }
        }, "fas-verify");
        worker.setDaemon(true);
        worker.start();
    }
}
