package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.AspectDto;
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
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;

/**
 * Aspects: the parties or groupings that ingested material is attributed to.
 *
 * <p>Lists every aspect with its importance and creation date, and offers create,
 * duplicate and delete. An aspect plus a source forms the mandatory attribution
 * recorded against processed files.
 */
public final class AspectsScreen implements Screen {

    private final AegisFacades facades;
    private final ObservableList<AspectDto> rows = FXCollections.observableArrayList();
    private TableView<AspectDto> table;
    private Label countLabel;

    public AspectsScreen(AegisFacades facades) {
        this.facades = facades;
    }

    @Override
    public String title() {
        return "Aspects";
    }

    @Override
    public String icon() {
        return Icons.DIAGRAM3;
    }

    @Override
    public Node build() {
        Button add = Fas.primary("Add Aspect", Icons.PLUS);
        add.setOnAction(e -> openForm());
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        countLabel = Fas.muted("0 aspects");
        table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState("No sides yet. Use \u201cAdd Aspect\u201d to create one."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<AspectDto, String> cId = new TableColumn<>("ID");
        cId.setPrefWidth(70);
        cId.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().id())));

        TableColumn<AspectDto, String> cName = new TableColumn<>("Name");
        cName.setPrefWidth(260);
        cName.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().name()));

        TableColumn<AspectDto, String> cImp = new TableColumn<>("Importance");
        cImp.setPrefWidth(120);
        cImp.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.format("%.2f", c.getValue().importance())));

        TableColumn<AspectDto, String> cDate = new TableColumn<>("Date of Creation");
        cDate.setPrefWidth(150);
        cDate.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().dateCreation() == null ? "" : c.getValue().dateCreation().toString()));

        TableColumn<AspectDto, AspectDto> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(150);
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
                Button dup = Fas.ghost("", Icons.FILES);
                dup.setOnAction(e -> {
                    facades.aspects().duplicateAspect(item.id());
                    onShow();
                });
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> {
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                            "Delete aspect \u201c" + item.name() + "\u201d?",
                            ButtonType.CANCEL, ButtonType.OK);
                    a.setHeaderText("Delete Confirmation");
                    a.showAndWait().ifPresent(bt -> {
                        if (bt == ButtonType.OK) {
                            facades.aspects().deleteAspect(item.id());
                            onShow();
                        }
                    });
                });
                setGraphic(Fas.row(2, dup, del));
            }
        });

        table.getColumns().addAll(cId, cName, cImp, cDate, cAct);

        VBox content = new VBox(16,
                Fas.pageHeader("Aspects", "Home / Aspects", refresh, add),
                Fas.cardWithHeader("All Aspects", null, new VBox(10, countLabel, table)));
        content.setPadding(new Insets(20));
        return content;
    }

    @Override
    public void onShow() {
        try {
            rows.setAll(facades.aspects().listAspects());
            countLabel.setText(rows.size() + (rows.size() == 1 ? " aspect" : " aspects"));
        } catch (RuntimeException e) {
            rows.clear();
        }
    }

    private void openForm() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Add Aspect");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField name = Fas.field("Side name");
        TextField importance = Fas.field("0.0 - 1.0");
        importance.setText("0.50");
        DatePicker date = new DatePicker(LocalDate.now());

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
                facades.aspects().createAspect(name.getText(),
                        Double.parseDouble(importance.getText().trim()), date.getValue());
                onShow();
            } catch (NumberFormatException ex) {
                err("Importance must be a number between 0.0 and 1.0");
            } catch (FacadeException ex) {
                err(ex.getMessage());
            }
        });
    }

    private static void err(String m) {
        Alert a = new Alert(Alert.AlertType.ERROR, m, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }
}
