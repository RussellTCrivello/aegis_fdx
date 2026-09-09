package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.WordDto;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ui.AnalyzeAction;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Categories: the classification taxonomy, each with its linked words and the
 * files it reaches.
 *
 * <p>Card grid and list views with search, sorting and full pagination
 * (First / Previous / numbered / Next / Last). Each card reports how many files
 * the category reaches and how many words it holds, so the analyst sees the
 * weight of a category at a glance. Selecting a category shows its words on the
 * right; double-clicking opens the category detail with its files.
 */
public final class CategoriesScreen implements Screen {

    private final AegisFacades facades;
    private final AgentService agent;
    private final Router router;

    private List<CategoryDto> all = List.of();
    private final ObservableList<CategoryDto> rows = FXCollections.observableArrayList();
    private final java.util.Map<Integer, Integer> fileCounts = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> wordCounts = new java.util.HashMap<>();
    private final ObservableList<WordDto> words = FXCollections.observableArrayList();
    private final java.util.Map<Integer, Integer> wordFileCounts = new java.util.HashMap<>();

    private TableView<CategoryDto> table;
    private FlowPane grid;
    private ScrollPane gridScroll;
    private StackPane views;
    private Label countLabel;
    private Label wordsHeader;
    private Label pageLabel;
    private HBox pageBar;
    private TextField searchField;
    private ComboBox<String> sortBox;
    private ComboBox<String> displayBox;
    private ComboBox<Integer> perPageBox;
    private int page;

    public CategoriesScreen(AegisFacades facades, AgentService agent) {
        this(facades, agent, null);
    }

    public CategoriesScreen(AegisFacades facades, AgentService agent, Router router) {
        this.facades = facades;
        this.agent = agent;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Categories";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Categories";
    }

    @Override
    public String icon() {
        return Icons.TAGS;
    }

