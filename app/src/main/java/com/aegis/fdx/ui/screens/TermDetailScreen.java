package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.facade.dto.TermSummary;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Detail for one node of the relationship graph — a keyword, a category or a category
 * word.
 *
 * <p>Everything shown is read from the relationships the case actually recorded: the
 * files the term reaches (with per-file occurrence counts where the relation keeps
 * them), and the neighbouring terms — each with its own whole-case file count. One
 * implementation serves all three kinds because the graph is the same from every node;
 * only the labels and the back destination differ.
 */
public final class TermDetailScreen implements Detail {

    /** Which kind of term this destination shows. */
    public enum Kind { WORD, KEYWORD, CATEGORY }

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
    private FlowPane categoryChips;
    private FlowPane keywordChips;
    private FlowPane wordChips;
    private final ObservableList<TermSummary.FileRef> files = FXCollections.observableArrayList();
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
        return switch (kind) {
            case WORD -> "Word Detail";
            case KEYWORD -> "Keyword Detail";
            case CATEGORY -> "Category Detail";
        };
    }

    private String listDestination() {
        return switch (kind) {
            case WORD -> "Words";
            case KEYWORD -> "Keywords";
            case CATEGORY -> "Categories";
        };
    }

    @Override
    public String breadcrumb() {
        return "Home / " + listDestination() + (termText.isBlank() ? " / Detail" : " / " + termText);
    }

    @Override
    public String icon() {
        return switch (kind) {
            case WORD -> Icons.BOOK;
            case KEYWORD -> Icons.KEY;
            case CATEGORY -> Icons.TAGS;
        };
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
        categoryChips = new FlowPane(6, 6);
        keywordChips = new FlowPane(6, 6);
        wordChips = new FlowPane(6, 6);

        Button back = Fas.outline("Back to " + listDestination(), null);
        back.setOnAction(e -> router.open(listDestination()));

        Button analyze = AnalyzeAction.secondaryButton(agent,
                "Analyze " + kindLabel(),
                "Where does this term appear on the case, and what does that suggest?",
                () -> kind == Kind.KEYWORD
                        ? AgentContext.ofScreen("Keywords").withKeyword(termId)
                        : AgentContext.ofScreen(listDestination()));

        Button searchBtn = Fas.secondary("Search for this term", Icons.SEARCH);
        searchBtn.setOnAction(e -> router.openSearch(quoted(termText)));

        Button manage = Fas.outline(kind == Kind.CATEGORY ? "Manage words" : "Open in list", null);
        manage.setOnAction(e -> {
            if (kind == Kind.CATEGORY) {
                router.openCategory(termId);
            } else {
                router.open(listDestination());
            }
        });

        TableView<TermSummary.FileRef> fileTable = new TableView<>(files);
        fileTable.setPlaceholder(Fas.emptyState(
                "No related files. Relationships are derived from stored text when material"
                        + " is registered; use Update associations on the Search page to re-derive."));
        fileTable.setPrefHeight(260);
        TableColumn<TermSummary.FileRef, String> f1 = new TableColumn<>("File");
        f1.setPrefWidth(240);
        f1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().fileName()));
        TableColumn<TermSummary.FileRef, String> f2 = new TableColumn<>("Path");
        f2.setPrefWidth(280);
        f2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().filePath() == null ? "" : c.getValue().filePath()));
        TableColumn<TermSummary.FileRef, String> f3 = new TableColumn<>("Source");
        f3.setPrefWidth(130);
        f3.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().sourceName() == null ? "" : c.getValue().sourceName()));
        TableColumn<TermSummary.FileRef, String> f4 = new TableColumn<>("Type");
        f4.setPrefWidth(70);
        f4.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().fileType() == null ? "" : c.getValue().fileType()));
        TableColumn<TermSummary.FileRef, String> f5 = new TableColumn<>("Hits");
        f5.setPrefWidth(70);
        f5.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                kind == Kind.CATEGORY ? "—" : String.valueOf(c.getValue().hits())));
        fileTable.getColumns().addAll(f1, f2, f3, f4, f5);
        fileTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<TermSummary.FileRef> row =
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

        VBox related = new VBox(10,
                Fas.fieldLabel("Categories"), categoryChips,
                Fas.fieldLabel("Keywords"), keywordChips,
                Fas.fieldLabel("Category words"), wordChips);

        VBox content = new VBox(16,
                Fas.pageHeader(title(), null, analyze, back, manage, searchBtn),
                new VBox(2, heading, subtitle),
                statsRow,
                new HBox(14,
                        grow(Fas.cardWithHeader("Related Terms",
                                "Each with its own whole-case file count; click to open", related)),
                        grow(Fas.cardWithHeader("Related Files",
                                "Files this term reaches through recorded relationships",
                                fileTable))),
                Fas.cardWithHeader("Occurrences in Indexed Text",
                        "Live search across everything the engine indexed",
                        new VBox(10, occurrencesLabel, occTable)));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private String kindLabel() {
        return switch (kind) {
            case WORD -> "Word";
            case KEYWORD -> "Keyword";
            case CATEGORY -> "Category";
        };
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
            TermSummary t = switch (kind) {
                case WORD -> facades.relationships().categoryWord(termId);
                case KEYWORD -> facades.relationships().keyword(termId);
                case CATEGORY -> facades.relationships().category(termId);
            };
            termText = t.text();
            heading.setText(termText);
            subtitle.setText(switch (kind) {
                case WORD -> "Category word · normalised as \"" + t.normalized() + "\"";
                case KEYWORD -> "Keyword · " + t.wordCount() + " words · normalised as \""
                        + t.normalized() + "\"";
                case CATEGORY -> "Category · files are reached through its words or by attribution";
            });
            files.setAll(t.files());
            chips(categoryChips, t.categories(), "No categories");
            chips(keywordChips, t.keywords(), "No keywords");
            chips(wordChips, t.categoryWords(), "No category words");

            // Live occurrence search against the index, independent of stored relations.
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
                    Fas.statCard(Icons.FILES, Fas.PRIMARY,
                            String.valueOf(t.fileCount()), "Related Files"),
                    Fas.statCard(Icons.KEY, Fas.WARNING,
                            String.valueOf(t.hits()), "Occurrences Recorded"),
                    Fas.statCard(Icons.FILE_TEXT, Fas.INFO,
                            String.valueOf(indexed), "Indexed Items Containing"),
                    Fas.statCard(Icons.TAGS, Fas.SUCCESS,
                            String.valueOf(t.categories().size() + t.keywords().size()
                                    + t.categoryWords().size()), "Related Terms")));
        } catch (FacadeException e) {
            heading.setText("Could not load this term");
            subtitle.setText(e.getMessage());
        }
    }

    private void chips(FlowPane pane, java.util.List<TermSummary.RelatedTerm> terms, String none) {
        pane.getChildren().clear();
        if (terms.isEmpty()) {
            pane.getChildren().add(Fas.muted(none));
            return;
        }
        for (TermSummary.RelatedTerm r : terms) {
            Button b = Fas.ghost(r.text() + "  (" + r.fileCount() + ")", null);
            b.setOnAction(e -> {
                switch (r.kind()) {
                    case KEYWORD -> router.openKeyword(r.id());
                    case CATEGORY -> router.openCategoryDetail(r.id());
                    case CATEGORY_WORD -> router.openWord(r.id());
                }
            });
            pane.getChildren().add(b);
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
