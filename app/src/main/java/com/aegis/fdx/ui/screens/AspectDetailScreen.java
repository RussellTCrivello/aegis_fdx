package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.CategoryUsage;
import com.aegis.fdx.facade.dto.EntityStatistics;
import com.aegis.fdx.facade.dto.KeywordUsage;
import com.aegis.fdx.facade.dto.PathDto;
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
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Everything known about one aspect: the record, the material attributed to it, and
 * how that material is classified.
 */
public final class AspectDetailScreen implements Detail {

    private final AegisFacades facades;
    private final Router router;
    private final AgentService agent;

    private int aspectId;
    private AspectDto aspect;

    private Label heading;
    private GridPane fields;
    private VBox statsRow;
    private ProgressBar reviewBar;
    private Label reviewLabel;
    private Label filesCount;
    private final ObservableList<PathDto> files = FXCollections.observableArrayList();
    private final ObservableList<CategoryUsage> categories = FXCollections.observableArrayList();
    private final ObservableList<KeywordUsage> keywords = FXCollections.observableArrayList();

    public AspectDetailScreen(AegisFacades facades, Router router, AgentService agent) {
        this.facades = facades;
        this.router = router;
        this.agent = agent;
    }

    @Override public String title() { return "Aspect Detail"; }

    @Override public String breadcrumb() {
        return aspect == null ? "Home / Analysis / Sides / Detail"
                : "Home / Analysis / Sides / " + aspect.name();
    }

    @Override public String icon() { return Icons.DIAGRAM3; }

    @Override public void setRecordId(int id) { this.aspectId = id; }