    @Override
    public Node build() {
        Button add = Fas.primary("Add Category", Icons.PLUS);
        add.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Add Category");
            d.setHeaderText("Create a category");
            d.setContentText("Category word:");
            d.showAndWait().ifPresent(w -> {
                try {
                    facades.categories().createCategory(w);
                    onShow();
                } catch (FacadeException ex) {
                    err(ex.getMessage());
                }
            });
        });

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        Button analyze = AnalyzeAction.button(agent, "Analyze Category",
                "What material falls under the selected category, and what does it "
                        + "have in common?",
                () -> {
                    CategoryDto sel = table.getSelectionModel().getSelectedItem();
                    AgentContext c = AgentContext.ofScreen("Categories");
                    return sel == null ? c : c.withCategory(sel.id());
                });

        Button dupes = Fas.outline("Find Duplicates", Icons.FUNNEL);
        dupes.setOnAction(e -> mergeDuplicates());

        Button export = Fas.outline("Export CSV", Icons.DOWNLOAD);
        export.setOnAction(e -> Fas.saveBytes(table, "Export Categories", "categories.csv",
                com.aegis.fdx.facade.ExportFacade.exportTermsCsv(
                        facades.relationships().categories(null, 100_000, 0).results())));

        searchField = Fas.field("Search categories...");
        searchField.setPrefWidth(200);
        searchField.textProperty().addListener((o, a, b) -> {
            page = 0;
            applyFilter();
        });
        sortBox = new ComboBox<>(FXCollections.observableArrayList(
                "Name (A-Z)", "Name (Z-A)", "Most files", "Most words"));
        sortBox.setValue("Name (A-Z)");
        sortBox.setPrefWidth(140);
        sortBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });
        displayBox = new ComboBox<>(FXCollections.observableArrayList("Grid", "List"));
        displayBox.setValue("Grid");
        displayBox.setPrefWidth(100);
        displayBox.setOnAction(e -> applyFilter());
        perPageBox = new ComboBox<>(FXCollections.observableArrayList(10, 25, 50, 100));
        perPageBox.setValue(10);
        perPageBox.setPrefWidth(90);
        perPageBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });

        VBox filterBar = new VBox(10,
                Fas.row(10,
                        Fas.formField("Search", searchField),
                        Fas.formField("Sort By", sortBox),
                        Fas.formField("Display", displayBox),
                        Fas.formField("Per Page", perPageBox),
                        Fas.spacer(), analyze, dupes, export, refresh, add));
        filterBar.getStyleClass().add("filter-bar");

        countLabel = Fas.muted("0 categories");
        table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState("No categories yet."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<CategoryDto, String> cId = new TableColumn<>("ID");
        cId.setPrefWidth(60);
        cId.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().id())));

        TableColumn<CategoryDto, String> cWord = new TableColumn<>("Category");
        cWord.setPrefWidth(180);
        cWord.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().word()));

        TableColumn<CategoryDto, String> cFiles = new TableColumn<>("Files");
        cFiles.setPrefWidth(70);
        cFiles.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(fileCounts.getOrDefault(c.getValue().id(), 0))));

        TableColumn<CategoryDto, String> cWords = new TableColumn<>("Words");
        cWords.setPrefWidth(70);
        cWords.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(wordCounts.getOrDefault(c.getValue().id(), 0))));

        TableColumn<CategoryDto, CategoryDto> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(110);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(CategoryDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button view = Fas.ghost("", Icons.EYE);
                view.setOnAction(e -> router.openCategoryDetail(item.id()));
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> {
                    facades.categories().deleteCategory(item.id());
                    onShow();
                });
                setGraphic(Fas.row(2, view, del));
            }
        });

        table.getColumns().addAll(List.of(cId, cWord, cFiles, cWords, cAct));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> loadWords(b));
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<CategoryDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openCategoryDetail(row.getItem().id());
                }
            });
            return row;
        });

        grid = new FlowPane(12, 12);
        grid.setPadding(new Insets(4));
        gridScroll = new ScrollPane(grid);
        gridScroll.setFitToWidth(true);
        gridScroll.setPrefHeight(420);
        VBox.setVgrow(gridScroll, Priority.ALWAYS);
        views = new StackPane(gridScroll, table);
        VBox.setVgrow(views, Priority.ALWAYS);

        pageLabel = Fas.muted("");
        pageBar = new HBox(6);

        // right pane — category words
        wordsHeader = Fas.muted("Select a category to see its words");
        ListView<WordDto> wordList = new ListView<>(words);
        wordList.setPlaceholder(Fas.emptyState("No words linked."));
        wordList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(WordDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                int n = wordFileCounts.getOrDefault(item.id(), 0);
                setText(null);
                setGraphic(Fas.row(8, new Label(item.word()), Fas.spacer(),
                        Fas.badge(n + (n == 1 ? " file" : " files"), n == 0 ? "secondary" : "info")));
            }
        });
        wordList.setOnMouseClicked(e -> {
            WordDto sel = wordList.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && sel != null) {
                router.openWord(sel.id());
            }
        });
        Button unlink = Fas.outline("Remove Word", null);
        unlink.setOnAction(e -> {
            CategoryDto cat = table.getSelectionModel().getSelectedItem();
            WordDto sel = wordList.getSelectionModel().getSelectedItem();
            if (cat == null || sel == null) {
                err("Select a category and one of its words first.");
                return;
            }
            try {
                facades.categories().removeWordFromCategory(cat.id(), sel.id());
                loadWords(cat);
                onShow();
            } catch (FacadeException ex) {
                err(ex.getMessage());
            }
        });
        VBox.setVgrow(wordList, Priority.ALWAYS);

        Button link = Fas.secondary("Link Word", Icons.LINK);
        link.setOnAction(e -> {
            CategoryDto sel = currentSelection();
            if (sel == null) {
                err("Select a category first.");
                return;
            }
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Link Word");
            d.setHeaderText("Link a word to \u201c" + sel.word() + "\u201d");
            d.setContentText("Word:");
            d.showAndWait().ifPresent(w -> {
                try {
                    facades.categories().linkWordToCategory(w, sel.word());
                    loadWords(sel);
                } catch (FacadeException ex) {
                    err(ex.getMessage());
                }
            });
        });

        VBox left = Fas.cardWithHeader("Categories", "Double-click a card to open the detail",
                new VBox(10, countLabel, views, Fas.row(8, pageLabel, Fas.spacer(), pageBar)));
        VBox right = Fas.cardWithHeader("Category Words", null,
                new VBox(10, Fas.row(8, wordsHeader, Fas.spacer(), unlink, link), wordList));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox split = new HBox(14, left, right);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader("Categories", breadcrumb()),
                filterBar, split);
        content.setPadding(new Insets(20));
        return content;
    }

    private CategoryDto currentSelection() {
        CategoryDto sel = table.getSelectionModel().getSelectedItem();
        if (sel != null) {
            return sel;
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void loadWords(CategoryDto cat) {
        if (cat == null) {
            words.clear();
            wordsHeader.setText("Select a category to see its words");
            return;
        }
        try {
            var page = facades.categories().getCategoryWords(cat.id(), 500, 0);
            wordFileCounts.clear();
            wordFileCounts.putAll(facades.relationships().wordFileCounts());
            words.setAll(page.results());
            wordsHeader.setText(cat.word() + " \u2014 " + words.size() + " word(s)");
        } catch (RuntimeException e) {
            words.clear();
        }
    }

    /**
     * Selects a category by id, so another destination can drill through to it.
     */
    public void selectCategory(int categoryId) {
        onShow();
        for (CategoryDto c : all) {
            if (c.id() == categoryId) {
                table.getSelectionModel().select(c);
                loadWords(c);
                return;
            }
        }
        // The category may sit on another page; find its page and show it.
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id() == categoryId) {
                int limit = perPageBox == null || perPageBox.getValue() == null
                        ? 10 : perPageBox.getValue();
                page = i / limit;
                applyFilter();
                for (CategoryDto c : rows) {
                    if (c.id() == categoryId) {
                        table.getSelectionModel().select(c);
                        loadWords(c);
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void onShow() {
        try {
            all = List.copyOf(facades.categories().listCategories(100_000, 0).results());
            fileCounts.clear();
            fileCounts.putAll(facades.relationships().categoryFileCounts());
            wordCounts.clear();
            for (CategoryDto c : all) {
                try {
                    // getCategoryWords reports the page size as its total, so ask for
                    // a full page and count the rows.
                    wordCounts.put(c.id(), facades.categories()
                            .getCategoryWords(c.id(), 10_000, 0).results().size());
                } catch (RuntimeException ignored) {
                    wordCounts.put(c.id(), 0);
                }
            }
            page = 0;
            applyFilter();
        } catch (RuntimeException e) {
            all = List.of();
            rows.clear();
        }
    }

    private void applyFilter() {
        String q = searchField == null || searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase();
        List<CategoryDto> filtered = new ArrayList<>();
        for (CategoryDto c : all) {
            if (q.isEmpty() || (c.word() != null && c.word().toLowerCase().contains(q))) {
                filtered.add(c);
            }
        }
        String sort = sortBox == null ? "Name (A-Z)" : sortBox.getValue();
        switch (sort) {
            case "Name (Z-A)" -> filtered.sort((a, b) ->
                    b.word().compareToIgnoreCase(a.word()));
            case "Most files" -> filtered.sort((a, b) -> Integer.compare(
                    fileCounts.getOrDefault(b.id(), 0), fileCounts.getOrDefault(a.id(), 0)));
            case "Most words" -> filtered.sort((a, b) -> Integer.compare(
                    wordCounts.getOrDefault(b.id(), 0), wordCounts.getOrDefault(a.id(), 0)));
            default -> filtered.sort((a, b) -> a.word().compareToIgnoreCase(b.word()));
        }

        int limit = perPageBox == null || perPageBox.getValue() == null ? 10 : perPageBox.getValue();
        int total = filtered.size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) limit));
        page = Math.min(Math.max(0, page), pages - 1);
        int from = page * limit;
        int to = Math.min(from + limit, total);
        List<CategoryDto> visible = from < to ? filtered.subList(from, to) : List.of();
        rows.setAll(visible);
        countLabel.setText(total + (total == 1 ? " category" : " categories"));
        pageLabel.setText(total == 0 ? "" : "Page " + (page + 1) + " of " + pages);

        boolean showGrid = displayBox == null || "Grid".equals(displayBox.getValue());
        gridScroll.setVisible(showGrid);
        gridScroll.setManaged(showGrid);
        table.setVisible(!showGrid);
        table.setManaged(!showGrid);
        if (showGrid) {
            buildGrid(visible);
        }
        buildPageBar(pages);
    }

    private void buildGrid(List<CategoryDto> visible) {
        grid.getChildren().clear();
        if (visible.isEmpty()) {
            grid.getChildren().add(Fas.emptyState("No categories yet."));
            return;
        }
        for (CategoryDto c : visible) {
            grid.getChildren().add(categoryCard(c));
        }
    }

    private VBox categoryCard(CategoryDto c) {
        javafx.scene.layout.StackPane chip = new javafx.scene.layout.StackPane(
                Icons.box(Icons.TAGS, Fas.PRIMARY, 16));
        chip.getStyleClass().add("stat-icon");
        chip.setStyle("-fx-background-color: " + Fas.PRIMARY + "1f;");
        chip.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        Label name = new Label(c.word());
        name.getStyleClass().add("entity-card-title");
        name.setWrapText(true);
        int files = fileCounts.getOrDefault(c.id(), 0);
        int wcount = wordCounts.getOrDefault(c.id(), 0);
        Label meta = new Label(files + (files == 1 ? " File" : " Files")
                + "  •  " + wcount + " Words");
        meta.getStyleClass().add("entity-card-meta");

        Button open = Fas.ghost("Open", Icons.EYE);
        open.setOnAction(e -> router.openCategoryDetail(c.id()));

        VBox card = new VBox(8, chip, name, meta,
                Fas.row(4, open, Fas.spacer(), Fas.badge(files + "", "info")));
        card.getStyleClass().add("entity-card");
        card.setOnMouseClicked(e -> {
            table.getSelectionModel().select(c);
            loadWords(c);
            if (e.getClickCount() == 2) {
                router.openCategoryDetail(c.id());
            }
        });
        return card;
    }

    private void buildPageBar(int pages) {
        pageBar.getChildren().clear();
        if (pages <= 1) {
            return;
        }
        Button first = Fas.pageButton("First");
        first.setDisable(page == 0);
        first.setOnAction(e -> {
            page = 0;
            applyFilter();
        });
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
        Button last = Fas.pageButton("Last");
        last.setDisable(page >= pages - 1);
        last.setOnAction(e -> {
            page = pages - 1;
            applyFilter();
        });
        pageBar.getChildren().addAll(first, prev);
        int start = Math.max(0, Math.min(page - 1, pages - 3));
        int end = Math.min(pages, start + 3);
        for (int i = start; i < end; i++) {
            final int p = i;
            Button b = Fas.pageButton(String.valueOf(i + 1));
            if (i == page) {
                b.getStyleClass().add("current");
            }
            b.setOnAction(e -> {
                page = p;
                applyFilter();
            });
            pageBar.getChildren().add(b);
        }
        if (end < pages) {
            pageBar.getChildren().add(Fas.muted("…"));
            Button tail = Fas.pageButton(String.valueOf(pages));
            final int p = pages - 1;
            tail.setOnAction(e -> {
                page = p;
                applyFilter();
            });
            pageBar.getChildren().add(tail);
        }
        pageBar.getChildren().addAll(next, last);
    }

    private void mergeDuplicates() {
        java.util.Map<String, Integer> d = facades.categories().findDuplicates();
        if (d.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION, "Every category is unique.",
                    ButtonType.OK);
            a.setHeaderText("No duplicates found");
            a.showAndWait();
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                d.entrySet().stream().map(x -> x.getKey() + "  \u00d7" + x.getValue())
                        .reduce((x, y) -> x + "\n" + y).orElse("")
                        + "\n\nMerge each group into its oldest category? Words, keywords and"
                        + " file attributions move across.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText(d.size() + " duplicated categor" + (d.size() == 1 ? "y" : "ies"));
        a.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            int removed = facades.categories().mergeDuplicates();
            onShow();
            Alert done = new Alert(Alert.AlertType.INFORMATION,
                    removed + " duplicate categor" + (removed == 1 ? "y" : "ies") + " merged.",
                    ButtonType.OK);
            done.setHeaderText("Merge complete");
            done.showAndWait();
        });
    }

    private static void err(String m) {
        Alert a = new Alert(Alert.AlertType.ERROR, m, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }
}
