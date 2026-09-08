package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SourceDraft;
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
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;

/**
 * Sources: the people, organisations and systems material originates from.
 *
 * <p>Lists every source with search, and offers create, view, duplicate and delete.
 * The create form captures identity, importance, provenance notes and access status.
 */
public final class SourcesScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;
    private final ObservableList<SourceDto> rows = FXCollections.observableArrayList();
    private TableView<SourceDto> table;
    private Label countLabel;
    private TextField searchField;

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
        Button add = Fas.primary("Add Source", Icons.PLUS);
        add.setOnAction(e -> openForm(null));

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        searchField = Fas.field("Search sources...");
        searchField.setPrefWidth(240);
        searchField.textProperty().addListener((o, a, b) -> applyFilter(b));

        countLabel = Fas.muted("0 sources");

        table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState("No sources yet. Use \u201cAdd Source\u201d to create one."));
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getColumns().addAll(
                col("ID", 60, s -> String.valueOf(s.id())),
                col("Source Name", 190, SourceDto::name),
                col("Job/Type", 130, SourceDto::job),
                col("Importance", 90, s -> String.format("%.2f", s.importance())),
                col("Country", 100, SourceDto::country),
                col("City", 110, SourceDto::city),
                col("Access Status", 120, SourceDto::accessStatus),
                col("Discovered", 105,
                        s -> s.entryDate() == null ? "" : s.entryDate().toString()));

        TableColumn<SourceDto, SourceDto> actions = new TableColumn<>("Actions");
        actions.setPrefWidth(190);
        actions.setSortable(false);
        actions.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        actions.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(SourceDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button view = Fas.ghost("", Icons.EYE);
                view.setOnAction(e -> router.openSource(item.id()));
                Button dup = Fas.ghost("", Icons.FILES);
                dup.setOnAction(e -> {
                    facades.sources().duplicateSource(item.id());
                    onShow();
                });
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> confirmDelete(item));
                setGraphic(Fas.row(2, view, dup, del));
            }
        });
        table.getColumns().add(actions);
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<SourceDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openSource(row.getItem().id());
                }
            });
            return row;
        });

        VBox content = new VBox(16,
                Fas.pageHeader("Sources", "Home / Sources", searchField, refresh, add),
                Fas.cardWithHeader("All Sources", null, new VBox(10, countLabel, table)));
        content.setPadding(new Insets(20));
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    private TableColumn<SourceDto, String> col(String name, double width,
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
            rows.setAll(facades.sources().listSources());
            countLabel.setText(rows.size() + (rows.size() == 1 ? " source" : " sources"));
            if (searchField != null && !searchField.getText().isBlank()) {
                applyFilter(searchField.getText());
            }
        } catch (RuntimeException e) {
            rows.clear();
        }
    }

    private void applyFilter(String term) {
        if (term == null || term.isBlank()) {
            rows.setAll(facades.sources().listSources());
        } else {
            String t = term.toLowerCase();
            rows.setAll(facades.sources().listSources().stream()
                    .filter(s -> nz(s.name()).toLowerCase().contains(t)
                            || nz(s.country()).toLowerCase().contains(t)
                            || nz(s.job()).toLowerCase().contains(t))
                    .toList());
        }
        countLabel.setText(rows.size() + (rows.size() == 1 ? " source" : " sources"));
    }

    /** Python {@code sources_list.html} create form / {@code source_detail.html}. */
    private void openForm(SourceDto existing) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(existing == null ? "Add Source" : "Edit Source");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField name = Fas.field("Source name");
        TextField job = Fas.field("Job / type");
        TextField importance = Fas.field("0.0 - 1.0");
        TextField country = Fas.field("Country");
        TextField city = Fas.field("City");
        TextArea description = new TextArea();
        description.setPrefRowCount(2);
        TextField accounts = Fas.field("Social media accounts");
        TextField attachments = Fas.field("Attachments");
        TextArea note = new TextArea();
        note.setPrefRowCount(2);
        TextField ownership = Fas.field("Ownership");
        TextField accessStatus = Fas.field("Access status");
        DatePicker entryDate = new DatePicker(LocalDate.now());
        TextField categoryId = Fas.field("Category id (optional)");

        importance.setText("0.50");

        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(10);
        g.setPadding(new Insets(16));
        int r = 0;
        g.add(Fas.formField("Source Name *", name), 0, r);
        g.add(Fas.formField("Job/Type *", job), 1, r++);
        g.add(Fas.formField("Importance *", importance), 0, r);
        g.add(Fas.formField("Country *", country), 1, r++);
        g.add(Fas.formField("City", city), 0, r);
        g.add(Fas.formField("Social Media Accounts", accounts), 1, r++);
        g.add(Fas.formField("Attachments", attachments), 0, r);
        g.add(Fas.formField("Ownership", ownership), 1, r++);
        g.add(Fas.formField("Access Status", accessStatus), 0, r);
        g.add(Fas.formField("Date of Source Discovery", entryDate), 1, r++);
        g.add(Fas.formField("Category", categoryId), 0, r++);
        g.add(Fas.formField("Description", description), 0, r, 2, 1);
        r++;
        g.add(Fas.formField("Notes", note), 0, r, 2, 1);

        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().setPrefWidth(660);

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) {
                return;
            }
            try {
                double imp = Double.parseDouble(importance.getText().trim());
                Integer cat = categoryId.getText().isBlank()
                        ? null : Integer.parseInt(categoryId.getText().trim());
                facades.sources().createSource(
                        new SourceDraft(name.getText(), country.getText(),
                                job.getText(), imp)
                                .city(city.getText())
                                .description(description.getText())
                                .accounts(accounts.getText())
                                .note(note.getText())
                                .attachments(attachments.getText())
                                .ownership(ownership.getText())
                                .accessStatus(accessStatus.getText())
                                .entryDate(entryDate.getValue())
                                .category(cat));
                onShow();
            } catch (NumberFormatException ex) {
                error("Importance must be a number between 0.0 and 1.0");
            } catch (FacadeException ex) {
                error(ex.getMessage());
            }
        });
    }

    /** Python {@code source_detail.html}. */
    private void showDetail(SourceDto s) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Source: " + s.name());
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        GridPane g = new GridPane();
        g.setHgap(16);
        g.setVgap(8);
        g.setPadding(new Insets(16));
        int r = 0;
        r = detailRow(g, r, "ID", String.valueOf(s.id()));
        r = detailRow(g, r, "Source Name", s.name());
        r = detailRow(g, r, "Job/Type", s.job());
        r = detailRow(g, r, "Importance", String.format("%.4f", s.importance()));
        r = detailRow(g, r, "Country", s.country());
        r = detailRow(g, r, "City", s.city());
        r = detailRow(g, r, "Description", s.description());
        r = detailRow(g, r, "Social Media Accounts", s.accounts());
        r = detailRow(g, r, "Attachments", s.attachments());
        r = detailRow(g, r, "Notes", s.note());
        r = detailRow(g, r, "Ownership", s.ownership());
        r = detailRow(g, r, "Access Status", s.accessStatus());
        r = detailRow(g, r, "Date of Source Discovery",
                s.entryDate() == null ? "" : s.entryDate().toString());
        detailRow(g, r, "Category", s.categoryId() == null ? "" : String.valueOf(s.categoryId()));

        ScrollPane sp = new ScrollPane(g);
        sp.setFitToWidth(true);
        sp.setPrefSize(520, 420);
        dlg.getDialogPane().setContent(sp);
        dlg.showAndWait();
    }

    private static int detailRow(GridPane g, int r, String label, String value) {
        g.add(Fas.fieldLabel(label), 0, r);
        Label v = new Label(nz(value).isBlank() ? "\u2014" : value);
        v.setWrapText(true);
        v.setMaxWidth(300);
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        g.add(v, 1, r);
        return r + 1;
    }

    private void confirmDelete(SourceDto s) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete source \u201c" + s.name() + "\u201d? This cannot be undone.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete Confirmation");
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                facades.sources().deleteSource(s.id());
                onShow();
            }
        });
    }

    private static void error(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
