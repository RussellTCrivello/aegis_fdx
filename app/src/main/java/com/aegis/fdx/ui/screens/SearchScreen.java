package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.ExportFacade;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.facade.SortField;
import com.aegis.fdx.facade.SortOrder;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.PreviewDto;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ui.AnalyzeAction;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.facade.RelationshipFacade;
import com.aegis.fdx.facade.dto.MatchRow;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

/**
 * Search: query the indexed corpus and inspect what matched.
 *
 * <p>The filter bar narrows by file type, source, aspect and date range, and controls
 * ordering and page size. Selecting a result previews its matching text and hashes.
 * Searches can be saved for reuse and results exported.
 */
public final class SearchScreen implements Screen {

    private final AegisFacades facades;
    private final AgentService agent;
    private final ObservableList<SearchResultDto> rows = FXCollections.observableArrayList();

    private TextField queryField;
    private ComboBox<String> fileType;
    private ComboBox<String> sourceBox;
    private ComboBox<String> aspectBox;
    private TextField dateFrom;
    private TextField dateTo;
    private ComboBox<SortField> sortBy;
    private ComboBox<SortOrder> sortOrder;
    private ComboBox<Integer> perPage;
    private Label resultLabel;
    private FlowPane suggestions;
    private Label pageLabel;
    private TextArea snippetArea;
    private TableView<SearchResultDto> table;
    private int offset;
    private int total;

    private final Router router;
    private final javafx.collections.ObservableList<MatchRow> matchRows =
            FXCollections.observableArrayList();
    private ComboBox<String> scopeBox;
    private Label matchLabel;
    private TableView<MatchRow> matchTable;

    public SearchScreen(AegisFacades facades, AgentService agent) {
        this(facades, agent, null);
    }

    public SearchScreen(AegisFacades facades, AgentService agent, Router router) {
        this.facades = facades;
        this.agent = agent;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Search";
    }

    @Override
    public String icon() {
        return Icons.SEARCH;
    }

    @Override
    public Node build() {
        queryField = Fas.field("Search across all extracted text\u2026");
        HBox.setHgrow(queryField, Priority.ALWAYS);
        queryField.setOnAction(e -> runSearch(true));

        Button go = Fas.primary("Search", Icons.SEARCH);
        go.setOnAction(e -> runSearch(true));

        Button save = Fas.outline("Save Search", Icons.BOOKMARK);
        save.setOnAction(e -> saveSearch());

        Button analyze = AnalyzeAction.button(agent, "Analyze Results",
                "Summarise these search results: which sources they come from, what "
                        + "categories and keywords they share, and what stands out.",
                () -> AgentContext.ofScreen("Search")
                        .withQuery(queryField.getText()));

        Button export = Fas.outline("Export CSV", Icons.DOWNLOAD);
        export.setOnAction(e -> exportCsv());

        fileType = combo("Any type", "txt", "pdf", "docx", "xlsx", "eml", "msg", "html", "csv",
                "png", "jpg", "zip");
        sourceBox = combo("Any source");
        aspectBox = combo("Any aspect");
        dateFrom = Fas.field("YYYY-MM-DD");
        dateFrom.setPrefWidth(120);
        dateTo = Fas.field("YYYY-MM-DD");
        dateTo.setPrefWidth(120);
        sortBy = new ComboBox<>(FXCollections.observableArrayList(SortField.values()));
        sortBy.setValue(SortField.RELEVANCE);
        sortBy.setPrefWidth(130);
        sortOrder = new ComboBox<>(FXCollections.observableArrayList(SortOrder.values()));
        sortOrder.setValue(SortOrder.DESCENDING);
        sortOrder.setPrefWidth(130);
        perPage = new ComboBox<>(FXCollections.observableArrayList(25, 50, 100));
        perPage.setValue(25);

        HBox filters = new HBox(10,
                Fas.formField("File Type", fileType),
                Fas.formField("Source", sourceBox),
                Fas.formField("Aspect", aspectBox),
                Fas.formField("Date From", dateFrom),
                Fas.formField("Date To", dateTo),
                Fas.formField("Sort By", sortBy),
                Fas.formField("Order", sortOrder),
                Fas.formField("Per Page", perPage));

        Button clear = Fas.ghost("Clear Filters", Icons.CLOSE);
        clear.setOnAction(e -> {
            fileType.setValue("Any type");
            sourceBox.setValue("Any source");
            aspectBox.setValue("Any aspect");
            dateFrom.clear();
            dateTo.clear();
            sortBy.setValue(SortField.RELEVANCE);
            sortOrder.setValue(SortOrder.DESCENDING);
        });

        resultLabel = Fas.muted("Enter a query to begin");
        table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        snippetArea = new TextArea();
        snippetArea.setEditable(false);
        snippetArea.setWrapText(true);
        snippetArea.setPromptText("Select a result to preview its matching text");

        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> preview(b));

