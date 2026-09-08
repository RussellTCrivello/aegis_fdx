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
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Python parity: {@code templates/Category/categories_list.html} +
 * {@code category_words.html} and the {@code /api/categories} routes.
 *
 * <p>Two-pane layout matching Python: the category list on the left, and the words
 * linked to the selected category on the right ({@code category_words} page).
 */
public final class CategoriesScreen implements Screen {

    private final AegisFacades facades;
    private final AgentService agent;
    private final ObservableList<CategoryDto> rows = FXCollections.observableArrayList();
    private final ObservableList<String> words = FXCollections.observableArrayList();
    private TableView<CategoryDto> table;
    private Label countLabel;
    private Label wordsHeader;

    public CategoriesScreen(AegisFacades facades, AgentService agent) {
        this.facades = facades;
        this.agent = agent;
    }

    @Override
    public String title() {
        return "Categories";
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

        countLabel = Fas.muted("0 categories");
        table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState("No categories yet."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<CategoryDto, String> cId = new TableColumn<>("ID");
        cId.setPrefWidth(70);
        cId.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().id())));

        TableColumn<CategoryDto, String> cWord = new TableColumn<>("Category");
        cWord.setPrefWidth(220);
        cWord.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().word()));

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
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> {
                    facades.categories().deleteCategory(item.id());
                    onShow();
                });
                setGraphic(del);
            }
        });

        table.getColumns().addAll(cId, cWord, cAct);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> loadWords(b));

        // right pane — Python category_words.html
        wordsHeader = Fas.muted("Select a category to see its words");
        ListView<String> wordList = new ListView<>(words);
        wordList.setPlaceholder(Fas.emptyState("No words linked."));
        VBox.setVgrow(wordList, Priority.ALWAYS);

        Button link = Fas.secondary("Link Word", Icons.LINK);
        link.setOnAction(e -> {
            CategoryDto sel = table.getSelectionModel().getSelectedItem();
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

        VBox left = Fas.cardWithHeader("Categories", null, new VBox(10, countLabel, table));
        VBox right = Fas.cardWithHeader("Category Words", null,
                new VBox(10, Fas.row(8, wordsHeader, Fas.spacer(), link), wordList));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox split = new HBox(14, left, right);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader("Categories", "Home / Categories", analyze, refresh, add), split);
        content.setPadding(new Insets(20));
        return content;
    }

    private void loadWords(CategoryDto cat) {
        if (cat == null) {
            words.clear();
            wordsHeader.setText("Select a category to see its words");
            return;
        }
        try {
            var page = facades.categories().getCategoryWords(cat.id(), 500, 0);
            words.setAll(page.results().stream().map(WordDto::word).toList());
            wordsHeader.setText(cat.word() + " \u2014 " + words.size() + " word(s)");
        } catch (RuntimeException e) {
            words.clear();
        }
    }

    /**
     * Selects a category by id, so another destination can drill through to it.
     *
     * <p>Reloads first, because the category may have been created since this screen
     * was last shown.
     */
    public void selectCategory(int categoryId) {
        onShow();
        for (CategoryDto c : rows) {
            if (c.id() == categoryId) {
                table.getSelectionModel().select(c);
                table.scrollTo(c);
                loadWords(c);
                return;
            }
        }
    }

    @Override
    public void onShow() {
        try {
            var page = facades.categories().listCategories(500, 0);
            rows.setAll(page.results());
            countLabel.setText(page.totalCount()
                    + (page.totalCount() == 1 ? " category" : " categories"));
        } catch (RuntimeException e) {
            rows.clear();
        }
    }

    private static void err(String m) {
        Alert a = new Alert(Alert.AlertType.ERROR, m, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }
}
