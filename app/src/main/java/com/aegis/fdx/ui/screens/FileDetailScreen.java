package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.FileState;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.Detail;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ui.AnalyzeAction;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything about one registered file: metadata, hashes, engine status, its
 * classification, and a preview of its extracted text.
 *
 * <p>Promoted from a dialog to a full destination, because it is a place the operator
 * navigates to and works from, not a transient popup.
 */
public final class FileDetailScreen implements Detail {

    private final AegisFacades facades;
    private final CorpusDatabase dao;
    private final Router router;
    private final AgentService agent;

    private int pathId;
    private PathDto path;

    private Label heading;
    private Label statusBadgeHolder;
    private GridPane meta;
    private FlowPane categoryChips;
    private FlowPane keywordChips;
    private TextArea preview;
    private Button readToggle;
    private Label engineStatus;

    public FileDetailScreen(AegisFacades facades, CorpusDatabase dao, Router router,
                            AgentService agent) {
        this.facades = facades;
        this.dao = dao;
        this.router = router;
        this.agent = agent;
    }

    @Override public String title() { return "File Detail"; }

    @Override public String breadcrumb() {
        return path == null ? "Home / File Library / Detail"
                : "Home / File Library / " + path.fileName();
    }

    @Override public String icon() { return Icons.FILE_TEXT; }

    @Override public void setRecordId(int id) { this.pathId = id; }

    @Override
    public Node build() {
        heading = new Label();
        heading.getStyleClass().add("section-title");
        statusBadgeHolder = new Label();
        engineStatus = Fas.muted("");
        meta = new GridPane();
        meta.setHgap(28);
        meta.setVgap(9);
        categoryChips = new FlowPane(6, 6);
        keywordChips = new FlowPane(6, 6);

        preview = new TextArea();
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(12);
        preview.setPromptText("No extracted text stored for this file.");

        Button back = Fas.outline("Back to Library", null);
        back.setOnAction(e -> router.open("File Library"));

        Button fullText = Fas.secondary("Full Content", Icons.FILE_TEXT);
        fullText.setOnAction(e -> router.openContent(pathId));

        readToggle = Fas.primary("Mark Read", Icons.CHECK_CIRCLE);
        readToggle.setOnAction(e -> toggleRead());

        Button analyze = AnalyzeAction.secondaryButton(agent, "Analyze File",
                "Summarise this file and explain what it relates to on this case.",
                () -> {
                    AgentContext c = AgentContext.ofScreen("File Library").withPath(pathId);
                    return path != null && path.elementId() != null
                            ? c.withElement(path.elementId()) : c;
                });

        Button classify = Fas.outline("Classify", Icons.TAGS);
        classify.setOnAction(e -> classify());

        Button openSource = Fas.ghost("Open Source", Icons.BUILDING);
        openSource.setOnAction(e -> {
            if (path != null && path.sourceId() != null) {
                router.openSource(path.sourceId());
            }
        });

        Button openAspect = Fas.ghost("Open Aspect", Icons.DIAGRAM3);
        openAspect.setOnAction(e -> {
            if (path != null && path.aspectId() != null) {
                router.openAspect(path.aspectId());
            }
        });

        VBox left = Fas.cardWithHeader("File Record", null, meta);
        VBox right = Fas.cardWithHeader("Classification",
                "Categories and keywords applied to this file",
                new VBox(12,
                        Fas.fieldLabel("CATEGORIES"), categoryChips,
                        Fas.fieldLabel("KEYWORDS"), keywordChips));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader("File Detail", null, analyze, back, fullText, classify, readToggle),
                new HBox(10, heading, statusBadgeHolder, Fas.spacer(),
                        openSource, openAspect),
                engineStatus,
                new HBox(14, left, right),
                Fas.cardWithHeader("Extracted Text",
                        "First part of the stored text; use Full Content for all of it",
                        preview));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    @Override
    public void onShow() {
        if (pathId <= 0) {
            heading.setText("No file selected");
            return;
        }
        try {
            path = facades.contents().getPath(pathId);
            heading.setText(path.fileName());

            boolean read = FileState.READ.label().equals(path.fileStatus());
            statusBadgeHolder.setGraphic(
                    Fas.badge(path.fileStatus(), read ? "success" : "muted"));
            readToggle.setText(read ? "Mark Unread" : "Mark Read");

            meta.getChildren().clear();
            int r = 0;
            r = row(r, "Type", path.fileType());
            r = row(r, "Size", DashboardScreen.humanBytes(path.fileSize()));
            r = row(r, "Source", path.sourceName());
            r = row(r, "Aspect", path.aspectName());
            r = row(r, "File Date", String.valueOf(path.fileDate()));
            r = row(r, "Registered", String.valueOf(path.dateCreation()));
            r = row(r, "SHA-256", path.hashValue());
            r = row(r, "Element ID", path.elementId());
            r = row(r, "Coordinates", path.coordinates());
            row(r, "Full Path", path.filePath());

            // Engine-side status for the originating element.
            engineStatus.setText("");
            if (path.elementId() != null) {
                try {
                    var item = facades.liveCase().byId(path.elementId());
                    if (item != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Engine status: ").append(item.status());
                        if (item.md5() != null) {
                            sb.append("   MD5: ").append(item.md5());
                        }
                        if (item.errors() != null && !item.errors().isEmpty()) {
                            sb.append("   Errors: ").append(String.join("; ", item.errors()));
                        }
                        engineStatus.setText(sb.toString());
                    }
                } catch (Exception ignored) {
                    // engine detail is supplementary; the registry record still stands
                }
            }

            categoryChips.getChildren().clear();
            for (var c : dao.selectPathCategories(pathId)) {
                Label chip = Fas.badge(c.str("word"), "info");
                categoryChips.getChildren().add(chip);
            }
            if (categoryChips.getChildren().isEmpty()) {
                categoryChips.getChildren().add(Fas.muted("None applied"));
            }

            keywordChips.getChildren().clear();
            for (var k : dao.selectPathKeywords(pathId)) {
                keywordChips.getChildren().add(
                        Fas.badge(k.str("keyword") + " \u00d7" + k.i("hits"), "warning"));
            }
            if (keywordChips.getChildren().isEmpty()) {
                keywordChips.getChildren().add(Fas.muted("None found"));
            }

            String text = facades.contents().getContentAsText(pathId);
            preview.setText(text == null || text.isBlank() ? ""
                    : (text.length() > 4000 ? text.substring(0, 4000) + "\n\u2026" : text));
        } catch (Exception e) {
            heading.setText("Could not load this file");
            engineStatus.setText(String.valueOf(e.getMessage()));
        }
    }

