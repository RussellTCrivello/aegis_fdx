package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.SavedSearchDto;
import com.aegis.fdx.facade.dto.SearchHistoryDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Python parity: {@code templates/Search/saved_searches.html} plus the
 * {@code /api/search/saved} and {@code /api/search/history} routes.
 */
public final class SavedSearchesScreen implements Screen {

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AegisFacades facades;
    private final ObservableList<SavedSearchDto> saved = FXCollections.observableArrayList();
    private final ObservableList<SearchHistoryDto> history = FXCollections.observableArrayList();

    public SavedSearchesScreen(AegisFacades facades) {
        this.facades = facades;
    }

    @Override
    public String title() {
        return "Saved Searches";
    }

    @Override
    public String breadcrumb() {
        return "Home / Search / Saved Searches";
    }

    @Override
    public String icon() {
        return Icons.BOOKMARK;
    }

    @Override
    public Node build() {
        TableView<SavedSearchDto> savedTable = new TableView<>(saved);
        savedTable.setPlaceholder(Fas.emptyState("No saved searches yet."));
        VBox.setVgrow(savedTable, Priority.ALWAYS);

        TableColumn<SavedSearchDto, String> sName = new TableColumn<>("Name");
        sName.setPrefWidth(180);
        sName.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        TableColumn<SavedSearchDto, String> sQuery = new TableColumn<>("Query");
        sQuery.setPrefWidth(240);
        sQuery.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().query()));
        TableColumn<SavedSearchDto, String> sUsed = new TableColumn<>("Uses");
        sUsed.setPrefWidth(70);
        sUsed.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().useCount())));
        TableColumn<SavedSearchDto, String> sCreated = new TableColumn<>("Created");
        sCreated.setPrefWidth(140);
        sCreated.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().createdAt() == null ? "" : DT.format(c.getValue().createdAt())));

        TableColumn<SavedSearchDto, SavedSearchDto> sAct = new TableColumn<>("Actions");
        sAct.setPrefWidth(120);
        sAct.setSortable(false);
        sAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        sAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(SavedSearchDto s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setGraphic(null); return; }
                Button rename = Fas.ghost("", Icons.PENCIL);
                rename.setOnAction(e -> {
                    TextInputDialog d = new TextInputDialog(s.name());
                    d.setTitle("Rename saved search");
                    d.setHeaderText("Rename");
                    d.setContentText("Name:");
                    d.showAndWait().ifPresent(n -> {
                        facades.history().updateSavedSearch(s.id(), n, null, null);
                        onShow();
                    });
                });
                Button del = Fas.ghost("", Icons.TRASH);
                del.setOnAction(e -> {
                    facades.history().deleteSavedSearch(s.id());
                    onShow();
                });
                setGraphic(Fas.row(2, rename, del));
            }
        });
        savedTable.getColumns().addAll(sName, sQuery, sUsed, sCreated, sAct);

        TableView<SearchHistoryDto> histTable = new TableView<>(history);
        histTable.setPlaceholder(Fas.emptyState("No searches run yet."));
        VBox.setVgrow(histTable, Priority.ALWAYS);
        TableColumn<SearchHistoryDto, String> hQuery = new TableColumn<>("Query");
        hQuery.setPrefWidth(260);
        hQuery.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().query()));
        TableColumn<SearchHistoryDto, String> hCount = new TableColumn<>("Results");
        hCount.setPrefWidth(90);
        hCount.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.format("%,d", c.getValue().resultCount())));
        TableColumn<SearchHistoryDto, String> hWhen = new TableColumn<>("When");
        hWhen.setPrefWidth(150);
        hWhen.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().searchedAt() == null ? "" : DT.format(c.getValue().searchedAt())));
        histTable.getColumns().addAll(hQuery, hCount, hWhen);

        Button clear = Fas.danger("Clear History", Icons.TRASH);
        clear.setOnAction(e -> {
            facades.history().clearHistory();
            onShow();
        });
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        VBox left = Fas.cardWithHeader("Saved Searches", null, savedTable);
        VBox right = Fas.cardWithHeader("Search History", null,
                new VBox(10, histTable, Fas.row(8, Fas.spacer(), clear)));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox split = new HBox(14, left, right);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader("Saved Searches", "Home / Search / Saved Searches", refresh),
                split);
        content.setPadding(new Insets(20));
        return content;
    }

    @Override
    public void onShow() {
        try {
            saved.setAll(facades.history().getSavedSearches());
            history.setAll(facades.history().getHistory(50, null));
        } catch (RuntimeException e) {
            saved.clear();
            history.clear();
        }
    }
}
