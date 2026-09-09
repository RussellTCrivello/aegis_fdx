package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.SourceDto;
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
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
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
 * Sources: the people, organisations and systems material originates from.
 *
 * <p>Management view with summary tiles (Total / Filtered / Visible / Page), search,
 * sorting, grid and list display formats, paging and bulk operations. Creating and
 * editing share the one {@link SourceForm} definition with the Source detail
 * destination, so the two can never drift apart.
 */
public final class SourcesScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private List<SourceDto> all = List.of();
    private Map<String, Integer> fileCounts = Map.of();
    private final Set<Integer> selected = new HashSet<>();
    private final ObservableList<SourceDto> rows = FXCollections.observableArrayList();

    private VBox tileTotal;
    private VBox tileFiltered;
    private VBox tileVisible;
    private VBox tilePage;

    private TextField searchField;
    private ComboBox<String> sortBox;
    private ComboBox<String> displayBox;
    private ComboBox<Integer> perPageBox;

    private TableView<SourceDto> table;
    private FlowPane grid;
    private ScrollPane gridScroll;
    private Label pageLabel;
    private HBox pageBar;
    private int page;

    public SourcesScreen(AegisFacades facades) {
        this(facades, null);
    }

    public SourcesScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Sources";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Sources";
    }

    @Override
    public String icon() {
        return Icons.BUILDING;
    }

    @Override
    public Node build() {
        tileTotal = Fas.summaryTile("0", "Total Sources");
        tileFiltered = Fas.summaryTile("0", "Filtered Sources");
        tileVisible = Fas.summaryTile("0", "Visible Sources");
        tilePage = Fas.summaryTile("1 / 1", "Page");
        VBox tiles = new VBox(Fas.statsGrid(tileTotal, tileFiltered, tileVisible, tilePage));

        searchField = Fas.field("Search Sources...");
        searchField.setPrefWidth(220);
        searchField.textProperty().addListener((o, a, b) -> {
            page = 0;
            applyFilter();
        });

        sortBox = new ComboBox<>(FXCollections.observableArrayList(
                "Importance (High-Low)", "Importance (Low-High)",
                "Name (A-Z)", "Name (Z-A)", "Country (A-Z)",
                "Most Files", "Newest", "Oldest"));
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

        Button add = Fas.primary("Add Source", Icons.PLUS);
        add.setOnAction(e -> createSource());

        Button selectAll = Fas.ghost("Select All", Icons.CHECK_ALL);
        selectAll.setOnAction(e -> {
            for (SourceDto s : rows) {
                selected.add(s.id());
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
            List<SourceDto> sel = selectedSources();
            if (sel.size() == 1) {
                editSource(sel.get(0));
            } else if (sel.isEmpty()) {
                err("Select a source first.");
            } else {
                err("Select a single source to edit. Bulk edit keeps one record at a time "
                        + "so nothing is overwritten by accident.");
            }
        });
        Button delSel = Fas.danger("Delete Selected", Icons.TRASH);
        delSel.setOnAction(e -> deleteSelected());
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        VBox filterBar = new VBox(10,
                Fas.row(10,
                        Fas.formField("Search Sources", searchField),
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

        StackPane views = new StackPane(gridScroll, table);
        VBox.setVgrow(views, Priority.ALWAYS);

        pageLabel = Fas.muted("");
        pageBar = new HBox(6);
        pageBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox content = new VBox(16,
                Fas.pageHeader("Sources", breadcrumb()),
                tiles, filterBar,
                Fas.cardWithHeader("All Sources", "Double-click to open the source detail",
                        new VBox(10, views, Fas.row(8, pageLabel, Fas.spacer(), pageBar))));
        content.setPadding(new Insets(20));
        return content;
    }

    private TableView<SourceDto> buildTable() {
        TableView<SourceDto> t = new TableView<>(rows);
        t.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        t.setPlaceholder(Fas.emptyState("No sources yet. Add your first source"));
        t.setPrefHeight(420);

        TableColumn<SourceDto, SourceDto> cSel = new TableColumn<>("✓");
        cSel.setPrefWidth(45);
        cSel.setSortable(false);
        cSel.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cSel.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(SourceDto item, boolean empty) {
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

        TableColumn<SourceDto, String> cId = strCol("ID", 60, s -> String.valueOf(s.id()));
        TableColumn<SourceDto, String> cName = strCol("Source Name", 180, SourceDto::name);
        TableColumn<SourceDto, String> cJob = strCol("Job/Type", 120, SourceDto::job);

        TableColumn<SourceDto, SourceDto> cImp = new TableColumn<>("Importance");
        cImp.setPrefWidth(130);
        cImp.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cImp.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(SourceDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                double v = Math.min(1, Math.max(0, item.importance()));
                ProgressBar bar = new ProgressBar(v);
                bar.setPrefWidth(64);
                Label lab = new Label(String.format("%.2f", item.importance()));
                lab.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Fas.TEXT_LIGHT + ";");
                HBox box = new HBox(6, bar, lab);
                box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });

        TableColumn<SourceDto, String> cCountry = strCol("Country", 100, SourceDto::country);
        TableColumn<SourceDto, String> cCity = strCol("City", 100, SourceDto::city);
        TableColumn<SourceDto, String> cFiles = strCol("Files", 70, s -> String.valueOf(filesOf(s)));
        TableColumn<SourceDto, String> cAccess = strCol("Access Status", 110, SourceDto::accessStatus);
        TableColumn<SourceDto, String> cDate = strCol("Discovered", 100,
                s -> s.entryDate() == null ? "" : s.entryDate().toString());

        TableColumn<SourceDto, SourceDto> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(180);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(SourceDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button view = Fas.ghost("", Icons.EYE);
                view.setOnAction(e -> router.openSource(item.id()));
                Button edit = Fas.ghost("", Icons.PENCIL);
                edit.setOnAction(e -> editSource(item));
                Button dup = Fas.ghost("", Icons.FILES);
                dup.setOnAction(e -> duplicateSource(item));
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> confirmDelete(item));
                setGraphic(Fas.row(2, view, edit, dup, del));
            }
        });

        t.getColumns().addAll(List.of(
                cSel, cId, cName, cJob, cImp, cCountry, cCity, cFiles, cAccess, cDate, cAct));
        t.setRowFactory(tv -> {
            javafx.scene.control.TableRow<SourceDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openSource(row.getItem().id());
                }
            });
            return row;
        });
        return t;
    }

    private static TableColumn<SourceDto, String> strCol(String name, double width,
                                                         java.util.function.Function<SourceDto, String> f) {
        TableColumn<SourceDto, String> c = new TableColumn<>(name);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd ->
                new javafx.beans.property.SimpleStringProperty(nz(f.apply(cd.getValue()))));
        return c;
    }

    @Override
    public void onShow() {
        try {
            all = List.copyOf(facades.sources().listSources());
            try {
                fileCounts = facades.sources().filesPerSource();
            } catch (RuntimeException e) {
                fileCounts = Map.of();
            }
            selected.retainAll(all.stream().map(SourceDto::id).toList());
            page = 0;
            applyFilter();
        } catch (RuntimeException e) {
            all = List.of();
            fileCounts = Map.of();
            rows.clear();
            applyFilter();
        }
    }

    private int filesOf(SourceDto s) {
        return fileCounts.getOrDefault(nz(s.name()), 0);
    }

    private void applyFilter() {
        String q = searchField == null || searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase();
        List<SourceDto> filtered = new ArrayList<>();
        for (SourceDto s : all) {
            if (q.isEmpty()
                    || nz(s.name()).toLowerCase().contains(q)
                    || nz(s.job()).toLowerCase().contains(q)
                    || nz(s.country()).toLowerCase().contains(q)
                    || nz(s.city()).toLowerCase().contains(q)) {
                filtered.add(s);
            }
        }
        String sort = sortBox == null ? "Importance (High-Low)" : sortBox.getValue();
        switch (sort) {
            case "Importance (Low-High)" -> filtered.sort((a, b) ->
                    Double.compare(a.importance(), b.importance()));
            case "Name (A-Z)" -> filtered.sort((a, b) ->
                    nz(a.name()).compareToIgnoreCase(nz(b.name())));
            case "Name (Z-A)" -> filtered.sort((a, b) ->
                    nz(b.name()).compareToIgnoreCase(nz(a.name())));
            case "Country (A-Z)" -> filtered.sort((a, b) ->
                    nz(a.country()).compareToIgnoreCase(nz(b.country())));
            case "Most Files" -> filtered.sort((a, b) ->
                    Integer.compare(filesOf(b), filesOf(a)));
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
        List<SourceDto> visible = from < to ? filtered.subList(from, to) : List.of();
        rows.setAll(visible);

        Fas.setSummary(tileTotal, String.valueOf(all.size()));
        Fas.setSummary(tileFiltered, String.valueOf(filtered.size()));
        Fas.setSummary(tileVisible, String.valueOf(visible.size()));
        Fas.setSummary(tilePage, (page + 1) + " / " + pages);
        pageLabel.setText(total == 0 ? "No sources yet. Add your first source"
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

    private void buildGrid(List<SourceDto> visible) {
        grid.getChildren().clear();
        if (visible.isEmpty()) {
            grid.getChildren().add(Fas.emptyState("No sources yet. Add your first source"));
            return;
        }
        for (SourceDto s : visible) {
            grid.getChildren().add(sourceCard(s));
        }
    }

    private VBox sourceCard(SourceDto s) {
        StackPane chip = new StackPane(Icons.box(Icons.BUILDING, "#4f46e5", 16));
        chip.getStyleClass().add("stat-icon");
        chip.setStyle("-fx-background-color: #4f46e51f;");
        chip.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        CheckBox cb = new CheckBox();
        cb.setSelected(selected.contains(s.id()));
        cb.setOnAction(e -> {
            if (cb.isSelected()) {
                selected.add(s.id());
            } else {
                selected.remove(s.id());
            }
        });

        Label name = new Label(nz(s.name()));
        name.getStyleClass().add("entity-card-title");
        name.setWrapText(true);
        Label meta = new Label(metaLine(s));
        meta.getStyleClass().add("entity-card-meta");
        meta.setWrapText(true);

        Button open = Fas.ghost("Open", Icons.EYE);
        open.setOnAction(e -> router.openSource(s.id()));
        Button edit = Fas.ghost("", Icons.PENCIL);
        edit.setOnAction(e -> editSource(s));
        Button del = Fas.ghost("", Icons.TRASH);
        del.setOnAction(e -> confirmDelete(s));

        VBox card = new VBox(8, Fas.row(8, chip, Fas.spacer(), cb), name, meta,
                Fas.row(4, open, Fas.spacer(), edit, del));
        card.getStyleClass().add("entity-card");
        if (selected.contains(s.id())) {
            card.getStyleClass().add("selected");
        }
        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                router.openSource(s.id());
            }
        });
        return card;
    }

    private String metaLine(SourceDto s) {
        List<String> parts = new ArrayList<>();
        if (!nz(s.job()).isBlank()) {
            parts.add(s.job());
        }
        String loc = joinNonBlank(", ", s.country(), s.city());
        if (!loc.isBlank()) {
            parts.add(loc);
        }
        parts.add(String.format("Importance %.2f", s.importance()));
        int n = filesOf(s);
        parts.add(n + (n == 1 ? " file" : " files"));
        return String.join("  •  ", parts);
    }

    private static String joinNonBlank(String sep, String... values) {
        List<String> parts = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                parts.add(v.strip());
            }
        }
        return String.join(sep, parts);
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

    private List<SourceDto> selectedSources() {
        List<SourceDto> out = new ArrayList<>();
        for (SourceDto s : all) {
            if (selected.contains(s.id())) {
                out.add(s);
            }
        }
        return out;
    }

    private void exportSelected() {
        List<SourceDto> sel = selectedSources();
        if (sel.isEmpty()) {
            err("Select at least one source to export.");
            return;
        }
        StringBuilder sb = new StringBuilder(
                "\uFEFFid,name,job,importance,country,city,access_status,entry_date,files\n");
        for (SourceDto s : sel) {
            sb.append(s.id()).append(",")
                    .append(q(s.name())).append(",")
                    .append(q(s.job())).append(",")
                    .append(String.format("%.4f", s.importance())).append(",")
                    .append(q(s.country())).append(",")
                    .append(q(s.city())).append(",")
                    .append(q(s.accessStatus())).append(",")
                    .append(s.entryDate() == null ? "" : s.entryDate()).append(",")
                    .append(filesOf(s)).append("\n");
        }
        Fas.saveBytes(table, "Export Sources", "sources.csv",
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String q(String v) {
        return "\"" + nz(v).replace("\"", "\"\"") + "\"";
    }

    private void deleteSelected() {
        List<SourceDto> sel = selectedSources();
        if (sel.isEmpty()) {
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + sel.size() + " source(s)? Collected material keeps its files "
                        + "but loses this attribution.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete Confirmation");
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                for (SourceDto s : sel) {
                    try {
                        facades.sources().deleteSource(s.id());
                    } catch (RuntimeException ignored) {
                        // best effort; the refresh below shows what remains
                    }
                }
                selected.clear();
                onShow();
            }
        });
    }

    private void createSource() {
        SourceForm.showCreate(draft -> {
            try {
                facades.sources().createSource(draft);
                onShow();
            } catch (FacadeException ex) {
                err(ex.getMessage());
            }
        });
    }

    private void editSource(SourceDto existing) {
        SourceForm.showEdit(existing, draft -> {
            try {
                facades.sources().updateSource(existing.id(), draft);
                onShow();
            } catch (FacadeException ex) {
                err(ex.getMessage());
            }
        });
    }

    private void duplicateSource(SourceDto item) {
        try {
            facades.sources().duplicateSource(item.id());
            onShow();
        } catch (FacadeException ex) {
            err(ex.getMessage());
        }
    }

    private void confirmDelete(SourceDto item) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete source \u201c" + item.name() + "\u201d? Material collected from it "
                        + "keeps its files but loses this attribution.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete Confirmation");
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                try {
                    facades.sources().deleteSource(item.id());
                } catch (FacadeException ex) {
                    err(ex.getMessage());
                    return;
                }
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
