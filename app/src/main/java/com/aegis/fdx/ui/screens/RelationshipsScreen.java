package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.CategoryUsage;
import com.aegis.fdx.facade.dto.KeywordUsage;
import com.aegis.fdx.ui.Detail;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The categories and keywords associated with one source or one aspect.
 *
 * <p>One implementation serves both, because the two destinations differ only in which
 * entity scopes the query. {@code forSource} decides which.
 */
public final class RelationshipsScreen implements Detail {

    private final AegisFacades facades;
    private final Router router;
    private final boolean forSource;

    private int entityId;
    private String entityName = "";

    private Label heading;
    private Label subtitle;
    private Label catCount;
    private Label kwCount;
    private final ObservableList<CategoryUsage> categories = FXCollections.observableArrayList();
    private final ObservableList<KeywordUsage> keywords = FXCollections.observableArrayList();
    private Label emptyNote;

    public RelationshipsScreen(AegisFacades facades, Router router, boolean forSource) {
        this.facades = facades;
        this.router = router;
        this.forSource = forSource;
    }

    @Override
    public String title() {
        return forSource ? "Source Relationships" : "Aspect Relationships";
    }

    @Override
    public String breadcrumb() {
        String root = forSource ? "Sources" : "Sides";
        return "Home / Analysis / " + root + " / " + (entityName.isBlank() ? "Relationships"
                : entityName + " / Categories & Keywords");
    }

    @Override
    public String icon() {
        return Icons.LINK;
    }

    @Override
    public void setRecordId(int id) {
        this.entityId = id;
    }

    @Override
    public Node build() {
        heading = new Label();
        heading.getStyleClass().add("section-title");
        subtitle = Fas.muted("");
        catCount = Fas.muted("0 categories");
        kwCount = Fas.muted("0 keywords");
        emptyNote = Fas.emptyState(
                "Nothing is classified yet. Categories and keywords appear here once "
                        + "material from this record has been classified.");

        Button back = Fas.outline(forSource ? "Back to Source" : "Back to Aspect", null);
        back.setOnAction(e -> {
            if (forSource) {
                router.openSource(entityId);
            } else {
                router.openAspect(entityId);
            }
        });

        TableView<CategoryUsage> catTable = new TableView<>(categories);
        catTable.setPlaceholder(Fas.emptyState("No categories."));
        VBox.setVgrow(catTable, Priority.ALWAYS);
        TableColumn<CategoryUsage, String> c1 = new TableColumn<>("Category");
        c1.setPrefWidth(220);
        c1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().word()));
        TableColumn<CategoryUsage, String> c2 = new TableColumn<>("Files");
        c2.setPrefWidth(80);
        c2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().fileCount())));
        catTable.getColumns().addAll(List.of(c1, c2));
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
        kwTable.setPlaceholder(Fas.emptyState("No keywords."));
        VBox.setVgrow(kwTable, Priority.ALWAYS);
        TableColumn<KeywordUsage, String> k1 = new TableColumn<>("Keyword");
        k1.setPrefWidth(200);
        k1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().phrase()));
        TableColumn<KeywordUsage, String> k2 = new TableColumn<>("Category");
        k2.setPrefWidth(140);
        k2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().categoryWord() == null ? "" : c.getValue().categoryWord()));
        TableColumn<KeywordUsage, String> k3 = new TableColumn<>("Hits");
        k3.setPrefWidth(70);
        k3.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().hits())));
        TableColumn<KeywordUsage, String> k4 = new TableColumn<>("Files");
        k4.setPrefWidth(70);
        k4.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().files())));
        kwTable.getColumns().addAll(List.of(k1, k2, k3, k4));
        kwTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<KeywordUsage> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openKeyword(row.getItem().keywordId());
                }
            });
            return row;
        });

        VBox left = Fas.cardWithHeader("Categories",
                "Double-click to see the files in a category",
                new VBox(10, catCount, catTable));
        VBox right = Fas.cardWithHeader("Keywords",
                "Double-click to open the keyword",
                new VBox(10, kwCount, kwTable));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox split = new HBox(14, left, right);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader(title(), null, back),
                new VBox(2, heading, subtitle),
                split, emptyNote);
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    @Override
    public void onShow() {
        if (entityId <= 0) {
            heading.setText("Nothing selected");
            return;
        }
        try {
            if (forSource) {
                entityName = facades.sources().getSource(entityId).name();
                categories.setAll(facades.analytics().categoriesForSource(entityId));
                keywords.setAll(facades.analytics().keywordsForSource(entityId));
            } else {
                entityName = facades.aspects().getAspect(entityId).name();
                categories.setAll(facades.analytics().categoriesForAspect(entityId));
                keywords.setAll(facades.analytics().keywordsForAspect(entityId));
            }
            heading.setText(entityName);
            subtitle.setText("How material from this record is classified");
            catCount.setText(categories.size()
                    + (categories.size() == 1 ? " category" : " categories"));
            kwCount.setText(keywords.size()
                    + (keywords.size() == 1 ? " keyword" : " keywords"));

            boolean empty = categories.isEmpty() && keywords.isEmpty();
            emptyNote.setVisible(empty);
            emptyNote.setManaged(empty);
        } catch (FacadeException e) {
            heading.setText("Could not load relationships");
            subtitle.setText(e.getMessage());
        }
    }
}
