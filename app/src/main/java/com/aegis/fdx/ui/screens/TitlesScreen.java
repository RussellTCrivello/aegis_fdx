package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Titles: the distinct document titles the case knows.
 *
 * <p>A title here is the file name a document was registered under. Distinct names
 * are grouped so an analyst can see every title once, with how many registered
 * files carry it, and drill through to those files. All figures come from the
 * {@code path} registry.
 */
public final class TitlesScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private final ObservableList<TitleRow> rows = FXCollections.observableArrayList();
    private List<TitleRow> all = List.of();
    private TableView<TitleRow> table;
    private TextField searchField;
    private ComboBox<String> sortBox;
    private ComboBox<Integer> perPage;
    private Label countLabel;
    private Label pageLabel;
    private HBox pageBar;
    private int page;
    private VBox tiles;

    public TitlesScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Titles";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Titles";
    }

    @Override
    public String icon() {
        return Icons.CARD_HEADING;
    }

    @Override
    public Node build() {
        tiles = new VBox();

        searchField = Fas.field("Search titles...");
        searchField.setPrefWidth(240);
        searchField.textProperty().addListener((o, a, b) -> {
            page = 0;
            applyFilter();
        });

        sortBox = new ComboBox<>(FXCollections.observableArrayList(
                "Name (A-Z)", "Name (Z-A)", "Most files", "Fewest files"));
        sortBox.setValue("Name (A-Z)");
        sortBox.setOnAction(e -> {
            page = 0;
            applyFilter();
        });

        perPage = new ComboBox<>(FXCollections.observableArrayList(10, 25, 50, 100));
        perPage.setValue(25);
        perPage.setOnAction(e -> {
            page = 0;
            applyFilter();
        });

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        countLabel = Fas.muted("0 titles");
        table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState("No titles yet. Ingest files to populate this list."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<TitleRow, String> cTitle = new TableColumn<>("Title");
        cTitle.setPrefWidth(340);
        cTitle.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().title()));
        TableColumn<TitleRow, String> cFiles = new TableColumn<>("Files");
        cFiles.setPrefWidth(80);
        cFiles.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().files().size())));
        TableColumn<TitleRow, String> cTypes = new TableColumn<>("Types");
        cTypes.setPrefWidth(140);
        cTypes.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.join(", ", c.getValue().types())));
        TableColumn<TitleRow, String> cSources = new TableColumn<>("Sources");
        cSources.setPrefWidth(180);
        cSources.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.join(", ", c.getValue().sources())));
        TableColumn<TitleRow, TitleRow> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(130);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(TitleRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button open = Fas.ghost("Open files", Icons.FOLDER_OPEN);
                open.setOnAction(e -> {
                    if (!item.files().isEmpty()) {
                        router.openFile(item.files().get(0).id());
                    }
                });
                Button search = Fas.ghost("", Icons.SEARCH);
                search.setOnAction(e -> router.openSearch("\"" + item.title() + "\""));
                setGraphic(Fas.row(2, open, search));
            }
        });
        table.getColumns().addAll(cTitle, cFiles, cTypes, cSources, cAct);
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<TitleRow> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()
                        && !row.getItem().files().isEmpty()) {
                    router.openFile(row.getItem().files().get(0).id());
                }
            });
            return row;
        });

        pageLabel = Fas.muted("");
        pageBar = new HBox(6);
        pageBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox filterBar = new VBox(10,
                Fas.row(10,
                        Fas.formField("Search", searchField),
                        Fas.formField("Sort By", sortBox),
                        Fas.formField("Per Page", perPage),
                        Fas.spacer(), refresh));
        filterBar.getStyleClass().add("filter-bar");

        VBox body = new VBox(10, countLabel, table,
                Fas.row(8, pageLabel, Fas.spacer(), pageBar));

        VBox content = new VBox(16,
                Fas.pageHeader("Titles", breadcrumb()),
                tiles, filterBar,
                Fas.cardWithHeader("Document Titles",
                        "Distinct file names across registered material; double-click to open",
                        body));
        content.setPadding(new Insets(20));
        return content;
    }

    @Override
    public void onShow() {
        try {
            List<PathDto> paths = facades.contents()
                    .getPaths(null, null, null, null, 100_000, 0).results();
            Map<String, List<PathDto>> grouped = new LinkedHashMap<>();
            for (PathDto p : paths) {
                String name = p.fileName() == null || p.fileName().isBlank()
                        ? "(untitled)" : p.fileName();
                grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(p);
            }
            List<TitleRow> list = new ArrayList<>();
            for (Map.Entry<String, List<PathDto>> e : grouped.entrySet()) {
                list.add(TitleRow.of(e.getKey(), e.getValue()));
            }
            all = list;

            int multi = 0;
            for (TitleRow t : list) {
                if (t.files().size() > 1) {
                    multi++;
                }
            }
            tiles.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.CARD_HEADING, Fas.PRIMARY,
                            String.format("%,d", list.size()), "Total Titles"),
                    Fas.statCard(Icons.FILES, Fas.SUCCESS,
                            String.format("%,d", paths.size()), "Registered Files"),
                    Fas.statCard(Icons.FILES, Fas.WARNING,
                            String.valueOf(multi), "Titles on 2+ Files")));

            page = 0;
            applyFilter();
        } catch (RuntimeException e) {
            all = List.of();
            rows.clear();
            countLabel.setText("0 titles");
        }
    }

    private void applyFilter() {
        String q = searchField == null || searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase();
        List<TitleRow> filtered = new ArrayList<>();
        for (TitleRow t : all) {
            if (q.isEmpty() || t.title().toLowerCase().contains(q)) {
                filtered.add(t);
            }
        }
        String sort = sortBox == null ? "Name (A-Z)" : sortBox.getValue();
        switch (sort) {
            case "Name (Z-A)" -> filtered.sort((a, b) ->
                    b.title().compareToIgnoreCase(a.title()));
            case "Most files" -> filtered.sort((a, b) ->
                    Integer.compare(b.files().size(), a.files().size()));
            case "Fewest files" -> filtered.sort((a, b) ->
                    Integer.compare(a.files().size(), b.files().size()));
            default -> filtered.sort((a, b) ->
                    a.title().compareToIgnoreCase(b.title()));
        }

        int limit = perPage == null || perPage.getValue() == null ? 25 : perPage.getValue();
        int total = filtered.size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) limit));
        page = Math.min(Math.max(0, page), pages - 1);
        int from = page * limit;
        int to = Math.min(from + limit, total);
        rows.setAll(from < to ? filtered.subList(from, to) : List.of());
        countLabel.setText(String.format("%,d title%s", total, total == 1 ? "" : "s"));
        pageLabel.setText(total == 0 ? "" : "Page " + (page + 1) + " of " + pages);
        buildPageBar(pages);
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
            pageBar.getChildren().add(Fas.muted("..."));
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

    /** One distinct title with the registered files carrying it. */
    public record TitleRow(String title, List<PathDto> files,
                            List<String> types, List<String> sources) {
        static TitleRow of(String title, List<PathDto> files) {
            java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> sources = new java.util.LinkedHashSet<>();
            for (PathDto p : files) {
                if (p.fileType() != null && !p.fileType().isBlank()) {
                    types.add(p.fileType());
                }
                if (p.sourceName() != null && !p.sourceName().isBlank()) {
                    sources.add(p.sourceName());
                }
            }
            return new TitleRow(title, List.copyOf(files),
                    List.copyOf(types), List.copyOf(sources));
        }
    }
}
