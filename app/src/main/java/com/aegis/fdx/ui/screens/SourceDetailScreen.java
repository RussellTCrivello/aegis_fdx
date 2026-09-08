package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.CategoryUsage;
import com.aegis.fdx.facade.dto.EntityStatistics;
import com.aegis.fdx.facade.dto.KeywordUsage;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.SourceDto;
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
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Everything known about one source: its record, what was collected from it, and how
 * that material is classified.
 *
 * <p>Reached by drilling through from the Sources list. Every figure is computed from
 * the registry and the engine, not stored on the source row.
 */
public final class SourceDetailScreen implements Detail {

    private final AegisFacades facades;
    private final Router router;
    private final AgentService agent;

    private int sourceId;
    private SourceDto source;

    private Label heading;
    private GridPane fields;
    private VBox statsRow;
    private VBox typeBars;
    private ProgressBar reviewBar;
    private Label reviewLabel;
    private final ObservableList<PathDto> files = FXCollections.observableArrayList();
    private final ObservableList<CategoryUsage> categories = FXCollections.observableArrayList();
    private final ObservableList<KeywordUsage> keywords = FXCollections.observableArrayList();
    private Label filesCount;
    private Label emptyNote;

    public SourceDetailScreen(AegisFacades facades, Router router, AgentService agent) {
        this.facades = facades;
        this.router = router;
        this.agent = agent;
    }

    @Override
    public String title() {
        return "Source Detail";
    }

    @Override
    public String breadcrumb() {
        return source == null ? "Home / Analysis / Sources / Detail"
                : "Home / Analysis / Sources / " + source.name();
    }

    @Override
    public String icon() {
        return Icons.BUILDING;
    }

    @Override
    public void setRecordId(int id) {
        this.sourceId = id;
    }

