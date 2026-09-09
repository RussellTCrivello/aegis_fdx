package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.KeywordDto;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ui.AnalyzeAction;
import com.aegis.fdx.ui.Background;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keywords: the curated phrases of interest, each belonging to a category.
 *
 * <p>Management view with summary tiles (Total / Active / Duplicates), search,
 * status and category filters, sorting, paging and bulk operations including
 * duplicate merging. Usage counts are whole-case file counts from
 * {@code path_keyword}, and status is derived: a keyword is Active when at
 * least one file carries it.
 */
public final class KeywordsScreen implements Screen {

    private final AegisFacades facades;
    private final AgentService agent;
    private final Router router;

    private List<KeywordDto> all = List.of();
    private final java.util.Map<Integer, Integer> fileCounts = new java.util.HashMap<>();
    private final Set<Integer> selected = new HashSet<>();
    private final ObservableList<KeywordDto> rows = FXCollections.observableArrayList();

    private VBox tileTotal;
    private VBox tileActive;
    private VBox tileDupes;

    private TextField searchField;
    private ComboBox<String> statusBox;
    private ComboBox<String> categoryBox;
    private ComboBox<String> sortBox;
    private ComboBox<String> orderBox;
    private ComboBox<Integer> perPage;
    private ComboBox<String> displayBox;
    private TableView<KeywordDto> table;
    private FlowPane grid;
    private ScrollPane gridScroll;
    private StackPane views;
    private Label countLabel;
    private Label pageLabel;
    private Label updateLabel;
    private HBox pageBar;
    private int page;
    private final Background.Job updateJob = Background.job();

    public KeywordsScreen(AegisFacades facades, AgentService agent) {
        this(facades, agent, null);
    }

    public KeywordsScreen(AegisFacades facades, AgentService agent, Router router) {
        this.facades = facades;
        this.agent = agent;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Keywords";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Keywords";
    }

    @Override
    public String icon() {
        return Icons.KEY;
    }