    private int row(int r, String label, String value) {
        meta.add(Fas.fieldLabel(label), 0, r);
        Label v = new Label(value == null || value.isBlank() ? "\u2014" : value);
        v.setWrapText(true);
        v.setMaxWidth(380);
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        meta.add(v, 1, r);
        return r + 1;
    }

    private void toggleRead() {
        if (path == null) return;
        boolean read = FileState.READ.label().equals(path.fileStatus());
        facades.contents().setPathStatus(pathId,
                read ? FileState.UNREAD.label() : FileState.READ.label());
        onShow();
    }

    /** Applies a category to this file, persisting the link. */
    private void classify() {
        if (path == null) return;
        try {
            List<CategoryDto> cats = facades.categories().listCategories(500, 0).results();
            if (cats.isEmpty()) {
                err("Create a category first, on the Categories destination.");
                return;
            }
            List<String> names = new ArrayList<>();
            for (CategoryDto c : cats) {
                names.add(c.word());
            }
            ChoiceDialog<String> d = new ChoiceDialog<>(names.get(0), names);
            d.setTitle("Classify File");
            d.setHeaderText("Apply a category to " + path.fileName());
            d.setContentText("Category:");
            d.showAndWait().ifPresent(chosen -> {
                for (CategoryDto c : cats) {
                    if (c.word().equals(chosen)) {
                        try {
                            dao.linkPathToCategory(pathId, c.id());
                            onShow();
                        } catch (Exception ex) {
                            err("Could not classify: " + ex.getMessage());
                        }
                        return;
                    }
                }
            });
        } catch (FacadeException e) {
            err(e.getMessage());
        }
    }

    private static void err(String m) {
        Alert a = new Alert(Alert.AlertType.ERROR, m, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }
}