        pageLabel = Fas.muted("");
        Button prev = Fas.outline("Previous", null);
        prev.setOnAction(e -> {
            offset = Math.max(0, offset - perPage.getValue());
            runSearch(false);
        });
        Button next = Fas.outline("Next", null);
        next.setOnAction(e -> {
            if (offset + perPage.getValue() < total) {
                offset += perPage.getValue();
                runSearch(false);
            }
        });

        suggestions = new FlowPane(8, 8);
        suggestions.setVisible(false);
        suggestions.setManaged(false);

        VBox resultsCard = Fas.cardWithHeader("Results", null,
                new VBox(10, Fas.row(8, resultLabel, Fas.spacer(), pageLabel, prev, next),
                        suggestions, table));
        VBox previewCard = Fas.cardWithHeader("Preview", "Matching text and metadata", snippetArea);
        previewCard.setPrefHeight(220);

        // ---- search everywhere: file ↔ keyword ↔ category ↔ category word ----
        scopeBox = combo("Everywhere", "Keyword", "Category", "Category word", "Content",
                "File name", "Path", "Metadata");
        scopeBox.setPrefWidth(150);
        Button goRel = Fas.secondary("Search relationships", Icons.LINK);
        goRel.setOnAction(e -> runRelationshipSearch());
        Button update = Fas.outline("Update associations", Icons.REFRESH);
        update.setOnAction(e -> updateAssociations());
        Button check = Fas.outline("Check relationships", Icons.CHECK_CIRCLE);
        check.setOnAction(e -> checkRelationships());
        matchLabel = Fas.muted("Reports where each file matched: name, path, metadata, "
                + "content, keyword, category or category word");
        matchTable = buildMatchTable();
        matchTable.setPrefHeight(220);
        VBox relCard = Fas.cardWithHeader("Search Everywhere", null,
                new VBox(10, Fas.row(8, Fas.fieldLabel("Scope"), scopeBox, goRel, update, check,
                        Fas.spacer(), matchLabel), matchTable));