    @Override
    public Node build() {
        tileTotal = Fas.summaryTile("0", "Total Keywords");
        tileActive = Fas.summaryTile("0", "Active");
        tileDupes = Fas.summaryTile("0", "Duplicates");
        VBox tiles = new VBox(Fas.statsGrid(tileTotal, tileActive, tileDupes));

        searchField = Fas.field("Search Keywords...");
        searchField.setPrefWidth(200);
        searchField.textProperty().addListener((o, a, b) -> {
            page = 0;
            applyFilter();
        });
        statusBox = new ComboBox<>(FXCollections.observableArrayList(
                "All statuses", "Active", "Inactive"));
        statusBox.setValue("All statuses");
        statusBox.setPrefWidth(130);
        statusBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });
        categoryBox = new ComboBox<>();
        categoryBox.getItems().add("All categories");
        categoryBox.setValue("All categories");
        categoryBox.setPrefWidth(160);
        categoryBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });
        sortBox = new ComboBox<>(FXCollections.observableArrayList(
                "Keyword", "Usage", "Category", "Status"));
        sortBox.setValue("Keyword");
        sortBox.setPrefWidth(120);
        sortBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });
        orderBox = new ComboBox<>(FXCollections.observableArrayList("Asc", "Desc"));
        orderBox.setValue("Asc");
        orderBox.setPrefWidth(90);
        orderBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });
        perPage = new ComboBox<>(FXCollections.observableArrayList(25, 50, 100));
        perPage.setValue(50);
        perPage.setPrefWidth(90);
        perPage.setOnAction(e -> {
            page = 0;
            applyFilter();
        });
        displayBox = new ComboBox<>(FXCollections.observableArrayList("Cards", "List"));
        displayBox.setValue("Cards");
        displayBox.setPrefWidth(100);
        displayBox.setOnAction(e -> applyFilter());

        Button add = Fas.primary("Add Keyword", Icons.PLUS);
        add.setOnAction(e -> openForm());

        Button update = Fas.secondary("Update Keywords", Icons.REFRESH);
        update.setOnAction(e -> updateKeywords());

        Button merge = Fas.outline("Merge Duplicates", Icons.FUNNEL);
        merge.setOnAction(e -> mergeDuplicates());

        Button export = Fas.outline("Export CSV", Icons.DOWNLOAD);
        export.setOnAction(e -> Fas.saveBytes(table, "Export Keywords", "keywords.csv",
                com.aegis.fdx.facade.ExportFacade.exportTermsCsv(
                        facades.relationships().keywords(null, 100_000, 0).results())));

        Button selectAll = Fas.ghost("Select All", Icons.CHECK_ALL);
        selectAll.setOnAction(e -> {
            for (KeywordDto k : rows) {
                selected.add(k.id());
            }
            table.refresh();
            applyFilter();
        });
        Button selectNone = Fas.ghost("Select None", Icons.CLOSE);
        selectNone.setOnAction(e -> {
            selected.clear();
            table.refresh();
            applyFilter();
        });
        Button editSel = Fas.outline("Edit Selected", Icons.PENCIL);
        editSel.setOnAction(e -> {
            List<KeywordDto> sel = selectedKeywords();
            if (sel.size() == 1) {
                rename(sel.get(0));
            } else if (sel.isEmpty()) {
                err("Select a keyword first.");
            } else {
                err("Select a single keyword to edit.");
            }
        });
        Button bulk = Fas.danger("Delete Selected", Icons.TRASH);
        bulk.setOnAction(e -> deleteSelected());

        Button analyze = AnalyzeAction.button(agent, "Analyze Keywords",
                "Which keywords occur most across the case, and in which material?",
                () -> {
                    KeywordDto sel = table.getSelectionModel().getSelectedItem();
                    AgentContext c = AgentContext.ofScreen("Keywords");
                    return sel == null ? c : c.withKeyword(sel.id());
                });

        updateLabel = Fas.muted("");
        VBox filterBar = new VBox(10,
                Fas.row(10,
                        Fas.formField("Search Keywords", searchField),
                        Fas.formField("Status", statusBox),
                        Fas.formField("Category", categoryBox),
                        Fas.formField("Sort By", sortBox),
                        Fas.formField("Order", orderBox),
                        Fas.formField("Per Page", perPage),
                        Fas.formField("Display", displayBox)),
                Fas.row(8, selectAll, selectNone, editSel, bulk,
                        Fas.spacer(), updateLabel, analyze, merge, export, update, add));
        filterBar.getStyleClass().add("filter-bar");

        countLabel = Fas.muted("0 keywords");
        table = new TableView<>(rows);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(Fas.emptyState("No keywords yet."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<KeywordDto, KeywordDto> cSel = new TableColumn<>("✓");
        cSel.setPrefWidth(45);
        cSel.setSortable(false);
        cSel.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cSel.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(KeywordDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                CheckBox cb = new CheckBox();
                cb.setSelected(selected.contains(item.id()));
                cb.setOnAction(e -> {
                    if (cb.isSelected()) {
                        selected.add(item.id());
                    } else {
                        selected.remove(item.id());
                    }
                });
                setGraphic(cb);
            }
        });

        TableColumn<KeywordDto, String> cNum = new TableColumn<>("#");
        cNum.setPrefWidth(60);
        cNum.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().id())));

        TableColumn<KeywordDto, String> cKw = new TableColumn<>("Keyword");
        cKw.setPrefWidth(280);
        cKw.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().keyword()));

        TableColumn<KeywordDto, String> cUse = new TableColumn<>("Usage");
        cUse.setPrefWidth(90);
        cUse.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                usageLabel(fileCounts.getOrDefault(c.getValue().id(), 0))));

        TableColumn<KeywordDto, KeywordDto> cStatus = new TableColumn<>("Status");
        cStatus.setPrefWidth(100);
        cStatus.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cStatus.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(KeywordDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                boolean active = fileCounts.getOrDefault(item.id(), 0) > 0;
                setGraphic(Fas.badge(active ? "Active" : "Inactive",
                        active ? "success" : "muted"));
            }
        });

        TableColumn<KeywordDto, String> cCat = new TableColumn<>("Category");
        cCat.setPrefWidth(160);
        cCat.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().categoryWord()));

        TableColumn<KeywordDto, KeywordDto> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(150);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(KeywordDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button view = Fas.ghost("", Icons.EYE);
                view.setOnAction(e -> router.openKeyword(item.id()));
                Button edit = Fas.ghost("", Icons.PENCIL);
                edit.setOnAction(e -> rename(item));
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> {
                    facades.keywords().deleteKeyword(item.id());
                    selected.remove(item.id());
                    onShow();
                });
                setGraphic(Fas.row(2, view, edit, del));
            }
        });

        table.getColumns().addAll(List.of(cSel, cNum, cKw, cUse, cStatus, cCat, cAct));
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<KeywordDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openKeyword(row.getItem().id());
                }
            });
            return row;
        });

        grid = new FlowPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPrefWrapLength(1120);
        gridScroll = new ScrollPane(grid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        views = new StackPane(table, gridScroll);
        VBox.setVgrow(views, Priority.ALWAYS);

        pageLabel = Fas.muted("0 - 0 of 0");
        pageBar = new HBox(6);

        VBox body = new VBox(10, countLabel, views,
                Fas.row(8, pageLabel, Fas.spacer(), pageBar));

        VBox content = new VBox(16,
                Fas.pageHeader("Keywords", breadcrumb()),
                tiles, filterBar,
                Fas.cardWithHeader("Keyword List",
                        "Double-click to open the keyword detail", body));
        content.setPadding(new Insets(20));
        return content;
    }

    private static String usageLabel(int files) {
        return files + (files == 1 ? " file" : " files");
    }

    @Override
    public void onShow() {
        try {
            all = List.copyOf(facades.keywords().listKeywords(100_000, 0).results());
            fileCounts.clear();
            fileCounts.putAll(facades.relationships().keywordFileCounts());
            selected.retainAll(all.stream().map(KeywordDto::id).toList());

            java.util.TreeSet<String> cats = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (KeywordDto k : all) {
                if (k.categoryWord() != null) {
                    cats.add(k.categoryWord());
                }
            }
            String keep = categoryBox == null ? null : categoryBox.getValue();
            List<String> items = new ArrayList<>();
            items.add("All categories");
            items.addAll(cats);
            if (categoryBox != null) {
                categoryBox.getItems().setAll(items);
                categoryBox.setValue(keep != null && items.contains(keep) ? keep : "All categories");
            }

            int active = 0;
            for (KeywordDto k : all) {
                if (fileCounts.getOrDefault(k.id(), 0) > 0) {
                    active++;
                }
            }
            int dupeGroups = 0;
            try {
                dupeGroups = facades.keywords().findDuplicates().size();
            } catch (RuntimeException ignored) {
                dupeGroups = 0;
            }
            Fas.setSummary(tileTotal, String.format("%,d", all.size()));
            Fas.setSummary(tileActive, String.valueOf(active));
            Fas.setSummary(tileDupes, String.valueOf(dupeGroups));

            page = 0;
            applyFilter();
        } catch (RuntimeException e) {
            all = List.of();
            rows.clear();
            buildGrid();
        }
    }

    private void applyFilter() {
        String q = searchField == null || searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase();
        String status = statusBox == null ? "All statuses" : statusBox.getValue();
        String cat = categoryBox == null ? "All categories" : categoryBox.getValue();
        List<KeywordDto> filtered = new ArrayList<>();
        for (KeywordDto k : all) {
            if (!q.isEmpty() && (k.keyword() == null
                    || !k.keyword().toLowerCase().contains(q))) {
                continue;
            }
            int files = fileCounts.getOrDefault(k.id(), 0);
            if (status != null && "Active".equals(status) && files == 0) {
                continue;
            }
            if (status != null && "Inactive".equals(status) && files > 0) {
                continue;
            }
            if (cat != null && !"All categories".equals(cat) && !cat.equalsIgnoreCase(k.categoryWord())) {
                continue;
            }
            filtered.add(k);
        }
        String sort = sortBox == null || sortBox.getValue() == null ? "Keyword" : sortBox.getValue();
        boolean desc = orderBox != null && "Desc".equals(orderBox.getValue());
        switch (sort) {
            case "Usage" -> filtered.sort((a, b) -> Integer.compare(
                    fileCounts.getOrDefault(a.id(), 0), fileCounts.getOrDefault(b.id(), 0)));
            case "Category" -> filtered.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                    nz(a.categoryWord()), nz(b.categoryWord())));
            case "Status" -> filtered.sort((a, b) -> Integer.compare(
                    fileCounts.getOrDefault(a.id(), 0) > 0 ? 1 : 0,
                    fileCounts.getOrDefault(b.id(), 0) > 0 ? 1 : 0));
            default -> filtered.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                    nz(a.keyword()), nz(b.keyword())));
        }
        if (desc) {
            java.util.Collections.reverse(filtered);
        }

        int limit = perPage == null || perPage.getValue() == null ? 50 : perPage.getValue();
        int total = filtered.size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) limit));
        page = Math.min(Math.max(0, page), pages - 1);
        int from = page * limit;
        int to = Math.min(from + limit, total);
        rows.setAll(from < to ? filtered.subList(from, to) : List.of());
        countLabel.setText(total + (total == 1 ? " keyword" : " keywords"));
        pageLabel.setText(total == 0 ? "0 - 0 of 0"
                : (from + 1) + " - " + to + " of " + String.format("%,d", total));
        buildPageBar(pages);
        buildGrid();
    }

    private void buildGrid() {
        grid.getChildren().clear();
        if (rows.isEmpty()) {
            grid.getChildren().add(Fas.emptyState("No keywords yet."));
        }
        for (KeywordDto k : rows) {
            grid.getChildren().add(buildKeywordCard(k));
        }
        boolean cards = displayBox == null || "Cards".equals(displayBox.getValue());
        gridScroll.setVisible(cards);
        gridScroll.setManaged(cards);
        table.setVisible(!cards);
        table.setManaged(!cards);
    }

    private VBox buildKeywordCard(KeywordDto k) {
        int files = fileCounts.getOrDefault(k.id(), 0);
        boolean active = files > 0;
        CheckBox cb = new CheckBox();
        cb.setSelected(selected.contains(k.id()));
        cb.setOnAction(e -> {
            if (cb.isSelected()) {
                selected.add(k.id());
            } else {
                selected.remove(k.id());
            }
        });
        Label title = new Label(nz(k.keyword()));
        title.getStyleClass().add("entity-card-title");
        title.setWrapText(true);
        HBox head = Fas.row(8, cb, title);
        Label cat = Fas.muted("Category: " + nz(k.categoryWord()));
        Label use = Fas.muted(usageLabel(files));
        Label status = Fas.badge(active ? "Active" : "Inactive",
                active ? "success" : "muted");
        Button view = Fas.ghost("", Icons.EYE);
        view.setOnAction(e -> router.openKeyword(k.id()));
        Button edit = Fas.ghost("", Icons.PENCIL);
        edit.setOnAction(e -> rename(k));
        HBox actions = Fas.row(4, Fas.spacer(), status, view, edit);
        VBox card = new VBox(8, head, cat, use, actions);
        card.getStyleClass().add("entity-card");
        card.setPrefWidth(300);
        return card;
    }

    /**
     * Re-derives every file-word and file-keyword edge from the stored case content.
     *
     * <p>The analysis runs off the FX thread: it re-reads every registered file, so
     * running it on the event thread would freeze the interface on any real case. The
     * counts reported are the analyzer's own totals — files scanned, keyword links —
     * and the list refreshes from the database only after the run commits.
     */
    private void updateKeywords() {
        if (updateJob.busy()) {
            return;
        }
        updateLabel.setText("Analyzing...");
        updateJob.run(
                () -> facades.relationshipAnalyzer().analyzeAll(null),
                r -> {
                    updateLabel.setText(String.format(
                            "%,d files scanned, %,d file-keyword links",
                            r.filesScanned(), r.keywordLinks()));
                    onShow();
                },
                t -> {
                    updateLabel.setText("");
                    err("Update failed: " + (t.getMessage() == null ? t : t.getMessage()));
                });
    }

    private void buildPageBar(int pages) {
        pageBar.getChildren().clear();
        if (pages <= 1) {
            return;
        }
        Button prev = Fas.pageButton("Previous");
        prev.setDisable(page == 0);
        prev.setOnAction(e -> {
            page = Math.max(0, page - 1);
            applyFilter();
        });
        Button next = Fas.pageButton("Next");
        next.setDisable(page >= pages - 1);
        next.setOnAction(e -> {
            page = Math.min(pages - 1, page + 1);
            applyFilter();
        });
        pageBar.getChildren().addAll(prev, next);
    }

    private List<KeywordDto> selectedKeywords() {
        List<KeywordDto> out = new ArrayList<>();
        for (KeywordDto k : all) {
            if (selected.contains(k.id())) {
                out.add(k);
            }
        }
        return out;
    }

    private void deleteSelected() {
        List<KeywordDto> sel = selectedKeywords();
        if (!table.getSelectionModel().getSelectedItems().isEmpty() && sel.isEmpty()) {
            sel = List.copyOf(table.getSelectionModel().getSelectedItems());
        }
        if (sel.isEmpty()) {
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + sel.size() + " keyword(s)? This cannot be undone.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete Confirmation");
        List<KeywordDto> doomed = sel;
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                facades.keywords().bulkDeleteKeywords(
                        doomed.stream().map(KeywordDto::id).toList());
                selected.clear();
                onShow();
            }
        });
    }

    private void mergeDuplicates() {
        Map<String, Integer> d = facades.keywords().findDuplicates();
        if (d.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION, "Every keyword phrase is unique.",
                    ButtonType.OK);
            a.setHeaderText("No duplicates found");
            a.showAndWait();
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                d.entrySet().stream()
                        .map(x -> x.getKey() + "  \u00d7" + x.getValue())
                        .reduce((x, y) -> x + "\n" + y).orElse("")
                        + "\n\nMerge each group into its oldest keyword? File links move across;"
                        + " nothing is lost.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText(d.size() + " duplicated keyword phrase(s)");
        a.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            int removed = facades.keywords().mergeDuplicates();
            onShow();
            Alert done = new Alert(Alert.AlertType.INFORMATION,
                    removed + " duplicate keyword(s) merged.", ButtonType.OK);
            done.setHeaderText("Merge complete");
            done.showAndWait();
        });
    }

    private void rename(KeywordDto item) {
        TextInputDialog d = new TextInputDialog(item.keyword());
        d.setTitle("Edit Keyword");
        d.setHeaderText("Rename keyword phrase");
        d.setContentText("Keyword:");
        d.showAndWait().ifPresent(k -> {
            facades.keywords().updateKeyword(item.id(), k);
            onShow();
        });
    }

    private void openForm() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Add Keyword");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField phrase = Fas.field("Keyword phrase (two or more words)");
        ComboBox<String> category = new ComboBox<>();
        try {
            category.getItems().setAll(facades.categories().listCategories(500, 0)
                    .results().stream().map(c -> c.word()).toList());
        } catch (RuntimeException ignored) {
            // leave empty; validation below reports the real problem
        }
        category.setMaxWidth(Double.MAX_VALUE);

        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(10);
        g.setPadding(new Insets(16));
        g.add(Fas.formField("Keyword Phrase *", phrase), 0, 0);
        g.add(Fas.formField("Category *", category), 0, 1);
        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().setPrefWidth(440);

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) {
                return;
            }
            if (category.getValue() == null) {
                err("Create a category first, then attach keywords to it.");
                return;
            }
            try {
                facades.keywords().createKeyword(phrase.getText(), category.getValue());
                onShow();
            } catch (FacadeException ex) {
                err(ex.getMessage());
            }
        });
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void err(String m) {
        Alert a = new Alert(Alert.AlertType.ERROR, m, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }
}
