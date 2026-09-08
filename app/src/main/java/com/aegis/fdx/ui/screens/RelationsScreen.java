package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.MatchRow;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * Relations: the whole-case relationship graph at a glance.
 *
 * <p>Shows the graph totals (files, keywords, categories, category words and the
 * edges between them) and lets the operator search the graph — file name, path,
 * metadata, content, keyword, category or category word — with every match
 * reporting where it was found. All edges are read from {@code case.db}.
 */
public final class RelationsScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private VBox tiles;
    private VBox graphCards;
    private TextField searchField;
    private ComboBox<String> scopeBox;
    private final ObservableList<MatchRow> rows = FXCollections.observableArrayList();
    private Label matchLabel;

    public RelationsScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Relations";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Relations";
    }

    @Override
    public String icon() {
        return Icons.SHARE;
    }

    @Override
    public Node build() {
        tiles = new VBox();
        graphCards = new VBox(12);

        searchField = Fas.field("Search the relationship graph: keyword, category, word, file...");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setOnAction(e -> runSearch());

        scopeBox = new ComboBox<>(FXCollections.observableArrayList(
                "Everywhere", "Keyword", "Category", "Category word",
                "Content", "File name", "Path", "Metadata"));
        scopeBox.setValue("Everywhere");
        scopeBox.setPrefWidth(150);

        Button go = Fas.primary("Search Graph", Icons.SEARCH);
        go.setOnAction(e -> runSearch());

        Button update = Fas.outline("Update associations", Icons.REFRESH);
        update.setOnAction(e -> {
            try {
                var r = facades.relationshipAnalyzer().analyzeAll(null);
                matchLabel.setText("Associations updated: " + r.filesScanned()
                        + " file(s) scanned, " + r.keywordLinks() + " keyword link(s), "
                        + r.wordLinks() + " category-word link(s)");
                onShow();
            } catch (RuntimeException ex) {
                matchLabel.setText("Could not update associations: " + ex.getMessage());
            }
        });

        matchLabel = Fas.muted("Reports where each file matched: name, path, metadata, "
                + "content, keyword, category or category word");

        TableView<MatchRow> table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState(
                "No relationship matches yet. Search above to walk the graph."));
        table.setPrefHeight(280);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.getColumns().add(col("File Name", 200, MatchRow::fileName));
        table.getColumns().add(col("Matched In", 120, r -> r.matchType().label()));
        table.getColumns().add(col("Matched Term", 180, MatchRow::matchedTerm));
        table.getColumns().add(col("Keyword", 150, MatchRow::keyword));
        table.getColumns().add(col("Category", 120, MatchRow::category));
        table.getColumns().add(col("Hits", 60,
                r -> r.hits() > 0 ? String.valueOf(r.hits()) : ""));
        table.getColumns().add(col("Evidence", 220, MatchRow::snippet));
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<MatchRow> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openFile(row.getItem().pathId());
                }
            });
            return row;
        });

        VBox searchCard = new VBox(10, Fas.row(10, searchField, scopeBox, go, update), matchLabel);
        searchCard.getStyleClass().add("filter-bar");

        VBox content = new VBox(16,
                Fas.pageHeader("Relations", breadcrumb()),
                tiles,
                Fas.cardWithHeader("Relationship Graph",
                        "One graph across files, keywords, categories and category words",
                        graphCards),
                searchCard,
                Fas.cardWithHeader("Graph Matches",
                        "Double-click a row to open the file", table));
        content.setPadding(new Insets(20));
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static TableColumn<MatchRow, String> col(
            String name, double w, java.util.function.Function<MatchRow, String> f) {
        TableColumn<MatchRow, String> c = new TableColumn<>(name);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    @Override
    public void onShow() {
        try {
            Map<String, Integer> t = facades.relationships().totals();
            int files = t.getOrDefault("files", 0);
            int keywords = t.getOrDefault("keywords", 0);
            int categories = t.getOrDefault("categories", 0);
            int words = t.getOrDefault("category_words", 0);
            int kEdges = t.getOrDefault("keyword_edges", 0);
            int wEdges = t.getOrDefault("word_edges", 0);
            int cEdges = t.getOrDefault("category_edges", 0);

            tiles.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.FILES, Fas.PRIMARY,
                            String.format("%,d", files), "Files in Graph"),
                    Fas.statCard(Icons.KEY, Fas.WARNING,
                            String.format("%,d", kEdges), "File ↔ Keyword Edges"),
                    Fas.statCard(Icons.BOOK, Fas.INFO,
                            String.format("%,d", wEdges), "File ↔ Word Edges"),
                    Fas.statCard(Icons.TAGS, Fas.SUCCESS,
                            String.format("%,d", cEdges), "File ↔ Category Edges")));

            VBox kwCard = hubMini(Icons.KEY, Fas.WARNING, "Keywords",
                    String.valueOf(keywords), "Distinct keyword phrases", "Keywords");
            VBox catCard = hubMini(Icons.TAGS, Fas.SUCCESS, "Categories",
                    String.valueOf(categories), "Classification taxonomy", "Categories");
            VBox wordCard = hubMini(Icons.BOOK, Fas.INFO, "Category Words",
                    String.valueOf(words), "Words linked to categories", "Words");
            VBox fileCard = hubMini(Icons.FILES, Fas.PRIMARY, "Files",
                    String.valueOf(files), "Registered files in the graph", "File Library");
            HBox row = new HBox(14, kwCard, catCard, wordCard, fileCard);
            Label flow = Fas.muted("FILE ── path_keyword ──▶ KEYWORD ──▶ CATEGORY"
                    + "        FILE ── path_word ──▶ CATEGORY WORD ──▶ CATEGORY"
                    + "        FILE ── path_category ──▶ CATEGORY");
            flow.setWrapText(true);
            graphCards.getChildren().setAll(row, flow);
        } catch (RuntimeException e) {
            tiles.getChildren().setAll(Fas.emptyState("No relationship data yet."));
        }
    }

    private VBox hubMini(String icon, String accent, String title,
                         String count, String hint, String destination) {
        VBox v = Fas.hubCard(icon, accent, title, count, hint, () -> router.open(destination));
        v.setMinHeight(130);
        return v;
    }

    private void runSearch() {
        String q = searchField.getText();
        if (q == null || q.isBlank()) {
            rows.clear();
            matchLabel.setText("Enter a term first");
            return;
        }
        String text = q.trim();
        com.aegis.fdx.facade.RelationshipFacade.Scope scope = switch (scopeBox.getValue()) {
            case "Keyword" -> com.aegis.fdx.facade.RelationshipFacade.Scope.KEYWORD;
            case "Category" -> com.aegis.fdx.facade.RelationshipFacade.Scope.CATEGORY;
            case "Category word" -> com.aegis.fdx.facade.RelationshipFacade.Scope.CATEGORY_WORD;
            case "Content" -> com.aegis.fdx.facade.RelationshipFacade.Scope.CONTENT;
            case "File name" -> com.aegis.fdx.facade.RelationshipFacade.Scope.FILE_NAME;
            case "Path" -> com.aegis.fdx.facade.RelationshipFacade.Scope.PATH;
            case "Metadata" -> com.aegis.fdx.facade.RelationshipFacade.Scope.METADATA;
            default -> com.aegis.fdx.facade.RelationshipFacade.Scope.EVERYWHERE;
        };
        try {
            List<MatchRow> found = facades.relationships()
                    .search(text, java.util.EnumSet.of(scope), 500);
            rows.setAll(found);
            java.util.Set<Integer> files = new java.util.HashSet<>();
            for (MatchRow r : found) {
                files.add(r.pathId());
            }
            matchLabel.setText(files.size() + " file(s), " + found.size()
                    + " match(es) for \u201c" + text + "\u201d in "
                    + scopeBox.getValue().toLowerCase());
        } catch (RuntimeException e) {
            rows.clear();
            matchLabel.setText(e.getMessage());
        }
    }
}
