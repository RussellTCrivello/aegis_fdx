package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.facade.dto.CategoryUsage;
import com.aegis.fdx.facade.dto.KeywordDto;
import com.aegis.fdx.facade.dto.KeywordUsage;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.facade.dto.WordDto;
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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Detail for one vocabulary term — a word or a keyword.
 *
 * <p>Shows the term, the categories it belongs to, where it occurs in indexed material,
 * and (for keywords) the per-file hit counts recorded during processing. One
 * implementation serves both because they differ only in how occurrences are resolved.
 */
public final class TermDetailScreen implements Detail {

    /** Which kind of term this destination shows. */
    public enum Kind { WORD, KEYWORD }

    private final AegisFacades facades;
    private final Router router;
    private final Kind kind;
    private final AgentService agent;

    private int termId;
    private String termText = "";

    private Label heading;
    private Label subtitle;
    private VBox statsRow;
    private Label occurrencesLabel;
    private final ObservableList<CategoryUsage> categories = FXCollections.observableArrayList();
    private final ObservableList<KeywordUsage.FileHit> hits = FXCollections.observableArrayList();
    private final ObservableList<SearchResultDto> occurrences = FXCollections.observableArrayList();

    public TermDetailScreen(AegisFacades facades, Router router, Kind kind,
                            AgentService agent) {
        this.facades = facades;
        this.router = router;
        this.kind = kind;
        this.agent = agent;
    }

    @Override
    public String title() {
        return kind == Kind.WORD ? "Word Detail" : "Keyword Detail";
    }

    @Override
    public String breadcrumb() {
        String root = kind == Kind.WORD ? "Words" : "Keywords";
        return "Home / " + root + (termText.isBlank() ? " / Detail" : " / " + termText);
    }

    @Override
    public String icon() {
        return kind == Kind.WORD ? Icons.BOOK : Icons.KEY;
    }

    @Override
    public void setRecordId(int id) {
        this.termId = id;
    }

