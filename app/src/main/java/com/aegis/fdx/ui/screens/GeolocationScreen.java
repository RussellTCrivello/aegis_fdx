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
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Geolocation: where the case's material claims to come from.
 *
 * <p>Coordinates are recorded by the ingest pipeline when an item carries them
 * ({@code Item.geoLocation} projected onto {@code path.coordinates} at
 * registration). This destination groups every non-blank coordinate, so an
 * analyst can see which locations occur and which files carry them. When no
 * material carries coordinates the screen reports {@code 0 / 0} with an
 * explicit empty state rather than an empty grid that could be mistaken for a
 * failed load.
 */
public final class GeolocationScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private final ObservableList<GeoRow> rows = FXCollections.observableArrayList();
    private List<GeoRow> all = List.of();
    private Label summaryLabel;
    private Label countLabel;
    private TextField searchField;
    private VBox tiles;

    public GeolocationScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Geolocation";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Geolocation";
    }

    @Override
    public String icon() {
        return Icons.GEO;
    }

    @Override
    public Node build() {
        tiles = new VBox();
        summaryLabel = Fas.muted("0 / 0");
        countLabel = Fas.muted("No items found in this section");

        searchField = Fas.field("Search coordinates or file names...");
        searchField.setPrefWidth(300);
        searchField.textProperty().addListener((o, a, b) -> applyFilter(b));

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        TableView<GeoRow> table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState("No items found in this section"));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<GeoRow, String> cCoord = new TableColumn<>("Coordinates");
        cCoord.setPrefWidth(220);
        cCoord.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().coordinates()));
        TableColumn<GeoRow, String> cFiles = new TableColumn<>("Files");
        cFiles.setPrefWidth(70);
        cFiles.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().files().size())));
        TableColumn<GeoRow, String> cNames = new TableColumn<>("File Names");
        cNames.setPrefWidth(300);
        cNames.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.join(", ", c.getValue().fileNames())));
        TableColumn<GeoRow, String> cSources = new TableColumn<>("Sources");
        cSources.setPrefWidth(170);
        cSources.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.join(", ", c.getValue().sources())));
        TableColumn<GeoRow, GeoRow> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(120);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(GeoRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button open = Fas.ghost("Open file", Icons.EYE);
                open.setOnAction(e -> {
                    if (!item.files().isEmpty()) {
                        router.openFile(item.files().get(0).id());
                    }
                });
                setGraphic(open);
            }
        });
        table.getColumns().addAll(List.of(cCoord, cFiles, cNames, cSources, cAct));
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<GeoRow> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()
                        && !row.getItem().files().isEmpty()) {
                    router.openFile(row.getItem().files().get(0).id());
                }
            });
            return row;
        });

        VBox filterBar = new VBox(10,
                Fas.row(10, Fas.formField("Search", searchField),
                        Fas.spacer(), summaryLabel, refresh));
        filterBar.getStyleClass().add("filter-bar");

        VBox content = new VBox(16,
                Fas.pageHeader("Geolocation", breadcrumb()),
                tiles, filterBar,
                Fas.cardWithHeader("Locations",
                        "Grouped coordinates from registered material",
                        new VBox(10, countLabel, table)));
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
                if (p.coordinates() == null || p.coordinates().isBlank()) {
                    continue;
                }
                grouped.computeIfAbsent(p.coordinates().trim(), k -> new ArrayList<>()).add(p);
            }
            List<GeoRow> list = new ArrayList<>();
            for (Map.Entry<String, List<PathDto>> e : grouped.entrySet()) {
                list.add(GeoRow.of(e.getKey(), e.getValue()));
            }
            list.sort((a, b) -> Integer.compare(b.files().size(), a.files().size()));
            all = list;

            int located = 0;
            for (GeoRow g : list) {
                located += g.files().size();
            }
            tiles.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.GEO, Fas.INFO,
                            String.valueOf(list.size()), "Distinct Locations"),
                    Fas.statCard(Icons.FILES, Fas.PRIMARY,
                            String.valueOf(located), "Located Files"),
                    Fas.statCard(Icons.FILES, Fas.TEXT_LIGHT,
                            String.valueOf(paths.size() - located), "Files Without Coordinates")));

            applyFilter(searchField == null ? "" : searchField.getText());
        } catch (RuntimeException e) {
            all = List.of();
            rows.clear();
            summaryLabel.setText("0 / 0");
            countLabel.setText("No items found in this section");
        }
    }

    private void applyFilter(String term) {
        String q = term == null ? "" : term.trim().toLowerCase();
        List<GeoRow> filtered = new ArrayList<>();
        for (GeoRow g : all) {
            if (q.isEmpty()
                    || g.coordinates().toLowerCase().contains(q)
                    || g.fileNames().stream().anyMatch(n -> n.toLowerCase().contains(q))) {
                filtered.add(g);
            }
        }
        rows.setAll(filtered);
        summaryLabel.setText(filtered.size() + " / " + all.size());
        countLabel.setText(filtered.isEmpty()
                ? "No items found in this section"
                : filtered.size() + (filtered.size() == 1 ? " location" : " locations"));
    }

    /** One distinct coordinate with the files carrying it. */
    public record GeoRow(String coordinates, List<PathDto> files,
                          List<String> fileNames, List<String> sources) {
        static GeoRow of(String coordinates, List<PathDto> files) {
            List<String> names = new ArrayList<>();
            java.util.LinkedHashSet<String> sources = new java.util.LinkedHashSet<>();
            for (PathDto p : files) {
                names.add(p.fileName());
                if (p.sourceName() != null && !p.sourceName().isBlank()) {
                    sources.add(p.sourceName());
                }
            }
            return new GeoRow(coordinates, List.copyOf(files),
                    List.copyOf(names), List.copyOf(sources));
        }
    }
}
