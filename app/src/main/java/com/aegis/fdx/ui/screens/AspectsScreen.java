package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.AspectDto;
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
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sides (Aspects): the parties or groupings that ingested material is attributed to.
 *
 * <p>Management view with summary tiles (Total / Filtered / Visible / Page), search,
 * importance sorting, grid and list display formats, paging and bulk operations.
 * An aspect plus a source forms the mandatory attribution recorded against
 * processed files.
 */
public final class AspectsScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private List<AspectDto> all = List.of();
    private final Set<Integer> selected = new HashSet<>();
    private final ObservableList<AspectDto> rows = FXCollections.observableArrayList();

    private VBox tiles;
    private VBox tileTotal;
    private VBox tileFiltered;
    private VBox tileVisible;
    private VBox tilePage;

    private TextField searchField;
    private ComboBox<String> sortBox;
    private ComboBox<String> displayBox;
    private ComboBox<Integer> perPageBox;

    private TableView<AspectDto> table;
    private FlowPane grid;
    private StackPane views;
    private ScrollPane gridScroll;
    private HBox pageBar;
    private Label pageLabel;
    private int page;

    public AspectsScreen(AegisFacades facades) {
        this(facades, null);
    }

    public AspectsScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Aspects";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Sides";
    }

    @Override
    public String icon() {
        return Icons.DIAGRAM3;
    }

    @Override
    public Node build() {
        tileTotal = Fas.summaryTile("0", "Total Sides");
        tileFiltered = Fas.summaryTile("0", "Filtered Sides");
        tileVisible = Fas.summaryTile("0", "Visible Sides");
        tilePage = Fas.summaryTile("1 / 1", "Page");
        tiles = new VBox(Fas.statsGrid(tileTotal, tileFiltered, tileVisible, tilePage));

        searchField = Fas.field("Search Sides...");
        searchField.setPrefWidth(220);
        searchField.textProperty().addListener((o, a, b) -> {
            page = 0;
            applyFilter();
        });

        sortBox = new ComboBox<>(FXCollections.observableArrayList(
                "Importance (High-Low)", "Importance (Low-High)",
                "Name (A-Z)", "Name (Z-A)", "Newest", "Oldest"));
        sortBox.setValue("Importance (High-Low)");
        sortBox.setPrefWidth(180);
        sortBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });

        displayBox = new ComboBox<>(FXCollections.observableArrayList("Grid", "List"));
        displayBox.setValue("Grid");
        displayBox.setPrefWidth(100);
        displayBox.setOnAction(e -> applyFilter());

        perPageBox = new ComboBox<>(FXCollections.observableArrayList(10, 25, 50, 100));
        perPageBox.setValue(50);
        perPageBox.setPrefWidth(90);
        perPageBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });

        Button add = Fas.primary("Add Side", Icons.PLUS);
        add.setOnAction(e -> openForm(null));

        Button selectAll = Fas.ghost("Select All", Icons.CHECK_ALL);
        selectAll.setOnAction(e -> {
            for (AspectDto a : rows) {
                selected.add(a.id());
            }
            applyFilter();
        });
        Button selectNone = Fas.ghost("Select None", Icons.CLOSE);
        selectNone.setOnAction(e -> {
            selected.clear();
            applyFilter();
        });
        Button exportSel = Fas.outline("Export Selected", Icons.DOWNLOAD);
        exportSel.setOnAction(e -> exportSelected());
        Button editSel = Fas.outline("Edit Selected", Icons.PENCIL);
        editSel.setOnAction(e -> {
            List<AspectDto> sel = selectedAspects();
            if (sel.size() == 1) {
                openForm(sel.get(0));
            } else if (sel.isEmpty()) {
                err("Select a side first.");
            } else {
                err("Select a single side to edit. Bulk edit keeps one record at a time "
                        + "so nothing is overwritten by accident.");
            }
        });
        Button delSel = Fas.danger("Delete Selected", Icons.TRASH);
        delSel.setOnAction(e -> deleteSelected());
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        VBox filterBar = new VBox(10,
                Fas.row(10,
                        Fas.formField("Search Sides", searchField),
                        Fas.formField("Sort By", sortBox),
                        Fas.formField("Display Format", displayBox),
                        Fas.formField("Per Page", perPageBox)),
                Fas.row(8, selectAll, selectNone, exportSel, editSel, delSel,
                        Fas.spacer(), refresh, add));
        filterBar.getStyleClass().add("filter-bar");

        table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

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
        pageBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox content = new VBox(16,
                Fas.pageHeader("Aspects", breadcrumb()),
                tiles, filterBar,
                Fas.cardWithHeader("All Sides", "Double-click to open the side detail",
                        new VBox(10, views, Fas.row(8, pageLabel, Fas.spacer(), pageBar))));
        content.setPadding(new Insets(20));
        return content;
    }

    private TableView<AspectDto> buildTable() {
        TableView<AspectDto> t = new TableView<>(rows);
        t.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        t.setPlaceholder(Fas.emptyState("No sides yet. Add your first side"));
        t.setPrefHeight(420);

        TableColumn<AspectDto, AspectDto> cSel = new TableColumn<>("✓");
        cSel.setPrefWidth(45);
        cSel.setSortable(false);
        cSel.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cSel.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(AspectDto item, boolean empty) {
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
                    applyFilter();
                });
                setGraphic(cb);
            }
        });

        TableColumn<AspectDto, String> cId = new TableColumn<>("ID");
        cId.setPrefWidth(60);
        cId.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().id())));
        TableColumn<AspectDto, String> cName = new TableColumn<>("Name");
        cName.setPrefWidth(240);
        cName.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        TableColumn<AspectDto, String> cImp = new TableColumn<>("Importance");
        cImp.setPrefWidth(110);
        cImp.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.format("%.2f", c.getValue().importance())));
        TableColumn<AspectDto, String> cDate = new TableColumn<>("Date of Creation");
        cDate.setPrefWidth(140);
        cDate.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().dateCreation() == null ? "" : c.getValue().dateCreation().toString()));
        TableColumn<AspectDto, AspectDto> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(170);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(AspectDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button view = Fas.ghost("", Icons.EYE);
                view.setOnAction(e -> router.openAspect(item.id()));
                Button edit = Fas.ghost("", Icons.PENCIL);
                edit.setOnAction(e -> openForm(item));
                Button dup = Fas.ghost("", Icons.FILES);
                dup.setOnAction(e -> {
                    facades.aspects().duplicateAspect(item.id());
                    onShow();
                });
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> confirmDelete(item));
                setGraphic(Fas.row(2, view, edit, dup, del));
            }
        });
        t.getColumns().addAll(cSel, cId, cName, cImp, cDate, cAct);
        t.setRowFactory(tv -> {
            javafx.scene.control.TableRow<AspectDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openAspect(row.getItem().id());
                }
            });
            return row;
        });
        return t;
    }

    @Override
    public void onShow() {
        try {
            all = List.copyOf(facades.aspects().listAspects());
            selected.retainAll(all.stream().map(AspectDto::id).toList());
            page = 0;
            applyFilter();
        } catch (RuntimeException e) {
            all = List.of();
            rows.clear();
            applyFilter();
        }
    }

    private void applyFilter() {
        String q = searchField == null || searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase();
        List<AspectDto> filtered = new ArrayList<>();
        for (AspectDto a : all) {
            if (q.isEmpty() || (a.name() != null && a.name().toLowerCase().contains(q))) {
                filtered.add(a);
            }
        }
        String sort = sortBox == null ? "Importance (High-Low)" : sortBox.getValue();
        switch (sort) {
            case "Importance (Low-High)" -> filtered.sort((a, b) ->
                    Double.compare(a.importance(), b.importance()));
            case "Name (A-Z)" -> filtered.sort((a, b) ->
                    a.name().compareToIgnoreCase(b.name()));
            case "Name (Z-A)" -> filtered.sort((a, b) ->
                    b.name().compareToIgnoreCase(a.name()));
            case "Newest" -> filtered.sort((a, b) -> Integer.compare(b.id(), a.id()));
            case "Oldest" -> filtered.sort((a, b) -> Integer.compare(a.id(), b.id()));
            default -> filtered.sort((a, b) ->
                    Double.compare(b.importance(), a.importance()));
        }

        int limit = perPageBox == null || perPageBox.getValue() == null
                ? 50 : perPageBox.getValue();
        int total = filtered.size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) limit));
        page = Math.min(Math.max(0, page), pages - 1);
        int from = page * limit;
        int to = Math.min(from + limit, total);
        List<AspectDto> visible = from < to ? filtered.subList(from, to) : List.of();
        rows.setAll(visible);

        Fas.setSummary(tileTotal, String.valueOf(all.size()));
        Fas.setSummary(tileFiltered, String.valueOf(filtered.size()));
        Fas.setSummary(tileVisible, String.valueOf(visible.size()));
        Fas.setSummary(tilePage, (page + 1) + " / " + pages);
        pageLabel.setText(total == 0 ? "No sides yet. Add your first side"
                : "Showing " + (total == 0 ? 0 : from + 1) + " - " + to + " of " + total);

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

    private void buildGrid(List<AspectDto> visible) {
        grid.getChildren().clear();
        if (visible.isEmpty()) {
            grid.getChildren().add(Fas.emptyState("No sides yet. Add your first side"));
            return;
        }
        for (AspectDto a : visible) {
            grid.getChildren().add(aspectCard(a));
        }
    }

    private VBox aspectCard(AspectDto a) {
        StackPane chip = new StackPane(Icons.box(Icons.DIAGRAM3, "#8b5cf6", 16));
        chip.getStyleClass().add("stat-icon");
        chip.setStyle("-fx-background-color: #8b5cf61f;");
        chip.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        CheckBox cb = new CheckBox();
        cb.setSelected(selected.contains(a.id()));
        cb.setOnAction(e -> {
            if (cb.isSelected()) {
                selected.add(a.id());
            } else {
                selected.remove(a.id());
            }
        });

        Label name = new Label(a.name());
        name.getStyleClass().add("entity-card-title");
        name.setWrapText(true);
        Label meta = new Label(String.format("Importance %.2f", a.importance())
                + (a.dateCreation() == null ? "" : "  •  " + a.dateCreation()));
        meta.getStyleClass().add("entity-card-meta");

        Button open = Fas.ghost("Open", Icons.EYE);
        open.setOnAction(e -> router.openAspect(a.id()));
        Button edit = Fas.ghost("", Icons.PENCIL);
        edit.setOnAction(e -> openForm(a));
        Button del = Fas.ghost("", Icons.TRASH);
        del.setOnAction(e -> confirmDelete(a));

        VBox card = new VBox(8, Fas.row(8, chip, Fas.spacer(), cb), name, meta,
                Fas.row(4, open, Fas.spacer(), edit, del));
        card.getStyleClass().add("entity-card");
        if (selected.contains(a.id())) {
            card.getStyleClass().add("selected");
        }
        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                router.openAspect(a.id());
            }
        });
        return card;
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

    private List<AspectDto> selectedAspects() {
        List<AspectDto> out = new ArrayList<>();
        for (AspectDto a : all) {
            if (selected.contains(a.id())) {
                out.add(a);
            }
        }
        return out;
    }

    private void exportSelected() {
        List<AspectDto> sel = selectedAspects();
        if (sel.isEmpty()) {
            err("Select at least one side to export.");
            return;
        }
        StringBuilder sb = new StringBuilder("\uFEFFid,name,importance,date_creation\n");
        for (AspectDto a : sel) {
            sb.append(a.id()).append(",\"")
                    .append(a.name().replace("\"", "\"\"")).append("\",")
                    .append(String.format("%.4f", a.importance())).append(",")
                    .append(a.dateCreation() == null ? "" : a.dateCreation()).append("\n");
        }
        Fas.saveBytes(table, "Export Sides", "sides.csv",
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void deleteSelected() {
        List<AspectDto> sel = selectedAspects();
        if (sel.isEmpty()) {
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + sel.size() + " side(s)? This cannot be undone.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete Confirmation");
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                for (AspectDto s : sel) {
                    try {
                        facades.aspects().deleteAspect(s.id());
                    } catch (RuntimeException ignored) {
                        // best effort; the refresh below shows what remains
                    }
                }
                selected.clear();
                onShow();
            }
        });
    }

    private void openForm(AspectDto existing) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(existing == null ? "Add Side" : "Edit Side");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField name = Fas.field("Side name");
        TextField importance = Fas.field("0.0 - 1.0");
        DatePicker date = new DatePicker(LocalDate.now());
        if (existing == null) {
            importance.setText("0.50");
        } else {
            name.setText(existing.name());
            importance.setText(String.format("%.2f", existing.importance()));
            if (existing.dateCreation() != null) {
                date.setValue(existing.dateCreation());
            }
        }

        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(10);
        g.setPadding(new Insets(16));
        g.add(Fas.formField("Name *", name), 0, 0);
        g.add(Fas.formField("Importance *", importance), 0, 1);
        g.add(Fas.formField("Date of Creation", date), 0, 2);
        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().setPrefWidth(420);

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) {
                return;
            }
            try {
                double imp = Double.parseDouble(importance.getText().trim());
                if (existing == null) {
                    facades.aspects().createAspect(name.getText(), imp, date.getValue());
                } else {
                    facades.aspects().updateAspect(existing.id(), name.getText(), imp, date.getValue());
                }
                onShow();
            } catch (NumberFormatException ex) {
                err("Importance must be a number between 0.0 and 1.0");
            } catch (FacadeException ex) {
                err(ex.getMessage());
            }
        });
    }

    private void confirmDelete(AspectDto item) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete side \u201c" + item.name() + "\u201d? This cannot be undone.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete Confirmation");
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                facades.aspects().deleteAspect(item.id());
                selected.remove(item.id());
                onShow();
            }
        });
    }

    private static void err(String m) {
        Alert a = new Alert(Alert.AlertType.ERROR, m, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }
}
