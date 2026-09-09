package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FileState;
import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.SourceDto;
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
import java.util.List;

/**
 * File Library: evidence repository and document management.
 *
 * <p>Summary stats show Total Files, Analyzed Files, and Pending Files.
 * Multi-dimensional filters narrow by Source, Side/Aspect, File Type, Status, and search text.
 */
public final class FileLibraryScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;
    private final ObservableList<PathDto> rows = FXCollections.observableArrayList();

    private TableView<PathDto> table;
    private TextField searchField;
    private ComboBox<String> sourceFilter;
    private ComboBox<String> aspectFilter;
    private ComboBox<String> typeFilter;
    private ComboBox<String> statusFilter;
    private ComboBox<Integer> perPage;

    private VBox tileTotal;
    private VBox tileAnalyzed;
    private VBox tilePending;

    private Label countLabel;
    private Label pageLabel;
    private int offset;
    private int total;
    private boolean updatingFilters;

    public FileLibraryScreen(AegisFacades facades) {
        this(facades, null);
    }

    public FileLibraryScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "File Library";
    }

    @Override
    public String icon() {
        return Icons.FOLDER_OPEN;
    }

    @Override
    public Node build() {
        tileTotal = Fas.summaryTile("0", "Total Files");
        tileAnalyzed = Fas.summaryTile("0", "Analyzed (Read)");
        tilePending = Fas.summaryTile("0", "Pending (Unread)");
        VBox tiles = new VBox(Fas.statsGrid(tileTotal, tileAnalyzed, tilePending));

        searchField = Fas.field("Search Files...");
        searchField.setPrefWidth(180);
        searchField.textProperty().addListener((o, a, b) -> {
            if (updatingFilters) return;
            offset = 0;
            loadRows();
        });

        sourceFilter = new ComboBox<>(FXCollections.observableArrayList("Any source"));
        sourceFilter.setValue("Any source");
        sourceFilter.setPrefWidth(140);
        sourceFilter.setOnAction(e -> {
            if (updatingFilters) return;
            offset = 0;
            loadRows();
        });

        aspectFilter = new ComboBox<>(FXCollections.observableArrayList("Any side"));
        aspectFilter.setValue("Any side");
        aspectFilter.setPrefWidth(140);
        aspectFilter.setOnAction(e -> {
            if (updatingFilters) return;
            offset = 0;
            loadRows();
        });

        typeFilter = new ComboBox<>(FXCollections.observableArrayList("Any type"));
        typeFilter.setValue("Any type");
        typeFilter.setPrefWidth(120);
        typeFilter.setOnAction(e -> {
            if (updatingFilters) return;
            offset = 0;
            loadRows();
        });

        statusFilter = new ComboBox<>(FXCollections.observableArrayList(
                "Any status", FileState.READ.label(), FileState.UNREAD.label()));
        statusFilter.setValue("Any status");
        statusFilter.setPrefWidth(120);
        statusFilter.setOnAction(e -> {
            if (updatingFilters) return;
            offset = 0;
            loadRows();
        });

        perPage = new ComboBox<>(FXCollections.observableArrayList(25, 50, 100));
        perPage.setValue(50);
        perPage.setPrefWidth(85);
        perPage.setOnAction(e -> {
            if (updatingFilters) return;
            offset = 0;
            loadRows();
        });

        Button uploadBtn = Fas.primary("Upload Files", Icons.UPLOAD);
        uploadBtn.setOnAction(e -> router.open("Upload Files"));

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        Button clearFilters = Fas.ghost("Clear", Icons.CLOSE);
        clearFilters.setOnAction(e -> {
            searchField.clear();
            sourceFilter.setValue("Any source");
            aspectFilter.setValue("Any side");
            typeFilter.setValue("Any type");
            statusFilter.setValue("Any status");
            offset = 0;
            loadRows();
        });

        countLabel = Fas.muted("0 files");
        table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState(
                "No files registered yet. Ingest a folder on the Upload Files page."));
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getColumns().add(col("ID", 50, p -> String.valueOf(p.id())));
        table.getColumns().add(col("File Name", 230, PathDto::fileName));
        table.getColumns().add(col("Type", 75, PathDto::fileType));
        table.getColumns().add(col("Size", 90,
                p -> DashboardScreen.humanBytes(p.fileSize())));
        table.getColumns().add(col("Source", 130, PathDto::sourceName));
        table.getColumns().add(col("Side", 120, PathDto::aspectName));

        TableColumn<PathDto, PathDto> cStatus = new TableColumn<>("Status");
        cStatus.setPrefWidth(100);
        cStatus.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cStatus.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(PathDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                boolean read = FileState.READ.label().equals(item.fileStatus());
                setGraphic(Fas.badge(item.fileStatus(), read ? "success" : "muted"));
            }
        });
        table.getColumns().add(cStatus);

        table.getColumns().add(col("Date", 105,
                p -> p.fileDate() == null ? "—" : p.fileDate().toString()));

        TableColumn<PathDto, PathDto> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(150);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(PathDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button view = Fas.ghost("", Icons.EYE);
                view.setOnAction(e -> router.openFile(item.id()));
                Button content = Fas.ghost("", Icons.FILE_TEXT);
                content.setOnAction(e -> router.openContent(item.id()));
                Button toggle = Fas.ghost("", Icons.CHECK_CIRCLE);
                toggle.setOnAction(e -> {
                    facades.contents().setPathStatus(item.id(),
                            FileState.READ.label().equals(item.fileStatus())
                                    ? FileState.UNREAD.label() : FileState.READ.label());
                    loadRows();
                });
                setGraphic(Fas.row(2, view, content, toggle));
            }
        });
        table.getColumns().add(cAct);
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<PathDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openFile(row.getItem().id());
                }
            });
            return row;
        });

        pageLabel = Fas.muted("0 - 0 of 0");
        Button prev = Fas.outline("Previous", null);
        prev.setOnAction(e -> {
            int limit = perPage.getValue() == null ? 50 : perPage.getValue();
            offset = Math.max(0, offset - limit);
            loadRows();
        });
        Button next = Fas.outline("Next", null);
        next.setOnAction(e -> {
            int limit = perPage.getValue() == null ? 50 : perPage.getValue();
            if (offset + limit < total) {
                offset += limit;
                loadRows();
            }
        });

        HBox filters = new HBox(10,
                Fas.formField("Search", searchField),
                Fas.formField("Source", sourceFilter),
                Fas.formField("Side", aspectFilter),
                Fas.formField("Type", typeFilter),
                Fas.formField("Status", statusFilter),
                Fas.formField("Per Page", perPage),
                Fas.spacer(), clearFilters);
        filters.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        filters.getStyleClass().add("filter-bar");

        VBox body = new VBox(10, filters, countLabel, table,
                Fas.row(8, Fas.spacer(), pageLabel, prev, next));

        VBox content = new VBox(16,
                Fas.pageHeader("File Library", "Home / File Library", uploadBtn, refresh),
                tiles,
                Fas.cardWithHeader("Registered Files",
                        "Files discovered by the ingest pipeline — double-click to open file detail", body));
        content.setPadding(new Insets(20));
        return content;
    }

    private static TableColumn<PathDto, String> col(
            String name, double w, java.util.function.Function<PathDto, String> f) {
        TableColumn<PathDto, String> c = new TableColumn<>(name);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    @Override
    public void onShow() {
        refreshFilters();
        loadRows();
    }

    private void refreshFilters() {
        updatingFilters = true;
        try {
            String keepType = typeFilter.getValue();
            var types = facades.contents().getPaths(null, null, null, null, 10_000, 0)
                    .results().stream().map(PathDto::fileType)
                    .filter(t -> t != null && !t.isBlank())
                    .distinct().sorted().toList();
            List<String> typeItems = new ArrayList<>();
            typeItems.add("Any type");
            typeItems.addAll(types);
            typeFilter.getItems().setAll(typeItems);
            typeFilter.setValue(typeItems.contains(keepType) ? keepType : "Any type");

            String keepSrc = sourceFilter.getValue();
            List<String> srcItems = new ArrayList<>();
            srcItems.add("Any source");
            facades.sources().listSources().forEach(s -> srcItems.add(s.name()));
            sourceFilter.getItems().setAll(srcItems);
            sourceFilter.setValue(srcItems.contains(keepSrc) ? keepSrc : "Any source");

            String keepAspect = aspectFilter.getValue();
            List<String> aspectItems = new ArrayList<>();
            aspectItems.add("Any side");
            facades.aspects().listAspects().forEach(a -> aspectItems.add(a.name()));
            aspectFilter.getItems().setAll(aspectItems);
            aspectFilter.setValue(aspectItems.contains(keepAspect) ? keepAspect : "Any side");
        } catch (RuntimeException ignored) {
        } finally {
            updatingFilters = false;
        }
    }

    private void loadRows() {
        try {
            String type = (typeFilter == null || "Any type".equals(typeFilter.getValue())) ? null : typeFilter.getValue();
            String status = (statusFilter == null || "Any status".equals(statusFilter.getValue()))
                    ? null : statusFilter.getValue();
            String q = searchField == null || searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
            Integer srcId = resolveSourceId(sourceFilter == null ? null : sourceFilter.getValue());
            Integer aspectId = resolveAspectId(aspectFilter == null ? null : aspectFilter.getValue());

            int limit = (perPage == null || perPage.getValue() == null) ? 50 : perPage.getValue();

            Page<PathDto> page = facades.contents().getPaths(type, srcId, aspectId, status, 10_000, 0);
            List<PathDto> allMatches = page.results();
            if (!q.isEmpty()) {
                allMatches = allMatches.stream()
                        .filter(p -> p.fileName().toLowerCase().contains(q) || (p.filePath() != null && p.filePath().toLowerCase().contains(q)))
                        .toList();
            }

            total = allMatches.size();
            int from = total == 0 ? 0 : offset;
            int to = Math.min(from + limit, total);
            rows.setAll(from < to ? allMatches.subList(from, to) : List.of());

            // Summary stats
            int totalCase = facades.contents().getPaths(null, null, null, null, 10_000, 0).totalCount();
            int readCount = facades.contents().getPaths(null, null, null, FileState.READ.label(), 10_000, 0).totalCount();
            int unreadCount = facades.contents().getPaths(null, null, null, FileState.UNREAD.label(), 10_000, 0).totalCount();

            Fas.setSummary(tileTotal, String.format("%,d", totalCase));
            Fas.setSummary(tileAnalyzed, String.format("%,d", readCount));
            Fas.setSummary(tilePending, String.format("%,d", unreadCount));

            countLabel.setText(String.format("%,d file%s matching filter", total, total == 1 ? "" : "s"));
            pageLabel.setText(total == 0 ? "0 - 0 of 0" : (from + 1) + " - " + to + " of " + String.format("%,d", total));
        } catch (RuntimeException e) {
            rows.clear();
        }
    }

    private Integer resolveSourceId(String name) {
        if (name == null || "Any source".equals(name)) return null;
        try {
            return facades.sources().listSources().stream()
                    .filter(s -> name.equals(s.name())).map(SourceDto::id).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private Integer resolveAspectId(String name) {
        if (name == null || "Any side".equals(name) || "Any aspect".equals(name)) return null;
        try {
            return facades.aspects().listAspects().stream()
                    .filter(a -> name.equals(a.name())).map(AspectDto::id).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }
}