    @Override
    public Node build() {
        heading = new Label();
        heading.getStyleClass().add("section-title");

        Button back = Fas.outline("Back to Aspects", null);
        back.setOnAction(e -> router.open("Aspects"));
        Button rel = Fas.secondary("Categories & Keywords", Icons.LINK);
        rel.setOnAction(e -> { router.open("Aspect Relationships"); router.openAspect(aspectId); });
        Button edit = Fas.primary("Edit", Icons.PENCIL);
        edit.setOnAction(e -> edit());
        Button del = Fas.danger("Delete", Icons.TRASH);
        del.setOnAction(e -> delete());

        Button analyze = AnalyzeAction.secondaryButton(agent, "Analyze Aspect",
                "Summarise this aspect: what material is attributed to it and how it "
                        + "is classified.",
                () -> AgentContext.ofScreen("Aspects").withAspect(aspectId));

        fields = new GridPane();
        fields.setHgap(28);
        fields.setVgap(9);
        statsRow = new VBox();
        reviewBar = new ProgressBar(0);
        reviewBar.setMaxWidth(Double.MAX_VALUE);
        reviewLabel = Fas.muted("");
        filesCount = Fas.muted("0 files");

        TableView<PathDto> fileTable = new TableView<>(files);
        fileTable.setPlaceholder(Fas.emptyState("No files attributed to this aspect."));
        fileTable.setPrefHeight(220);
        fileTable.getColumns().add(col("File Name", 240, PathDto::fileName));
        fileTable.getColumns().add(col("Type", 70, PathDto::fileType));
        fileTable.getColumns().add(col("Size", 90, p -> DashboardScreen.humanBytes(p.fileSize())));
        fileTable.getColumns().add(col("Source", 140, PathDto::sourceName));
        fileTable.getColumns().add(col("State", 90, PathDto::fileStatus));
        fileTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<PathDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) router.openFile(row.getItem().id());
            });
            return row;
        });

        TableView<CategoryUsage> catTable = new TableView<>(categories);
        catTable.setPlaceholder(Fas.emptyState("No categories applied."));
        catTable.setPrefHeight(170);
        TableColumn<CategoryUsage, String> c1 = new TableColumn<>("Category");
        c1.setPrefWidth(180);
        c1.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().word()));
        TableColumn<CategoryUsage, String> c2 = new TableColumn<>("Files");
        c2.setPrefWidth(70);
        c2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().fileCount())));
        catTable.getColumns().addAll(c1, c2);

        TableView<KeywordUsage> kwTable = new TableView<>(keywords);
        kwTable.setPlaceholder(Fas.emptyState("No keywords found."));
        kwTable.setPrefHeight(170);
        TableColumn<KeywordUsage, String> k1 = new TableColumn<>("Keyword");
        k1.setPrefWidth(180);
        k1.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().phrase()));
        TableColumn<KeywordUsage, String> k2 = new TableColumn<>("Hits");
        k2.setPrefWidth(60);
        k2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().hits())));
        kwTable.getColumns().addAll(k1, k2);
        kwTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<KeywordUsage> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) router.openKeyword(row.getItem().keywordId());
            });
            return row;
        });

        VBox left = Fas.cardWithHeader("Aspect Record", null, fields);
        VBox right = Fas.cardWithHeader("Review Progress", null,
                new VBox(8, reviewBar, reviewLabel));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader("Aspect Detail", null, analyze, back, rel, edit, del),
                heading, statsRow,
                new HBox(14, left, right),
                Fas.cardWithHeader("Attributed Material", "Double-click a row to open the file",
                        new VBox(10, filesCount, fileTable)),
                new HBox(14,
                        grow(Fas.cardWithHeader("Categories", null, catTable)),
                        grow(Fas.cardWithHeader("Keywords", "Double-click to open", kwTable))));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static Region grow(Region r) { HBox.setHgrow(r, Priority.ALWAYS); return r; }

    private static TableColumn<PathDto, String> col(
            String n, double w, java.util.function.Function<PathDto, String> f) {
        TableColumn<PathDto, String> c = new TableColumn<>(n);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    @Override
    public void onShow() {
        if (aspectId <= 0) { heading.setText("No aspect selected"); return; }
        try {
            aspect = facades.aspects().getAspect(aspectId);
            heading.setText(aspect.name());

            fields.getChildren().clear();
            fields.add(Fas.fieldLabel("Importance"), 0, 0);
            fields.add(value(String.format("%.2f", aspect.importance())), 1, 0);
            fields.add(Fas.fieldLabel("Date of Creation"), 0, 1);
            fields.add(value(String.valueOf(aspect.dateCreation())), 1, 1);

            EntityStatistics st = facades.analytics().aspectStatistics(aspectId);
            statsRow.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.FILES, Fas.PRIMARY, String.format("%,d", st.files()), "Files"),
                    Fas.statCard(Icons.DATABASE, Fas.SUCCESS,
                            DashboardScreen.humanBytes(st.bytes()), "Total Size"),
                    Fas.statCard(Icons.FOLDER, Fas.WARNING,
                            String.valueOf(st.distinctTypes()), "File Types"),
                    Fas.statCard(Icons.CHECK_CIRCLE, Fas.INFO,
                            st.readFiles() + " / " + st.files(), "Reviewed")));
            reviewBar.setProgress(st.reviewProgress());
            reviewLabel.setText(String.format("%.0f%% reviewed \u2014 %d unread",
                    st.reviewProgress() * 100, st.unreadFiles()));

            files.setAll(facades.contents().getPaths(null, null, aspectId, null, 500, 0).results());
            filesCount.setText(files.size() + (files.size() == 1 ? " file" : " files"));
            categories.setAll(facades.analytics().categoriesForAspect(aspectId));
            keywords.setAll(facades.analytics().keywordsForAspect(aspectId));
        } catch (FacadeException e) {
            heading.setText("Could not load this aspect");
            statsRow.getChildren().setAll(Fas.emptyState(e.getMessage()));
        }
    }

    private static Label value(String s) {
        Label v = new Label(s == null || s.isBlank() ? "\u2014" : s);
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        return v;
    }

    private void edit() {
        if (aspect == null) return;
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Edit Aspect");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        TextField name = Fas.field("Aspect name");
        name.setText(aspect.name());
        TextField imp = Fas.field("0.0 - 1.0");
        imp.setText(String.format("%.2f", aspect.importance()));
        DatePicker date = new DatePicker(aspect.dateCreation());
        GridPane g = new GridPane();
        g.setHgap(14); g.setVgap(10); g.setPadding(new Insets(16));
        g.add(Fas.formField("Name *", name), 0, 0);
        g.add(Fas.formField("Importance *", imp), 0, 1);
        g.add(Fas.formField("Date of Creation", date), 0, 2);
        dlg.getDialogPane().setContent(g);
        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            try {
                facades.aspects().updateAspect(aspectId, name.getText(),
                        Double.parseDouble(imp.getText().trim()), date.getValue());
                onShow();
            } catch (NumberFormatException ex) {
                err("Importance must be a number between 0.0 and 1.0");
            } catch (FacadeException ex) {
                err(ex.getMessage());
            }
        });
    }

    private void delete() {
        if (aspect == null) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete aspect \u201c" + aspect.name() + "\u201d?", ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete Confirmation");
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                facades.aspects().deleteAspect(aspectId);
                router.open("Aspects");
            }
        });
    }

    private static void err(String m) {
        Alert a = new Alert(Alert.AlertType.ERROR, m, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }
}
