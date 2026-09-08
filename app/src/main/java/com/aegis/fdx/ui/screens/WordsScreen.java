package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.WordDto;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Python parity: {@code templates/Word/Word_list.html} + {@code Word_detail.html}
 * and the {@code /api/words} routes, including pagination, search and bulk delete.
 */
public final class WordsScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;
    private final ObservableList<WordDto> rows = FXCollections.observableArrayList();
    private final java.util.Map<Integer, Integer> fileCounts = new java.util.HashMap<>();
    private TableView<WordDto> table;
    private TextField searchField;
    private Label pageLabel;
    private ComboBox<Integer> perPage;
    private int offset;
    private int total;

    public WordsScreen(AegisFacades facades) {
        this(facades, null);
    }

    public WordsScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Words";
    }

    @Override
    public String icon() {
        return Icons.BOOK;
    }

    @Override
    public Node build() {
        searchField = Fas.field("Search words...");
        searchField.setPrefWidth(230);
        searchField.textProperty().addListener((o, a, b) -> {
            offset = 0;
            onShow();
        });

        Button add = Fas.primary("Add Word", Icons.PLUS);
        add.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Add Word");
            d.setHeaderText("Create a new word");
            d.setContentText("Word:");
            d.showAndWait().ifPresent(w -> {
                try {
                    facades.words().createWord(w);
                    onShow();
                } catch (FacadeException ex) {
                    err(ex.getMessage());
                }
            });
        });

        Button bulkDelete = Fas.danger("Delete Selected", Icons.TRASH);
        bulkDelete.setOnAction(e -> {
            List<WordDto> sel = List.copyOf(table.getSelectionModel().getSelectedItems());
            if (sel.isEmpty()) {
                return;
            }
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + sel.size() + " word(s)? This cannot be undone.",
                    ButtonType.CANCEL, ButtonType.OK);
            a.setHeaderText("Delete Confirmation");
            a.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    facades.words().bulkDeleteWords(sel.stream().map(WordDto::id).toList());
                    onShow();
                }
            });
        });

        perPage = new ComboBox<>(FXCollections.observableArrayList(25, 50, 100, 200));
        perPage.setValue(50);
        perPage.setOnAction(e -> {
            offset = 0;
            onShow();
        });

        table = new TableView<>(rows);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(Fas.emptyState("No words yet."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<WordDto, String> cId = new TableColumn<>("ID");
        cId.setPrefWidth(80);
        cId.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().id())));

        TableColumn<WordDto, String> cWord = new TableColumn<>("Word");
        cWord.setPrefWidth(320);
        cWord.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().word()));

        TableColumn<WordDto, String> cFiles = new TableColumn<>("Files");
        cFiles.setPrefWidth(80);
        cFiles.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(fileCounts.getOrDefault(c.getValue().id(), 0))));

        TableColumn<WordDto, WordDto> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(170);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(WordDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button view = Fas.ghost("", Icons.EYE);
                view.setOnAction(e -> router.openWord(item.id()));
                Button edit = Fas.ghost("", Icons.PENCIL);
                edit.setOnAction(e -> {
                    TextInputDialog d = new TextInputDialog(item.word());
                    d.setTitle("Edit Word");
                    d.setHeaderText("Rename word");
                    d.setContentText("Word:");
                    d.showAndWait().ifPresent(w -> {
                        facades.words().updateWord(item.id(), w);
                        onShow();
                    });
                });
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> {
                    facades.words().deleteWord(item.id());
                    onShow();
                });
                setGraphic(Fas.row(2, view, edit, del));
            }
        });

        table.getColumns().addAll(cId, cWord, cFiles, cAct);
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<WordDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openWord(row.getItem().id());
                }
            });
            return row;
        });

        pageLabel = Fas.muted("0 - 0 of 0");
        Button prev = Fas.outline("Previous", null);
        prev.setOnAction(e -> {
            offset = Math.max(0, offset - perPage.getValue());
            onShow();
        });
        Button next = Fas.outline("Next", null);
        next.setOnAction(e -> {
            if (offset + perPage.getValue() < total) {
                offset += perPage.getValue();
                onShow();
            }
        });

        VBox body = new VBox(10, table,
                Fas.row(8, Fas.muted("Per Page:"), perPage, Fas.spacer(),
                        pageLabel, prev, next));

        Button export = Fas.outline("Export CSV", Icons.DOWNLOAD);
        export.setOnAction(e -> Fas.saveBytes(table, "Export Category Words", "category-words.csv",
                com.aegis.fdx.facade.ExportFacade.exportTermsCsv(
                        facades.relationships().categoryWords(null, 100_000, 0).results())));

        VBox content = new VBox(16,
                Fas.pageHeader("Words", "Home / Words", searchField, export, bulkDelete, add),
                Fas.cardWithHeader("Word List", null, body));
        content.setPadding(new Insets(20));
        return content;
    }

    @Override
    public void onShow() {
        try {
            int limit = perPage.getValue();
            String q = searchField.getText();
            Page<WordDto> page = facades.words().searchWords(
                    q == null || q.isBlank() ? null : q, limit, offset);
            // whole-case counts from path_word; a word outside any category has none
            fileCounts.clear();
            fileCounts.putAll(facades.relationships().wordFileCounts());
            rows.setAll(page.results());
            total = page.totalCount();
            int from = total == 0 ? 0 : offset + 1;
            int to = Math.min(offset + limit, total);
            pageLabel.setText(from + " - " + to + " of " + String.format("%,d", total));
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
