package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.KeywordDto;
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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * Python parity: {@code templates/Keyword/keywords_list.html} +
 * {@code keyword_detail.html} and the {@code /api/keywords} routes, including
 * bulk delete and duplicate detection.
 */
public final class KeywordsScreen implements Screen {

    private final AegisFacades facades;
    private final AgentService agent;
    private final Router router;
    private final ObservableList<KeywordDto> rows = FXCollections.observableArrayList();
    private final java.util.Map<Integer, Integer> fileCounts = new java.util.HashMap<>();
    private TableView<KeywordDto> table;
    private Label countLabel;
    private ComboBox<Integer> perPage;
    private Label pageLabel;
    private int offset;
    private int total;

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
    public String icon() {
        return Icons.KEY;
    }

    @Override
    public Node build() {
        Button add = Fas.primary("Add Keyword", Icons.PLUS);
        add.setOnAction(e -> openForm());

        Button dupes = Fas.outline("Find Duplicates", Icons.FUNNEL);
        dupes.setOnAction(e -> {
            Map<String, Integer> d = facades.keywords().findDuplicates();
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setHeaderText(d.isEmpty() ? "No duplicates found"
                    : d.size() + " duplicated keyword phrase(s)");
            a.setContentText(d.isEmpty() ? "Every keyword phrase is unique."
                    : d.entrySet().stream()
                    .map(x -> x.getKey() + "  \u00d7" + x.getValue())
                    .reduce((x, y) -> x + "\n" + y).orElse(""));
            a.showAndWait();
        });

        Button bulk = Fas.danger("Delete Selected", Icons.TRASH);
        bulk.setOnAction(e -> {
            List<KeywordDto> sel = List.copyOf(table.getSelectionModel().getSelectedItems());
            if (sel.isEmpty()) {
                return;
            }
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + sel.size() + " keyword(s)?", ButtonType.CANCEL, ButtonType.OK);
            a.setHeaderText("Delete Confirmation");
            a.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    facades.keywords().bulkDeleteKeywords(
                            sel.stream().map(KeywordDto::id).toList());
                    onShow();
                }
            });
        });

        Button analyze = AnalyzeAction.button(agent, "Analyze Keywords",
                "Which keywords occur most across the case, and in which material?",
                () -> {
                    KeywordDto sel = table.getSelectionModel().getSelectedItem();
                    AgentContext c = AgentContext.ofScreen("Keywords");
                    return sel == null ? c : c.withKeyword(sel.id());
                });

        perPage = new ComboBox<>(FXCollections.observableArrayList(25, 50, 100));
        perPage.setValue(50);
        perPage.setOnAction(e -> {
            offset = 0;
            onShow();
        });

        countLabel = Fas.muted("0 keywords");
        table = new TableView<>(rows);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(Fas.emptyState("No keywords yet."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<KeywordDto, String> cId = new TableColumn<>("ID");
        cId.setPrefWidth(70);
        cId.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().id())));

        TableColumn<KeywordDto, String> cKw = new TableColumn<>("Keyword");
        cKw.setPrefWidth(300);
        cKw.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().keyword()));

        TableColumn<KeywordDto, String> cCat = new TableColumn<>("Category");
        cCat.setPrefWidth(180);
        cCat.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().categoryWord()));

        TableColumn<KeywordDto, String> cFiles = new TableColumn<>("Files");
        cFiles.setPrefWidth(80);
        cFiles.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(fileCounts.getOrDefault(c.getValue().id(), 0))));

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
                edit.setOnAction(e -> {
                    TextInputDialog d = new TextInputDialog(item.keyword());
                    d.setTitle("Edit Keyword");
                    d.setHeaderText("Rename keyword phrase");
                    d.setContentText("Keyword:");
                    d.showAndWait().ifPresent(k -> {
                        facades.keywords().updateKeyword(item.id(), k);
                        onShow();
                    });
                });
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> {
                    facades.keywords().deleteKeyword(item.id());
                    onShow();
                });
                setGraphic(Fas.row(2, view, edit, del));
            }
        });

        table.getColumns().addAll(cId, cKw, cCat, cFiles, cAct);
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<KeywordDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openKeyword(row.getItem().id());
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

        VBox body = new VBox(10, countLabel, table,
                Fas.row(8, Fas.muted("Per Page:"), perPage, Fas.spacer(),
                        pageLabel, prev, next));

        VBox content = new VBox(16,
                Fas.pageHeader("Keywords", "Home / Keywords", analyze, dupes, bulk, add),
                Fas.cardWithHeader("Keyword List", null, body));
        content.setPadding(new Insets(20));
        return content;
    }

    private void openForm() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Add Keyword");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField phrase = Fas.field("Keyword phrase");
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

    @Override
    public void onShow() {
        try {
            int limit = perPage.getValue();
            var page = facades.keywords().listKeywords(limit, offset);
            // whole-case counts, computed from path_keyword — not from this page
            fileCounts.clear();
            fileCounts.putAll(facades.relationships().keywordFileCounts());
            rows.setAll(page.results());
            total = page.totalCount();
            countLabel.setText(total + (total == 1 ? " keyword" : " keywords"));
            int from = total == 0 ? 0 : offset + 1;
            pageLabel.setText(from + " - " + Math.min(offset + limit, total)
                    + " of " + String.format("%,d", total));
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