    @Override
    public Node build() {
        heading = new Label();
        heading.getStyleClass().add("section-title");
        subtitle = Fas.muted("");
        statsRow = new VBox();
        occurrencesLabel = Fas.muted("");

        Button back = Fas.outline(kind == Kind.WORD ? "Back to Words" : "Back to Keywords", null);
        back.setOnAction(e -> router.open(kind == Kind.WORD ? "Words" : "Keywords"));

        Button analyze = AnalyzeAction.secondaryButton(agent,
                kind == Kind.WORD ? "Analyze Word" : "Analyze Keyword",
                "Where does this term appear on the case, and what does that suggest?",
                () -> kind == Kind.WORD
                        ? AgentContext.ofScreen("Words")
                        : AgentContext.ofScreen("Keywords").withKeyword(termId));

        Button searchBtn = Fas.secondary("Search for this term", Icons.SEARCH);
        searchBtn.setOnAction(e -> router.openSearch(quoted(termText)));

        TableView<CategoryUsage> catTable = new TableView<>(categories);
        catTable.setPlaceholder(Fas.emptyState("Not linked to any category."));
        catTable.setPrefHeight(150);
        TableColumn<CategoryUsage, String> c1 = new TableColumn<>("Category");
        c1.setPrefWidth(200);
        c1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().word()));
        catTable.getColumns().add(c1);
        catTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<CategoryUsage> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openCategory(row.getItem().categoryId());
                }
            });
            return row;
        });

        TableView<KeywordUsage.FileHit> hitTable = new TableView<>(hits);
        hitTable.setPlaceholder(Fas.emptyState(
                "No recorded hits. Hits are counted when material is classified."));
        hitTable.setPrefHeight(200);
        TableColumn<KeywordUsage.FileHit, String> h1 = new TableColumn<>("File");
        h1.setPrefWidth(240);
        h1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().fileName()));
        TableColumn<KeywordUsage.FileHit, String> h2 = new TableColumn<>("Source");
        h2.setPrefWidth(150);
        h2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().sourceName() == null ? "" : c.getValue().sourceName()));
        TableColumn<KeywordUsage.FileHit, String> h3 = new TableColumn<>("Hits");
        h3.setPrefWidth(70);
        h3.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().hits())));
        hitTable.getColumns().addAll(h1, h2, h3);
        hitTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<KeywordUsage.FileHit> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openFile(row.getItem().pathId());
                }
            });
            return row;
        });

        TableView<SearchResultDto> occTable = new TableView<>(occurrences);
        occTable.setPlaceholder(Fas.emptyState("This term does not appear in indexed text."));
        occTable.setPrefHeight(200);
        TableColumn<SearchResultDto, String> o1 = new TableColumn<>("File");
        o1.setPrefWidth(240);
        o1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().fileName()));
        TableColumn<SearchResultDto, String> o2 = new TableColumn<>("Type");
        o2.setPrefWidth(70);
        o2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().fileType() == null ? "" : c.getValue().fileType()));
        TableColumn<SearchResultDto, String> o3 = new TableColumn<>("Matches");
        o3.setPrefWidth(80);
        o3.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().matchCount())));
        TableColumn<SearchResultDto, String> o4 = new TableColumn<>("Excerpt");
        o4.setPrefWidth(320);
        o4.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().snippets().isEmpty() ? ""
                        : c.getValue().snippets().get(0).replace('\n', ' ')));
        occTable.getColumns().addAll(o1, o2, o3, o4);

        VBox content = new VBox(16,
                Fas.pageHeader(title(), null, analyze, back, searchBtn),
                new VBox(2, heading, subtitle),
                statsRow,
                new HBox(14,
                        grow(Fas.cardWithHeader("Categories", null, catTable)),
                        grow(Fas.cardWithHeader("Recorded Hits",
                                "Counted when material is classified", hitTable))),
                Fas.cardWithHeader("Occurrences in Indexed Text",
                        "Live search across everything the engine indexed",
                        new VBox(10, occurrencesLabel, occTable)));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) {
        HBox.setHgrow(v, Priority.ALWAYS);
        return v;
    }

    @Override
    public void onShow() {
        if (termId <= 0) {
            heading.setText("Nothing selected");
            return;
        }
        try {
            int fileCount = 0;
            int totalHits = 0;

            if (kind == Kind.WORD) {
                WordDto w = facades.words().getWord(termId);
                termText = w.word();
                subtitle.setText("Vocabulary term");
                categories.setAll(facades.analytics().categoriesForWord(termId));
                hits.clear();
            } else {
                KeywordDto k = facades.keywords().getKeyword(termId);
                termText = k.keyword();
                subtitle.setText("Keyword in category " + k.categoryWord());
                categories.setAll(java.util.List.of(
                        new CategoryUsage(k.categoryId(), k.categoryWord(), 0)));
                var fileHits = facades.analytics().filesForKeyword(termId, 200);
                hits.setAll(fileHits);
                fileCount = fileHits.size();
                for (var fh : fileHits) {
                    totalHits += fh.hits();
                }
            }
            heading.setText(termText);

            // Live occurrence search against the index, independent of stored hits.
            int indexed = 0;
            try {
                var page = facades.search().search(
                        SearchCriteria.of(quoted(termText)).limit(200));
                occurrences.setAll(page.results());
                indexed = page.totalCount();
                occurrencesLabel.setText(indexed + " indexed item(s) contain this term");
            } catch (FacadeException e) {
                occurrences.clear();
                occurrencesLabel.setText("Could not search: " + e.getMessage());
            }

            statsRow.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.FILE_TEXT, Fas.PRIMARY,
                            String.valueOf(indexed), "Items Containing"),
                    Fas.statCard(Icons.KEY, Fas.WARNING,
                            String.valueOf(totalHits), "Recorded Hits"),
                    Fas.statCard(Icons.FILES, Fas.INFO,
                            String.valueOf(fileCount), "Classified Files"),
                    Fas.statCard(Icons.TAGS, Fas.SUCCESS,
                            String.valueOf(categories.size()), "Categories")));
        } catch (FacadeException e) {
            heading.setText("Could not load this term");
            subtitle.setText(e.getMessage());
        }
    }

    /** Quotes a multi-word phrase so the query engine treats it as one term. */
    private static String quoted(String term) {
        if (term == null || term.isBlank()) {
            return "\"\"";
        }
        return term.trim().contains(" ") ? "\"" + term.trim() + "\"" : term.trim();
    }
}
