package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Email material: the messages and mailboxes the pipeline extracted.
 *
 * <p>Lists every element the analyzers identified as email, with its source and path.
 */
public final class EmailWordsScreen implements Screen {

    private final AegisFacades facades;
    private final ObservableList<SearchResultDto> rows = FXCollections.observableArrayList();
    private Label countLabel;

    public EmailWordsScreen(AegisFacades facades) {
        this.facades = facades;
    }

    @Override
    public String title() {
        return "Email Words";
    }

    @Override
    public String icon() {
        return Icons.ENVELOPE_AT;
    }

    @Override
    public Node build() {
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        countLabel = Fas.muted("0 email elements");
        TableView<SearchResultDto> table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState(
                "No email elements. Ingest a PST, MBOX, EML or MSG file."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<SearchResultDto, String> cName = new TableColumn<>("File Name");
        cName.setPrefWidth(260);
        cName.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().fileName()));
        TableColumn<SearchResultDto, String> cType = new TableColumn<>("Type");
        cType.setPrefWidth(80);
        cType.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().fileType()));
        TableColumn<SearchResultDto, String> cSrc = new TableColumn<>("Source");
        cSrc.setPrefWidth(150);
        cSrc.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().sourceName() == null ? "" : c.getValue().sourceName()));
        TableColumn<SearchResultDto, String> cPath = new TableColumn<>("Path");
        cPath.setPrefWidth(320);
        cPath.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().filePath() == null ? "" : c.getValue().filePath()));

        table.getColumns().addAll(cName, cType, cSrc, cPath);

        VBox content = new VBox(16,
                Fas.pageHeader("Email Words", "Home / Email Words", refresh),
                Fas.cardWithHeader("Email Elements",
                        "Messages and attachments extracted by the ingest pipeline",
                        new VBox(10, countLabel, table)));
        content.setPadding(new Insets(20));
        return content;
    }

    @Override
    public void onShow() {
        try {
            // The AEGIS query grammar exposes an ext: field; email formats are the
            // ones the pipeline maps to message analyzers.
            var page = facades.search().search(
                    com.aegis.fdx.facade.SearchCriteria
                            .of("ext:eml OR ext:msg OR ext:pst OR ext:ost OR ext:mbox")
                            .limit(500));
            rows.setAll(page.results());
            countLabel.setText(page.totalCount()
                    + (page.totalCount() == 1 ? " email element" : " email elements"));
        } catch (RuntimeException e) {
            rows.clear();
            countLabel.setText("0 email elements");
        }
    }
}
