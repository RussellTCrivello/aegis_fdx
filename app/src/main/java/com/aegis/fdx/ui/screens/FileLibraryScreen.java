package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.ContentFacade;
import com.aegis.fdx.facade.dto.ContentDto;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Python parity: {@code templates/file/files_list.html},
 * {@code file/file_detail.html} and {@code file/full_content.html}.
 *
 * <p>Columns match the Python file list exactly: File Name, Type, Size, Source, Side,
 * Status, Date, Actions. Rows come from the {@code paths} registry, which is populated
 * from real AEGIS ingest results by {@link ContentFacade#registerIngestedItems}.
 */
public final class FileLibraryScreen implements Screen {

    private final AegisFacades facades;
    private final ObservableList<PathDto> rows = FXCollections.observableArrayList();
    private TableView<PathDto> table;
    private ComboBox<String> typeFilter;
    private ComboBox<String> statusFilter;
    private ComboBox<Integer> perPage;
    private Label countLabel;
    private Label pageLabel;
    private int offset;
    private int total;

    public FileLibraryScreen(AegisFacades facades) {
        this.facades = facades;
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
        typeFilter = new ComboBox<>(FXCollections.observableArrayList("Any type"));
        typeFilter.setValue("Any type");
        typeFilter.setOnAction(e -> {
            offset = 0;
            onShow();
        });

        statusFilter = new ComboBox<>(FXCollections.observableArrayList(
                "Any status", ContentFacade.STATUS_READ, ContentFacade.STATUS_UNREAD));
        statusFilter.setValue("Any status");
        statusFilter.setOnAction(e -> {
            offset = 0;
            onShow();
        });

        perPage = new ComboBox<>(FXCollections.observableArrayList(25, 50, 100));
        perPage.setValue(50);
        perPage.setOnAction(e -> {
            offset = 0;
            onShow();
        });

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        countLabel = Fas.muted("0 files");
        table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState(
                "No files registered yet. Ingest a folder on the Upload Files page."));
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getColumns().add(col("File Name", 230, PathDto::fileName));
        table.getColumns().add(col("Type", 75, PathDto::fileType));
        table.getColumns().add(col("Size", 90,
                p -> DashboardScreen.humanBytes(p.fileSize())));
        table.getColumns().add(col("Source", 130, PathDto::sourceName));
        table.getColumns().add(col("Aspect", 120, PathDto::aspectName));

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
                boolean read = ContentFacade.STATUS_READ.equals(item.fileStatus());
                setGraphic(Fas.badge(item.fileStatus(), read ? "success" : "muted"));
            }
        });
        table.getColumns().add(cStatus);

        table.getColumns().add(col("Date", 105,
                p -> p.fileDate() == null ? "" : p.fileDate().toString()));

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
                view.setOnAction(e -> showDetail(item));
                Button content = Fas.ghost("", Icons.FILE_TEXT);
                content.setOnAction(e -> showContent(item));
                Button toggle = Fas.ghost("", Icons.CHECK_CIRCLE);
                toggle.setOnAction(e -> {
                    facades.contents().setPathStatus(item.id(),
                            ContentFacade.STATUS_READ.equals(item.fileStatus())
                                    ? ContentFacade.STATUS_UNREAD : ContentFacade.STATUS_READ);
                    onShow();
                });
                setGraphic(Fas.row(2, view, content, toggle));
            }
        });
        table.getColumns().add(cAct);

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

        HBox filters = new HBox(10,
                Fas.formField("Type", typeFilter),
                Fas.formField("Status", statusFilter),
                Fas.formField("Per Page", perPage));

        VBox body = new VBox(10, filters, countLabel, table,
                Fas.row(8, Fas.spacer(), pageLabel, prev, next));

        VBox content = new VBox(16,
                Fas.pageHeader("File Library", "Home / File Library", refresh),
                Fas.cardWithHeader("Registered Files",
                        "Files discovered by the ingest pipeline", body));
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

    /** Python {@code file/file_detail.html}. */
    private void showDetail(PathDto p) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("File: " + p.fileName());
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        GridPane g = new GridPane();
        g.setHgap(16);
        g.setVgap(8);
        g.setPadding(new Insets(16));
        int r = 0;
        r = row(g, r, "ID", String.valueOf(p.id()));
        r = row(g, r, "File Name", p.fileName());
        r = row(g, r, "File Path", p.filePath());
        r = row(g, r, "Type", p.fileType());
        r = row(g, r, "Size", DashboardScreen.humanBytes(p.fileSize()));
        r = row(g, r, "Status", p.fileStatus());
        r = row(g, r, "File Date", String.valueOf(p.fileDate()));
        r = row(g, r, "Date Added", String.valueOf(p.dateCreation()));
        r = row(g, r, "SHA-256", p.hashValue());
        r = row(g, r, "Source", p.sourceName());
        r = row(g, r, "Aspect", p.aspectName());
        r = row(g, r, "Coordinates", p.coordinates());
        row(g, r, "Element ID", p.elementId());

        ScrollPane sp = new ScrollPane(g);
        sp.setFitToWidth(true);
        sp.setPrefSize(560, 400);
        dlg.getDialogPane().setContent(sp);
        dlg.showAndWait();
    }

    /** Python {@code file/full_content.html}. */
    private void showContent(PathDto p) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Content: " + p.fileName());
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextArea ta = new TextArea();
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefSize(680, 460);
        String text = facades.contents().getContentAsText(p.id());
        if (text == null || text.isBlank()) {
            java.util.List<ContentDto> parts = facades.contents().getContents(p.id());
            text = parts.isEmpty() ? "(no extracted content stored for this file)" : "";
        }
        ta.setText(text);
        dlg.getDialogPane().setContent(ta);
        dlg.showAndWait();
    }

    private static int row(GridPane g, int r, String label, String value) {
        g.add(Fas.fieldLabel(label), 0, r);
        Label v = new Label(value == null || value.isBlank() ? "\u2014" : value);
        v.setWrapText(true);
        v.setMaxWidth(360);
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        g.add(v, 1, r);
        return r + 1;
    }

    @Override
    public void onShow() {
        try {
            String type = "Any type".equals(typeFilter.getValue()) ? null : typeFilter.getValue();
            String status = "Any status".equals(statusFilter.getValue())
                    ? null : statusFilter.getValue();
            int limit = perPage.getValue();

            Page<PathDto> page = facades.contents()
                    .getPaths(type, null, null, status, limit, offset);
            rows.setAll(page.results());
            total = page.totalCount();
            countLabel.setText(String.format("%,d file%s", total, total == 1 ? "" : "s"));
            int from = total == 0 ? 0 : offset + 1;
            pageLabel.setText(from + " - " + Math.min(offset + limit, total)
                    + " of " + String.format("%,d", total));

            // keep the type filter in sync with what is actually registered
            String keep = typeFilter.getValue();
            var types = facades.contents().getPaths(null, null, null, null, 10_000, 0)
                    .results().stream().map(PathDto::fileType).distinct().sorted().toList();
            typeFilter.getItems().setAll("Any type");
            typeFilter.getItems().addAll(types);
            typeFilter.setValue(typeFilter.getItems().contains(keep) ? keep : "Any type");
        } catch (RuntimeException e) {
            rows.clear();
        }
    }
}
