package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.facade.SortField;
import com.aegis.fdx.facade.SortOrder;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * A field-by-field query builder for operators who would rather not write query syntax.
 *
 * <p>Each row contributes a clause; the composed expression is shown live, so the
 * builder also teaches the query language rather than hiding it. The result runs
 * through the same {@link SearchCriteria} path as the plain search destination.
 */
public final class AdvancedSearchScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private TextField allWords;
    private TextField exactPhrase;
    private TextField anyWords;
    private TextField noneWords;
    private TextField fromField;
    private TextField toField;
    private TextField subjectField;
    private TextField nameField;
    private ComboBox<String> typeBox;
    private ComboBox<String> sourceBox;
    private ComboBox<String> aspectBox;
    private TextField dateFrom;
    private TextField dateTo;
    private ComboBox<SortField> sortBy;
    private ComboBox<SortOrder> sortOrder;
    private CheckBox includeHidden;
    private Label expression;
    private Label resultLabel;
    private final ObservableList<SearchResultDto> rows = FXCollections.observableArrayList();

    public AdvancedSearchScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router;
    }

    @Override public String title() { return "Advanced Search"; }
    @Override public String breadcrumb() { return "Home / Search / Advanced"; }
    @Override public String icon() { return Icons.FUNNEL; }

    @Override
    public Node build() {
        allWords = Fas.field("all of these words");
        exactPhrase = Fas.field("this exact phrase");
        anyWords = Fas.field("any of these words");
        noneWords = Fas.field("none of these words");
        fromField = Fas.field("sender");
        toField = Fas.field("recipient");
        subjectField = Fas.field("subject contains");
        nameField = Fas.field("file name contains");
        dateFrom = Fas.field("YYYY-MM-DD");
        dateTo = Fas.field("YYYY-MM-DD");

        typeBox = combo("Any type", "txt", "pdf", "docx", "xlsx", "eml", "msg",
                "html", "csv", "png", "jpg", "zip");
        sourceBox = combo("Any source");
        aspectBox = combo("Any aspect");

        sortBy = new ComboBox<>(FXCollections.observableArrayList(SortField.values()));
        sortBy.setValue(SortField.RELEVANCE);
        sortOrder = new ComboBox<>(FXCollections.observableArrayList(SortOrder.values()));
        sortOrder.setValue(SortOrder.DESCENDING);
        includeHidden = new CheckBox("Include hidden items");

        expression = new Label("(nothing entered yet)");
        expression.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 11px;"
                + " -fx-text-fill: " + Fas.PRIMARY + ";");
        expression.setWrapText(true);
        resultLabel = Fas.muted("");

        for (TextField f : new TextField[]{allWords, exactPhrase, anyWords, noneWords,
                fromField, toField, subjectField, nameField}) {
            f.textProperty().addListener((o, a, b) -> updateExpression());
        }
        typeBox.setOnAction(e -> updateExpression());

        Button run = Fas.primary("Search", Icons.SEARCH);
        run.setOnAction(e -> run());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> clear());
        Button toPlain = Fas.outline("Open in Search", null);
        toPlain.setOnAction(e -> router.openSearch(buildExpression()));

        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(10);
        int r = 0;
        g.add(Fas.formField("All of these words", allWords), 0, r);
        g.add(Fas.formField("This exact phrase", exactPhrase), 1, r++);
        g.add(Fas.formField("Any of these words", anyWords), 0, r);
        g.add(Fas.formField("None of these words", noneWords), 1, r++);
        g.add(Fas.formField("From", fromField), 0, r);
        g.add(Fas.formField("To", toField), 1, r++);
        g.add(Fas.formField("Subject", subjectField), 0, r);
        g.add(Fas.formField("File name", nameField), 1, r++);
        g.add(Fas.formField("File type", typeBox), 0, r);
        g.add(Fas.formField("Source", sourceBox), 1, r++);
        g.add(Fas.formField("Aspect", aspectBox), 0, r);
        g.add(Fas.formField("Date from", dateFrom), 1, r++);
        g.add(Fas.formField("Date to", dateTo), 0, r);
        g.add(Fas.formField("Sort by", sortBy), 1, r++);
        g.add(Fas.formField("Order", sortOrder), 0, r);
        g.add(new VBox(5, Fas.fieldLabel("Options"), includeHidden), 1, r);

        TableView<SearchResultDto> table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState("Fill in some fields and press Search."));
        VBox.setVgrow(table, Priority.ALWAYS);
        table.getColumns().add(col("File Name", 230, SearchResultDto::fileName));
        table.getColumns().add(col("Type", 70, SearchResultDto::fileType));
        table.getColumns().add(col("Source", 130, SearchResultDto::sourceName));
        table.getColumns().add(col("Matches", 80,
                x -> String.valueOf(x.matchCount())));
        table.getColumns().add(col("Path", 320, SearchResultDto::filePath));

        VBox content = new VBox(16,
                Fas.pageHeader("Advanced Search", null, toPlain, clear, run),
                Fas.cardWithHeader("Build a Query",
                        "Each field adds a clause; the composed query is shown below", g),
                Fas.card(new VBox(6, Fas.fieldLabel("COMPOSED QUERY"), expression)),
                Fas.cardWithHeader("Results", null, new VBox(10, resultLabel, table)));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private ComboBox<String> combo(String... values) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(values));
        c.setValue(values[0]);
        c.setPrefWidth(170);
        return c;
    }

    private static TableColumn<SearchResultDto, String> col(
            String n, double w, java.util.function.Function<SearchResultDto, String> f) {
        TableColumn<SearchResultDto, String> c = new TableColumn<>(n);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    /** Composes the field values into the engine's query grammar. */
    String buildExpression() {
        StringBuilder q = new StringBuilder();
        appendTerms(q, allWords.getText(), "AND", null);
        if (has(exactPhrase)) {
            and(q).append('"').append(exactPhrase.getText().trim()).append('"');
        }
        if (has(anyWords)) {
            String[] parts = anyWords.getText().trim().split("\\s+");
            if (parts.length > 0) {
                and(q).append('(').append(String.join(" OR ", parts)).append(')');
            }
        }
        if (has(noneWords)) {
            for (String w : noneWords.getText().trim().split("\\s+")) {
                and(q).append("NOT ").append(w);
            }
        }
        field(q, "from", fromField);
        field(q, "to", toField);
        field(q, "subject", subjectField);
        field(q, "name", nameField);
        if (typeBox.getValue() != null && !"Any type".equals(typeBox.getValue())) {
            and(q).append("ext:").append(typeBox.getValue());
        }
        return q.length() == 0 ? "*" : q.toString();
    }

    private static void appendTerms(StringBuilder q, String text, String join, String prefix) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String w : text.trim().split("\\s+")) {
            and(q).append(prefix == null ? "" : prefix + ":").append(w);
        }
    }

    private static void field(StringBuilder q, String name, TextField f) {
        if (f.getText() != null && !f.getText().isBlank()) {
            String v = f.getText().trim();
            and(q).append(name).append(':')
                    .append(v.contains(" ") ? "\"" + v + "\"" : v);
        }
    }

    private static StringBuilder and(StringBuilder q) {
        if (q.length() > 0) {
            q.append(" AND ");
        }
        return q;
    }

    private static boolean has(TextField f) {
        return f.getText() != null && !f.getText().isBlank();
    }

    private void updateExpression() {
        expression.setText(buildExpression());
    }

    private void clear() {
        for (TextField f : new TextField[]{allWords, exactPhrase, anyWords, noneWords,
                fromField, toField, subjectField, nameField, dateFrom, dateTo}) {
            f.clear();
        }
        typeBox.setValue("Any type");
        sourceBox.setValue("Any source");
        aspectBox.setValue("Any aspect");
        sortBy.setValue(SortField.RELEVANCE);
        sortOrder.setValue(SortOrder.DESCENDING);
        includeHidden.setSelected(false);
        rows.clear();
        resultLabel.setText("");
        updateExpression();
    }

    private void run() {
        String q = buildExpression();
        try {
            SearchCriteria c = SearchCriteria.of(q)
                    .source(resolve(sourceBox.getValue(), "Any source", true))
                    .aspect(resolve(aspectBox.getValue(), "Any aspect", false))
                    .sortBy(sortBy.getValue(), sortOrder.getValue())
                    .includeHidden(includeHidden.isSelected())
                    .limit(200);
            if (has(dateFrom)) {
                c = c.from(java.time.LocalDate.parse(dateFrom.getText().trim()));
            }
            if (has(dateTo)) {
                c = c.to(java.time.LocalDate.parse(dateTo.getText().trim()));
            }
            long t0 = System.nanoTime();
            var page = facades.search().search(c);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            rows.setAll(page.results());
            resultLabel.setText(String.format("%,d result(s) in %.0f ms",
                    page.totalCount(), ms));
            facades.history().addSearch(q, page.totalCount());
        } catch (java.time.format.DateTimeParseException e) {
            resultLabel.setText("Dates must be in YYYY-MM-DD format");
            rows.clear();
        } catch (FacadeException e) {
            resultLabel.setText(e.getMessage());
            rows.clear();
        }
    }

    private Integer resolve(String display, String all, boolean source) {
        if (display == null || all.equals(display)) return null;
        try {
            if (source) {
                return facades.sources().listSources().stream()
                        .filter(s -> display.equals(s.name())).map(s -> s.id())
                        .findFirst().orElse(null);
            }
            return facades.aspects().listAspects().stream()
                    .filter(a -> display.equals(a.name())).map(a -> a.id())
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void onShow() {
        try {
            String keepS = sourceBox.getValue();
            sourceBox.getItems().setAll("Any source");
            facades.sources().listSources().forEach(s -> sourceBox.getItems().add(s.name()));
            sourceBox.setValue(sourceBox.getItems().contains(keepS) ? keepS : "Any source");

            String keepA = aspectBox.getValue();
            aspectBox.getItems().setAll("Any aspect");
            facades.aspects().listAspects().forEach(a -> aspectBox.getItems().add(a.name()));
            aspectBox.setValue(aspectBox.getItems().contains(keepA) ? keepA : "Any aspect");
        } catch (RuntimeException ignored) {
            // filter lists are a convenience
        }
        updateExpression();
    }
}