        SplitPane split = new SplitPane(resultsCard, relCard, previewCard);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.45, 0.78);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox searchCard = Fas.card(
                Fas.row(10, queryField, go, save, export, analyze),
                filters,
                Fas.row(8, Fas.spacer(), clear));

        VBox content = new VBox(16,
                Fas.pageHeader("Search", "Home / Search"),
                searchCard, split);
        content.setPadding(new Insets(20));
        return content;
    }

    private TableView<SearchResultDto> buildTable() {
        TableView<SearchResultDto> t = new TableView<>(rows);
        t.setPlaceholder(Fas.emptyState("No results. Try a different query or clear the filters."));

        t.getColumns().add(strCol("File Name", 220, SearchResultDto::fileName));
        t.getColumns().add(strCol("Type", 70, SearchResultDto::fileType));
        t.getColumns().add(strCol("Size", 90,
                r -> DashboardScreen.humanBytes(r.fileSize())));
        t.getColumns().add(strCol("Source", 130, SearchResultDto::sourceName));
        t.getColumns().add(strCol("Status", 100, SearchResultDto::status));
        t.getColumns().add(strCol("Matches", 80, r -> String.valueOf(r.matchCount())));
        t.getColumns().add(strCol("Rank", 80, r -> String.format("%.3f", r.rank())));
        t.getColumns().add(strCol("Path", 260, SearchResultDto::filePath));
        return t;
    }

    private TableView<MatchRow> buildMatchTable() {
        TableView<MatchRow> t = new TableView<>(matchRows);
        t.setPlaceholder(Fas.emptyState(
                "No relationship matches. Search a keyword, category, category word, "
                        + "file name, path, metadata value or piece of content."));
        t.getColumns().add(mCol("File Name", 200, MatchRow::fileName));
        t.getColumns().add(mCol("Matched In", 110, r -> r.matchType().label()));
        t.getColumns().add(mCol("Matched Term", 180, MatchRow::matchedTerm));
        t.getColumns().add(mCol("Keyword", 160, MatchRow::keyword));
        t.getColumns().add(mCol("Category", 110, MatchRow::category));
        t.getColumns().add(mCol("Category Word", 110, MatchRow::categoryWord));
        t.getColumns().add(mCol("Hits", 60, r -> r.hits() > 0 ? String.valueOf(r.hits()) : ""));
        t.getColumns().add(mCol("Evidence", 220, MatchRow::snippet));
        t.getColumns().add(mCol("Source", 110, MatchRow::sourceName));
        t.getColumns().add(mCol("Path", 240, MatchRow::filePath));
        t.setRowFactory(tv -> {
            javafx.scene.control.TableRow<MatchRow> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openFile(row.getItem().pathId());
                }
            });
            return row;
        });
        t.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                snippetArea.setText(b.fileName() + "\n" + nz(b.filePath())
                        + "\n\nMatched in: " + b.matchType().label()
                        + "\nTerm: " + nz(b.matchedTerm())
                        + (b.keyword() != null ? "\nKeyword: " + b.keyword() : "")
                        + (b.category() != null ? "\nCategory: " + b.category() : "")
                        + (b.categoryWord() != null ? "\nCategory word: " + b.categoryWord() : "")
                        + "\n\n" + nz(b.snippet()));
            }
        });
        return t;
    }

    private static TableColumn<MatchRow, String> mCol(
            String name, double w, java.util.function.Function<MatchRow, String> f) {
        TableColumn<MatchRow, String> c = new TableColumn<>(name);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    private void runRelationshipSearch() {
        String q = queryField.getText();
        if (q == null || q.isBlank()) {
            matchRows.clear();
            matchLabel.setText("Enter a term first");
            return;
        }
        // strip the quotes a term detail adds for the index query
        String text = q.trim().replaceAll("^\"|\"$", "");
        RelationshipFacade.Scope scope = switch (scopeBox.getValue()) {
            case "Keyword" -> RelationshipFacade.Scope.KEYWORD;
            case "Category" -> RelationshipFacade.Scope.CATEGORY;
            case "Category word" -> RelationshipFacade.Scope.CATEGORY_WORD;
            case "Content" -> RelationshipFacade.Scope.CONTENT;
            case "File name" -> RelationshipFacade.Scope.FILE_NAME;
            case "Path" -> RelationshipFacade.Scope.PATH;
            case "Metadata" -> RelationshipFacade.Scope.METADATA;
            default -> RelationshipFacade.Scope.EVERYWHERE;
        };
        try {
            var found = facades.relationships().search(text, java.util.EnumSet.of(scope), 500);
            matchRows.setAll(found);
            java.util.Set<Integer> files = new java.util.HashSet<>();
            for (MatchRow r : found) {
                files.add(r.pathId());
            }
            matchLabel.setText(files.size() + " file(s), " + found.size() + " match(es) for \u201c"
                    + text + "\u201d in " + scopeBox.getValue().toLowerCase());
        } catch (FacadeException e) {
            matchRows.clear();
            matchLabel.setText(e.getMessage());
        }
    }

    /** Walks the relationship graph from both ends and shows the report. */
    private void checkRelationships() {
        try {
            var report = facades.relationshipIntegrity().check();
            matchLabel.setText(report.summary());
            snippetArea.setText(com.aegis.fdx.facade.RelationshipIntegrity.render(report));
            Alert a = new Alert(report.consistent() ? Alert.AlertType.INFORMATION
                    : Alert.AlertType.WARNING, report.summary(), ButtonType.OK);
            a.setHeaderText(report.consistent() ? "Relationships are consistent"
                    : report.findings().size() + " inconsistency(ies) found — see the preview pane");
            a.showAndWait();
        } catch (FacadeException e) {
            matchLabel.setText("Could not check relationships: " + e.getMessage());
        }
    }

    /** Re-derives file ↔ word and file ↔ keyword edges from the stored text of every file. */
    private void updateAssociations() {
        try {
            var r = facades.relationshipAnalyzer().analyzeAll(null);
            matchLabel.setText("Associations updated: " + r.filesScanned() + " file(s) scanned, "
                    + r.filesWithText() + " with text, " + r.keywordLinks() + " keyword link(s), "
                    + r.wordLinks() + " category-word link(s) across "
                    + r.keywordsChecked() + " keyword(s) and " + r.wordsChecked() + " word(s)");
            if (!matchRows.isEmpty()) {
                runRelationshipSearch();
            }
        } catch (FacadeException e) {
            matchLabel.setText("Could not update associations: " + e.getMessage());
        }
    }

    private static TableColumn<SearchResultDto, String> strCol(
            String name, double w, java.util.function.Function<SearchResultDto, String> f) {
        TableColumn<SearchResultDto, String> c = new TableColumn<>(name);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    private ComboBox<String> combo(String... values) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(values));
        c.setValue(values[0]);
        c.setPrefWidth(130);
        return c;
    }

    private void runSearch(boolean resetOffset) {
        if (resetOffset) {
            offset = 0;
        }
        String q = queryField.getText();
        if (q == null || q.isBlank()) {
            resultLabel.setText("Enter a query to begin");
            rows.clear();
            return;
        }
        try {
            String type = "Any type".equals(fileType.getValue()) ? null : fileType.getValue();
            Integer srcId = resolveId(sourceBox.getValue(), "Any source", true);
            Integer aspectId = resolveId(aspectBox.getValue(), "Any aspect", false);

            SearchCriteria criteria = SearchCriteria.of(q)
                    .fileType(type)
                    .source(srcId)
                    .aspect(aspectId)
                    .between(parseDate(dateFrom.getText(), "Date From"),
                             parseDate(dateTo.getText(), "Date To"))
                    .sortBy(sortBy.getValue(), sortOrder.getValue())
                    .limit(perPage.getValue())
                    .offset(offset);

            long t0 = System.nanoTime();
            Page<SearchResultDto> page = facades.search().search(criteria);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;

            rows.setAll(page.results());
            total = page.totalCount();
            resultLabel.setText(String.format("%,d result%s in %.0f ms",
                    total, total == 1 ? "" : "s", ms));
            int from = total == 0 ? 0 : offset + 1;
            pageLabel.setText(from + " - " + Math.min(offset + perPage.getValue(), total)
                    + " of " + String.format("%,d", total));

            offerSuggestions(q, total);

            // Python records every executed search in its history table.
            facades.history().addSearch(q, total);
        } catch (FacadeException ex) {
            rows.clear();
            resultLabel.setText(ex.getMessage());
            offerSuggestions(q, 0);
        }
    }

    /**
     * Offers the closest matches when a query finds nothing.
     *
     * <p>A query that returns no rows is the moment an examiner is most likely to
     * conclude the material is not there. `SearchFacade.getSearchSuggestions` runs the
     * same query fuzzily against the same index; each suggestion here is therefore a
     * document that genuinely exists on this case, and clicking one runs a real search
     * for it. Nothing is offered when the index has nothing close.
     */
    private void offerSuggestions(String query, int found) {
        if (suggestions == null) {
            return;
        }
        suggestions.getChildren().clear();
        List<String> ideas = List.of();
        if (found == 0 && query != null && !query.isBlank()) {
            try {
                ideas = facades.search().getSearchSuggestions(query);
            } catch (RuntimeException e) {
                ideas = List.of();
            }
        }
        if (ideas.isEmpty()) {
            suggestions.setVisible(false);
            suggestions.setManaged(false);
            return;
        }
        suggestions.getChildren().add(Fas.muted("Closest matches on this case:"));
        for (String idea : ideas) {
            Button chip = Fas.ghost(idea, null);
            chip.setOnAction(e -> {
                queryField.setText(idea);
                runSearch(true);
            });
            suggestions.getChildren().add(chip);
        }
        suggestions.setVisible(true);
        suggestions.setManaged(true);
    }

    private Integer resolveId(String display, String anyLabel, boolean source) {
        if (display == null || anyLabel.equals(display)) {
            return null;
        }
        try {
            if (source) {
                return facades.sources().listSources().stream()
                        .filter(s -> display.equals(s.name())).map(s -> s.id())
                        .findFirst().orElse(null);
            }
            return facades.aspects().listAspects().stream()
                    .filter(s -> display.equals(s.name())).map(s -> s.id())
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void preview(SearchResultDto r) {
        if (r == null) {
            snippetArea.clear();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(r.fileName()).append('\n')
                .append(r.filePath()).append("\n\n")
                .append("Type: ").append(nz(r.fileType()))
                .append("   Size: ").append(DashboardScreen.humanBytes(r.fileSize()))
                .append("   Status: ").append(nz(r.status())).append('\n')
                .append("SHA-256: ").append(nz(r.sha256())).append('\n')
                .append("MD5: ").append(nz(r.md5())).append("\n\n");
        if (!r.snippets().isEmpty()) {
            sb.append("Matching text\n");
            for (String s : r.snippets()) {
                sb.append("  \u2022 ").append(s.replace('\n', ' ')).append('\n');
            }
        } else {
            try {
                PreviewDto p = facades.preview().getPreview(r.id());
                if (p.content() != null) {
                    String c = p.content();
                    sb.append(c.length() > 4000 ? c.substring(0, 4000) + "\n\u2026" : c);
                }
            } catch (RuntimeException ignored) {
                sb.append("(no preview available)");
            }
        }
        snippetArea.setText(sb.toString());
    }

    private void saveSearch() {
        String q = queryField.getText();
        if (q == null || q.isBlank()) {
            return;
        }
        TextInputDialog d = new TextInputDialog(q);
        d.setTitle("Save Search");
        d.setHeaderText("Name this search");
        d.setContentText("Name:");
        d.showAndWait().ifPresent(name -> facades.history().saveSearch(name, q));
    }

    private void exportCsv() {
        if (rows.isEmpty()) {
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Search Results");
        fc.setInitialFileName("search-results.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File f = fc.showSaveDialog(table.getScene().getWindow());
        if (f == null) {
            return;
        }
        try {
            byte[] csv = ExportFacade.exportSearchResultsCsv(List.copyOf(rows));
            Files.write(f.toPath(), csv);
            Alert a = new Alert(Alert.AlertType.INFORMATION,
                    "Exported " + rows.size() + " row(s) to\n" + f.getAbsolutePath(),
                    ButtonType.OK);
            a.setHeaderText("Export complete");
            a.showAndWait();
        } catch (Exception ex) {
            Alert a = new Alert(Alert.AlertType.ERROR, String.valueOf(ex.getMessage()),
                    ButtonType.OK);
            a.setHeaderText("Export failed");
            a.showAndWait();
        }
    }

    /**
     * Runs a query supplied by another destination.
     *
     * <p>Used when a chart segment or a term detail hands the operator over to search.
     */
    public void runQuery(String query) {
        if (queryField == null || query == null || query.isBlank()) {
            return;
        }
        queryField.setText(query);
        runSearch(true);
    }

    @Override
    public void onShow() {
        try {
            String keepSrc = sourceBox.getValue();
            sourceBox.getItems().setAll("Any source");
            facades.sources().listSources().forEach(s -> sourceBox.getItems().add(s.name()));
            sourceBox.setValue(sourceBox.getItems().contains(keepSrc) ? keepSrc : "Any source");

            String keepAspect = aspectBox.getValue();
            aspectBox.getItems().setAll("Any aspect");
            facades.aspects().listAspects().forEach(a -> aspectBox.getItems().add(a.name()));
            aspectBox.setValue(aspectBox.getItems().contains(keepAspect) ? keepAspect : "Any aspect");
        } catch (RuntimeException ignored) {
            // filter lists are a convenience; search still works without them
        }
    }

    /** Accepts an ISO date or blank; anything else is reported to the user. */
    private static java.time.LocalDate parseDate(String s, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw FacadeException.validation(field + " must be in YYYY-MM-DD format");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
