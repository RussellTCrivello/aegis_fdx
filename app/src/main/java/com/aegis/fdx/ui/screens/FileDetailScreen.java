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
    private FlowPane wordChips;
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
        wordChips = new FlowPane(6, 6);

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
        VBox right = Fas.cardWithHeader("Relationships",
                "Keywords, categories and category words this file is related to; click to open",
                new VBox(12,
                        Fas.fieldLabel("KEYWORDS"), keywordChips,
                        Fas.fieldLabel("CATEGORIES"), categoryChips,
                        Fas.fieldLabel("CATEGORY WORDS"), wordChips));
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

            // Engine-side record for the originating element: the authoritative
            // hashes, timestamps, media type, processing status and OCR state.
            com.aegis.fdx.model.Item item = null;
            engineStatus.setText("");
            if (path.elementId() != null) {
                try {
                    item = facades.liveCase().byId(path.elementId());
                } catch (Exception ignored) {
                    // engine detail is supplementary; the registry record still stands
                }
            }

            meta.getChildren().clear();
            int r = 0;
            r = row(r, "Full Path", path.filePath());
            r = row(r, "Name", path.fileName());
            r = row(r, "Size", DashboardScreen.humanBytes(path.fileSize())
                    + "  (" + String.format("%,d", path.fileSize()) + " bytes)");
            r = row(r, "SHA-256", path.hashValue() != null ? path.hashValue()
                    : item != null ? item.sha256() : null);
            r = row(r, "MD5", item == null ? null : item.md5());
            r = row(r, "MIME Type", item == null ? null : item.mediaType());
            r = row(r, "Type", path.fileType());
            r = row(r, "Created", item == null || item.created() == null
                    ? null : item.created().toString());
            r = row(r, "Modified", item == null || item.modified() == null
                    ? String.valueOf(path.fileDate()) : item.modified().toString());
            r = row(r, "Registered", String.valueOf(path.dateCreation()));
            r = row(r, "Processing Status", item == null ? null : String.valueOf(item.status()));
            r = row(r, "OCR", item == null ? null
                    : item.ocrApplied() ? "Applied" : item.needsOcr() ? "Candidate, not applied" : "Not needed");
            r = row(r, "Review Status", path.fileStatus());
            r = row(r, "Source", path.sourceName());
            r = row(r, "Aspect", path.aspectName());
            r = row(r, "Element ID", path.elementId());
            row(r, "Coordinates", path.coordinates());
            if (item != null && item.errors() != null && !item.errors().isEmpty()) {
                engineStatus.setText("Processing errors: " + String.join("; ", item.errors()));
            }

            // Relationships: exactly what the case recorded, each chip opens its detail.
            var rel = facades.relationships().forFile(pathId);
            keywordChips.getChildren().clear();
            for (var k : rel.keywords()) {
                int hits = 0;
                for (var row : dao.selectPathKeywords(pathId)) {
                    if (row.i("id") == k.id()) {
                        hits = row.i("hits");
                    }
                }
                Button chip = Fas.ghost(k.text() + (hits > 0 ? " \u00d7" + hits : "")
                        + "  (" + k.fileCount() + " files)", Icons.KEY);
                chip.setOnAction(e -> router.openKeyword(k.id()));
                keywordChips.getChildren().add(chip);
            }
            if (keywordChips.getChildren().isEmpty()) {
                keywordChips.getChildren().add(Fas.muted("No keyword phrase occurs in this file"));
            }

            categoryChips.getChildren().clear();
            for (var c : rel.categories()) {
                Button chip = Fas.ghost(c.text() + "  (" + c.fileCount() + " files)", Icons.TAGS);
                chip.setOnAction(e -> router.openCategoryDetail(c.id()));
                categoryChips.getChildren().add(chip);
            }
            if (categoryChips.getChildren().isEmpty()) {
                categoryChips.getChildren().add(Fas.muted("None applied or reached"));
            }

            wordChips.getChildren().clear();
            for (var w : rel.categoryWords()) {
                Button chip = Fas.ghost(w.text() + "  (" + w.fileCount() + " files)", Icons.BOOK);
                chip.setOnAction(e -> router.openWord(w.id()));
                wordChips.getChildren().add(chip);
            }
            if (wordChips.getChildren().isEmpty()) {
                wordChips.getChildren().add(Fas.muted("No category word occurs in this file"));
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