    @Override
    public Node build() {
        heading = new Label();
        heading.getStyleClass().add("section-title");

        Button back = Fas.outline("Back to Sources", null);
        back.setOnAction(e -> router.open("Sources"));

        Button relationships = Fas.secondary("Categories & Keywords", Icons.LINK);
        relationships.setOnAction(e -> {
            router.open("Source Relationships");
            router.openSource(sourceId);
        });

        Button editBtn = Fas.primary("Edit", Icons.PENCIL);
        editBtn.setOnAction(e -> edit());

        Button deleteBtn = Fas.danger("Delete", Icons.TRASH);
        deleteBtn.setOnAction(e -> delete());

        Button analyze = AnalyzeAction.secondaryButton(agent, "Analyze Source",
                "Summarise this source: what was collected from it, how it is "
                        + "classified, and what stands out.",
                () -> AgentContext.ofScreen("Sources").withSource(sourceId));

        fields = new GridPane();
        fields.setHgap(28);
        fields.setVgap(9);

        statsRow = new VBox();
        typeBars = new VBox(6);
        reviewBar = new ProgressBar(0);
        reviewBar.setMaxWidth(Double.MAX_VALUE);
        reviewLabel = Fas.muted("");
        filesCount = Fas.muted("0 files");
        emptyNote = Fas.emptyState("Nothing has been collected from this source yet.");

        TableView<PathDto> fileTable = new TableView<>(files);
        fileTable.setPlaceholder(Fas.emptyState("No files from this source."));
        fileTable.setPrefHeight(220);
        fileTable.getColumns().add(strCol("File Name", 240, PathDto::fileName));
        fileTable.getColumns().add(strCol("Type", 70, PathDto::fileType));
        fileTable.getColumns().add(strCol("Size", 90,
                p -> DashboardScreen.humanBytes(p.fileSize())));
        fileTable.getColumns().add(strCol("Aspect", 130, PathDto::aspectName));
        fileTable.getColumns().add(strCol("State", 90, PathDto::fileStatus));
        fileTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<PathDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openFile(row.getItem().id());
                }
            });
            return row;
        });

        TableView<CategoryUsage> catTable = new TableView<>(categories);
        catTable.setPlaceholder(Fas.emptyState("No categories applied to this material."));
        catTable.setPrefHeight(170);
        TableColumn<CategoryUsage, String> cCat = new TableColumn<>("Category");
        cCat.setPrefWidth(180);
        cCat.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().word()));
        TableColumn<CategoryUsage, String> cCatN = new TableColumn<>("Files");
        cCatN.setPrefWidth(70);
        cCatN.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().fileCount())));
        catTable.getColumns().addAll(cCat, cCatN);
        catTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<CategoryUsage> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openCategory(row.getItem().categoryId());
                }
            });
            return row;
        });

        TableView<KeywordUsage> kwTable = new TableView<>(keywords);
        kwTable.setPlaceholder(Fas.emptyState("No keywords found in this material."));
        kwTable.setPrefHeight(170);
        TableColumn<KeywordUsage, String> cKw = new TableColumn<>("Keyword");
        cKw.setPrefWidth(180);
        cKw.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().phrase()));
        TableColumn<KeywordUsage, String> cKwHits = new TableColumn<>("Hits");
        cKwHits.setPrefWidth(60);
        cKwHits.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().hits())));
        TableColumn<KeywordUsage, String> cKwFiles = new TableColumn<>("Files");
        cKwFiles.setPrefWidth(60);
        cKwFiles.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().files())));
        kwTable.getColumns().addAll(cKw, cKwHits, cKwFiles);
        kwTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<KeywordUsage> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openKeyword(row.getItem().keywordId());
                }
            });
            return row;
        });

        VBox left = Fas.cardWithHeader("Source Record", null, fields);
        HBox.setHgrow(left, Priority.ALWAYS);
        VBox right = Fas.cardWithHeader("Review Progress", null,
                new VBox(8, reviewBar, reviewLabel, typeBars));
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox rel = new HBox(14,
                grow(Fas.cardWithHeader("Categories", "Double-click to see the files", catTable)),
                grow(Fas.cardWithHeader("Keywords", "Double-click to open the keyword", kwTable)));

        VBox content = new VBox(16,
                Fas.pageHeader("Source Detail", null, analyze, back, relationships, editBtn, deleteBtn),
                heading,
                statsRow,
                new HBox(14, left, right),
                Fas.cardWithHeader("Collected Material",
                        "Double-click a row to open the file",
                        new VBox(10, filesCount, fileTable)),
                rel,
                emptyNote);
        content.setPadding(new Insets(20));

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static Region grow(Region r) {
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static TableColumn<PathDto, String> strCol(
            String name, double w, java.util.function.Function<PathDto, String> f) {
        TableColumn<PathDto, String> c = new TableColumn<>(name);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    @Override
    public void onShow() {
        if (sourceId <= 0) {
            heading.setText("No source selected");
            return;
        }
        try {
            source = facades.sources().getSource(sourceId);
            heading.setText(source.name());

            fields.getChildren().clear();
            int r = 0;
            r = row(r, "Job / Type", source.job());
            r = row(r, "Importance", String.format("%.2f", source.importance()));
            r = row(r, "Country", source.country());
            r = row(r, "City", source.city());
            r = row(r, "Ownership", source.ownership());
            r = row(r, "Access Status", source.accessStatus());
            r = row(r, "Accounts", source.accounts());
            r = row(r, "Attachments", source.attachments());
            r = row(r, "Discovered",
                    source.entryDate() == null ? "" : source.entryDate().toString());
            r = row(r, "Description", source.description());
            row(r, "Notes", source.note());

            EntityStatistics st = facades.analytics().sourceStatistics(sourceId);
            statsRow.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.FILES, Fas.PRIMARY,
                            String.format("%,d", st.files()), "Files"),
                    Fas.statCard(Icons.DATABASE, Fas.SUCCESS,
                            DashboardScreen.humanBytes(st.bytes()), "Total Size"),
                    Fas.statCard(Icons.FOLDER, Fas.WARNING,
                            String.valueOf(st.distinctTypes()), "File Types"),
                    Fas.statCard(Icons.CHECK_CIRCLE, Fas.INFO,
                            st.readFiles() + " / " + st.files(), "Reviewed")));

            reviewBar.setProgress(st.reviewProgress());
            reviewLabel.setText(String.format("%.0f%% reviewed \u2014 %d unread",
                    st.reviewProgress() * 100, st.unreadFiles()));

            typeBars.getChildren().clear();
            if (st.typeBreakdown().isEmpty()) {
                typeBars.getChildren().add(Fas.muted("No file types yet."));
            } else {
                int max = st.typeBreakdown().values().stream()
                        .mapToInt(Integer::intValue).max().orElse(1);
                st.typeBreakdown().forEach((type, n) ->
                        typeBars.getChildren().add(bar(type, n, max)));
            }

            files.setAll(facades.contents()
                    .getPaths(null, sourceId, null, null, 500, 0).results());
            filesCount.setText(files.size() + (files.size() == 1 ? " file" : " files"));

            categories.setAll(facades.analytics().categoriesForSource(sourceId));
            keywords.setAll(facades.analytics().keywordsForSource(sourceId));

            boolean empty = st.isEmpty();
            emptyNote.setVisible(empty);
            emptyNote.setManaged(empty);
        } catch (FacadeException e) {
            heading.setText("Could not load this source");
            statsRow.getChildren().setAll(Fas.emptyState(e.getMessage()));
        }
    }

    private Node bar(String label, int value, int max) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        l.setMinWidth(70);
        l.setMaxWidth(70);
        Region b = new Region();
        double w = 120 * Math.max(0.05, value / (double) max);
        b.setPrefWidth(w);
        b.setMinWidth(w);
        b.setPrefHeight(8);
        b.setStyle("-fx-background-color: " + Fas.PRIMARY + "; -fx-background-radius: 4;");
        Label n = new Label(String.valueOf(value));
        n.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Fas.TEXT_LIGHT + ";");
        return Fas.row(8, l, b, Fas.spacer(), n);
    }

    private int row(int r, String label, String value) {
        fields.add(Fas.fieldLabel(label), 0, r);
        Label v = new Label(value == null || value.isBlank() ? "\u2014" : value);
        v.setWrapText(true);
        v.setMaxWidth(340);
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        fields.add(v, 1, r);
        return r + 1;
    }

    /** Opens the edit form. Reuses the create form's field set, pre-filled. */
    private void edit() {
        if (source == null) {
            return;
        }
        SourceForm.showEdit(source, updated -> {
            try {
                facades.sources().updateSource(sourceId, updated);
                onShow();
            } catch (FacadeException e) {
                error(e.getMessage());
            }
        });
    }

    private void delete() {
        if (source == null) {
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete source \u201c" + source.name() + "\u201d? Material collected from it "
                        + "keeps its files but loses this attribution.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete Confirmation");
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                facades.sources().deleteSource(sourceId);
                router.open("Sources");
            }
        });
    }

    private static void error(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }
}
