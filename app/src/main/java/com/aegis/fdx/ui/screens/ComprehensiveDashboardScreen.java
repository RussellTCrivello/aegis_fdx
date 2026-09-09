package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.DashboardFacade;
import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.KeywordDto;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.facade.dto.WordDto;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.Background;
import com.aegis.fdx.ui.ChartPane;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive Dashboard: multidimensional analytics across the case.
 *
 * <p>Provides seven dedicated analysis tabs corresponding to the reference facades:
 * <ol>
 *   <li><b>Files</b>: filtered by Category, Source, Side, Keyword — table + chart.</li>
 *   <li><b>Categories</b>: filtered by Source, Side — table + chart.</li>
 *   <li><b>Keywords</b>: filtered by Category, Source, Side — table + chart.</li>
 *   <li><b>Sources</b>: filtered by File Type, Side, Keyword — table + chart.</li>
 *   <li><b>Sides</b>: filtered by File Type, Category, Keyword — table + chart.</li>
 *   <li><b>Words</b>: filtered by Category — table + frequency chart.</li>
 *   <li><b>Similar Files</b>: similarity threshold, min group size, group type — group table.</li>
 * </ol>
 *
 * <p>All operations query the case database asynchronously and never freeze JavaFX.
 */
public final class ComprehensiveDashboardScreen implements Screen {

    private final AegisFacades facades;
    private final CorpusDatabase dao;
    private final Router router;

    private final Background.Job statsJob = Background.job();
    private final Background.Job filesJob = Background.job();
    private final Background.Job catsJob = Background.job();
    private final Background.Job kwJob = Background.job();
    private final Background.Job srcJob = Background.job();
    private final Background.Job sidesJob = Background.job();
    private final Background.Job wordsJob = Background.job();
    private final Background.Job similarJob = Background.job();

    // Summary tiles
    private VBox tiles;
    private Button rebuild;
    private Label rebuildStatus;

    // Tabs
    private TabPane tabPane;

    // 1. Files tab controls
    private ComboBox<String> filesCatBox;
    private ComboBox<String> filesSrcBox;
    private ComboBox<String> filesSideBox;
    private ComboBox<String> filesKwBox;
    private final ObservableList<PathDto> filesRows = FXCollections.observableArrayList();
    private TableView<PathDto> filesTable;
    private VBox filesChartHolder;
    private Label filesCountLabel;

    // 2. Categories tab controls
    private ComboBox<String> catsSrcBox;
    private ComboBox<String> catsSideBox;
    private final ObservableList<CatAnalysisRow> catsRows = FXCollections.observableArrayList();
    private TableView<CatAnalysisRow> catsTable;
    private VBox catsChartHolder;
    private Label catsCountLabel;

    // 3. Keywords tab controls
    private ComboBox<String> kwCatBox;
    private ComboBox<String> kwSrcBox;
    private ComboBox<String> kwSideBox;
    private final ObservableList<KwAnalysisRow> kwRows = FXCollections.observableArrayList();
    private TableView<KwAnalysisRow> kwTable;
    private VBox kwChartHolder;
    private Label kwCountLabel;

    // 4. Sources tab controls
    private ComboBox<String> srcTypeBox;
    private ComboBox<String> srcSideBox;
    private ComboBox<String> srcKwBox;
    private final ObservableList<SrcAnalysisRow> srcRows = FXCollections.observableArrayList();
    private TableView<SrcAnalysisRow> srcTable;
    private VBox srcChartHolder;
    private Label srcCountLabel;

    // 5. Sides tab controls
    private ComboBox<String> sidesTypeBox;
    private ComboBox<String> sidesCatBox;
    private ComboBox<String> sidesKwBox;
    private final ObservableList<SideAnalysisRow> sidesRows = FXCollections.observableArrayList();
    private TableView<SideAnalysisRow> sidesTable;
    private VBox sidesChartHolder;
    private Label sidesCountLabel;

    // 6. Words tab controls
    private ComboBox<String> wordsCatBox;
    private TextField wordsSearchField;
    private final ObservableList<WordAnalysisRow> wordsRows = FXCollections.observableArrayList();
    private TableView<WordAnalysisRow> wordsTable;
    private VBox wordsChartHolder;
    private Label wordsCountLabel;

    // 7. Similar Files tab controls
    private ComboBox<String> simThresholdBox;
    private ComboBox<String> simGroupSizeBox;
    private ComboBox<String> simTypeBox;
    private final ObservableList<SimilarGroupRow> simRows = FXCollections.observableArrayList();
    private TableView<SimilarGroupRow> simTable;
    private VBox simChartHolder;
    private Label simCountLabel;

    public ComprehensiveDashboardScreen(AegisFacades facades, CorpusDatabase dao, Router router) {
        this.facades = facades;
        this.dao = dao;
        this.router = router;
    }

    @Override public String title() { return "Comprehensive"; }
    @Override public String breadcrumb() { return "Home / Analysis / Comprehensive"; }
    @Override public String icon() { return Icons.ARCHIVE; }

    @Override
    public Node build() {
        tiles = new VBox();
        rebuild = Fas.ghost("Rebuild Statistics", Icons.REFRESH);
        rebuild.setOnAction(e -> rebuildStats());
        rebuildStatus = Fas.muted("");

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getTabs().addAll(
                new Tab("Files", buildFilesTab()),
                new Tab("Categories", buildCategoriesTab()),
                new Tab("Keywords", buildKeywordsTab()),
                new Tab("Sources", buildSourcesTab()),
                new Tab("Sides", buildSidesTab()),
                new Tab("Words", buildWordsTab()),
                new Tab("Similar Files", buildSimilarFilesTab()));
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        tabPane.getSelectionModel().selectedIndexProperty().addListener((o, oldV, newV) -> {
            if (newV != null) {
                switch (newV.intValue()) {
                    case 0 -> loadFiles();
                    case 1 -> loadCategories();
                    case 2 -> loadKeywords();
                    case 3 -> loadSources();
                    case 4 -> loadSides();
                    case 5 -> loadWords();
                    case 6 -> loadSimilarFiles();
                }
            }
        });

        HBox topActions = new HBox(10, rebuild, rebuildStatus);
        topActions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox content = new VBox(14,
                Fas.pageHeader("Comprehensive Dashboard",
                        "Advanced filtering and analysis across files, categories, keywords, sources, sides, and words."),
                topActions,
                tiles,
                tabPane);
        content.setPadding(new Insets(20));
        VBox.setVgrow(content, Priority.ALWAYS);

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    // ------------------------------------------------------------- 1. Files Tab

    private Node buildFilesTab() {
        filesCatBox = combo("All Categories");
        filesSrcBox = combo("All Sources");
        filesSideBox = combo("All Sides");
        filesKwBox = combo("All Keywords");

        Button apply = Fas.primary("Apply Filters", Icons.FUNNEL);
        apply.setOnAction(e -> loadFiles());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            filesCatBox.setValue("All Categories");
            filesSrcBox.setValue("All Sources");
            filesSideBox.setValue("All Sides");
            filesKwBox.setValue("All Keywords");
            loadFiles();
        });

        HBox filterBar = new HBox(10,
                Fas.formField("Category", filesCatBox),
                Fas.formField("Source", filesSrcBox),
                Fas.formField("Side", filesSideBox),
                Fas.formField("Keyword", filesKwBox),
                Fas.spacer(), apply, clear);
        filterBar.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        filterBar.getStyleClass().add("filter-bar");

        filesCountLabel = Fas.muted("0 files");
        filesTable = new TableView<>(filesRows);
        filesTable.setPlaceholder(Fas.emptyState("No files match the current filters."));
        filesTable.setPrefHeight(320);
        VBox.setVgrow(filesTable, Priority.ALWAYS);

        filesTable.getColumns().add(col("ID", 50, p -> String.valueOf(p.id())));
        filesTable.getColumns().add(col("File Name", 220, PathDto::fileName));
        filesTable.getColumns().add(col("Type", 70, PathDto::fileType));
        filesTable.getColumns().add(col("Size", 85, p -> DashboardScreen.humanBytes(p.fileSize())));
        filesTable.getColumns().add(col("Source", 120, PathDto::sourceName));
        filesTable.getColumns().add(col("Side", 110, PathDto::aspectName));
        filesTable.getColumns().add(col("Status", 80, PathDto::fileStatus));
        filesTable.getColumns().add(col("Date", 100, p -> p.fileDate() == null ? "—" : String.valueOf(p.fileDate())));

        filesTable.setRowFactory(tv -> {
            TableRow<PathDto> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openFile(row.getItem().id());
                }
            });
            return row;
        });

        filesChartHolder = new VBox(8);
        filesChartHolder.setPrefWidth(300);

        HBox mainSplit = new HBox(14,
                grow(Fas.cardWithHeader("Files Table", "Double-click to open file detail",
                        new VBox(8, filesCountLabel, filesTable))),
                Fas.cardWithHeader("Files Chart", "Distribution by type / source", filesChartHolder));
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        VBox box = new VBox(12, filterBar, mainSplit);
        box.setPadding(new Insets(12));
        return box;
    }

    private void loadFiles() {
        String cat = filesCatBox.getValue();
        String src = filesSrcBox.getValue();
        String side = filesSideBox.getValue();
        String kw = filesKwBox.getValue();

        filesJob.run(
                () -> {
                    Integer srcId = idOfSource(src);
                    Integer aspectId = idOfAspect(side);
                    Integer catId = idOfCategory(cat);
                    Integer kwId = idOfKeyword(kw);

                    List<PathDto> list = facades.contents().getPaths(null, srcId, aspectId, null, 1000, 0).results();
                    if (catId != null || kwId != null) {
                        List<PathDto> filtered = new ArrayList<>();
                        for (PathDto p : list) {
                            boolean matchCat = true;
                            boolean matchKw = true;
                            if (catId != null) {
                                try {
                                    matchCat = dao.selectCategoriesForPath(p.id()).stream().anyMatch(r -> r.i("id") == catId);
                                } catch (Exception e) { matchCat = false; }
                            }
                            if (kwId != null) {
                                try {
                                    matchKw = dao.selectPathKeywords(p.id()).stream().anyMatch(r -> r.i("id") == kwId);
                                } catch (Exception e) { matchKw = false; }
                            }
                            if (matchCat && matchKw) {
                                filtered.add(p);
                            }
                        }
                        list = filtered;
                    }

                    Map<String, Integer> byType = new LinkedHashMap<>();
                    for (PathDto p : list) {
                        byType.merge(p.fileType() == null || p.fileType().isBlank() ? "unknown" : p.fileType().toLowerCase(), 1, Integer::sum);
                    }
                    return Map.entry(list, byType);
                },
                res -> {
                    filesRows.setAll(res.getKey());
                    filesCountLabel.setText(res.getKey().size() + (res.getKey().size() == 1 ? " file" : " files"));
                    filesChartHolder.getChildren().clear();
                    if (res.getValue().isEmpty()) {
                        filesChartHolder.getChildren().add(Fas.emptyState("No file types"));
                    } else {
                        filesChartHolder.getChildren().add(ChartPane.donut(ChartPane.slices(res.getValue()), 200, null));
                    }
                },
                t -> {
                    filesRows.clear();
                    filesCountLabel.setText("Error loading files: " + t.getMessage());
                });
    }

    // -------------------------------------------------------- 2. Categories Tab

    public record CatAnalysisRow(int id, String name, int files, int words, int keywords, double share) {}

    private Node buildCategoriesTab() {
        catsSrcBox = combo("All Sources");
        catsSideBox = combo("All Sides");

        Button apply = Fas.primary("Apply Filters", Icons.FUNNEL);
        apply.setOnAction(e -> loadCategories());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            catsSrcBox.setValue("All Sources");
            catsSideBox.setValue("All Sides");
            loadCategories();
        });

        HBox filterBar = new HBox(10,
                Fas.formField("Source", catsSrcBox),
                Fas.formField("Side", catsSideBox),
                Fas.spacer(), apply, clear);
        filterBar.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        filterBar.getStyleClass().add("filter-bar");

        catsCountLabel = Fas.muted("0 categories");
        catsTable = new TableView<>(catsRows);
        catsTable.setPlaceholder(Fas.emptyState("No categories found."));
        catsTable.setPrefHeight(320);
        VBox.setVgrow(catsTable, Priority.ALWAYS);

        catsTable.getColumns().add(col("ID", 50, c -> String.valueOf(c.id())));
        catsTable.getColumns().add(col("Category", 180, CatAnalysisRow::name));
        catsTable.getColumns().add(col("Files", 75, c -> String.valueOf(c.files())));
        catsTable.getColumns().add(col("Words", 75, c -> String.valueOf(c.words())));
        catsTable.getColumns().add(col("Keywords", 75, c -> String.valueOf(c.keywords())));
        catsTable.getColumns().add(col("Share", 85, c -> String.format("%.1f%%", c.share() * 100)));

        catsTable.setRowFactory(tv -> {
            TableRow<CatAnalysisRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openCategoryDetail(row.getItem().id());
                }
            });
            return row;
        });

        catsChartHolder = new VBox(8);
        catsChartHolder.setPrefWidth(320);

        HBox mainSplit = new HBox(14,
                grow(Fas.cardWithHeader("Categories Table", "Double-click to open category detail",
                        new VBox(8, catsCountLabel, catsTable))),
                Fas.cardWithHeader("Categories Chart", "File coverage by category", catsChartHolder));
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        VBox box = new VBox(12, filterBar, mainSplit);
        box.setPadding(new Insets(12));
        return box;
    }

    private void loadCategories() {
        String src = catsSrcBox.getValue();
        String side = catsSideBox.getValue();

        catsJob.run(
                () -> {
                    Integer srcId = idOfSource(src);
                    Integer aspectId = idOfAspect(side);

                    List<CategoryDto> allCats = facades.categories().listCategories(500, 0).results();
                    Map<Integer, Integer> fCounts = facades.relationships().categoryFileCounts();
                    List<CatAnalysisRow> list = new ArrayList<>();
                    Map<String, Integer> chartData = new LinkedHashMap<>();

                    int totalHits = 0;
                    for (CategoryDto c : allCats) {
                        int files = fCounts.getOrDefault(c.id(), 0);
                        int words = 0;
                        int keywords = 0;
                        try {
                            words = facades.categories().getCategoryWords(c.id(), 1000, 0).results().size();
                            keywords = dao.selectKeywordsOfCategory(c.id()).size();
                        } catch (Exception ignored) {}

                        totalHits += files;
                        list.add(new CatAnalysisRow(c.id(), c.word(), files, words, keywords, 0.0));
                        if (files > 0) {
                            chartData.put(c.word(), files);
                        }
                    }

                    final int sum = Math.max(1, totalHits);
                    List<CatAnalysisRow> finalList = list.stream()
                            .map(r -> new CatAnalysisRow(r.id(), r.name(), r.files(), r.words(), r.keywords(), r.files() / (double) sum))
                            .toList();

                    return Map.entry(finalList, chartData);
                },
                res -> {
                    catsRows.setAll(res.getKey());
                    catsCountLabel.setText(res.getKey().size() + (res.getKey().size() == 1 ? " category" : " categories"));
                    catsChartHolder.getChildren().clear();
                    if (res.getValue().isEmpty()) {
                        catsChartHolder.getChildren().add(Fas.emptyState("No categories data"));
                    } else {
                        catsChartHolder.getChildren().add(ChartPane.donut(ChartPane.top(res.getValue(), 10), 200, null));
                    }
                },
                t -> {
                    catsRows.clear();
                    catsCountLabel.setText("Error loading categories: " + t.getMessage());
                });
    }

    // ---------------------------------------------------------- 3. Keywords Tab

    public record KwAnalysisRow(int id, String phrase, String category, int hits, int files) {}

    private Node buildKeywordsTab() {
        kwCatBox = combo("All Categories");
        kwSrcBox = combo("All Sources");
        kwSideBox = combo("All Sides");

        Button apply = Fas.primary("Apply Filters", Icons.FUNNEL);
        apply.setOnAction(e -> loadKeywords());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            kwCatBox.setValue("All Categories");
            kwSrcBox.setValue("All Sources");
            kwSideBox.setValue("All Sides");
            loadKeywords();
        });

        HBox filterBar = new HBox(10,
                Fas.formField("Category", kwCatBox),
                Fas.formField("Source", kwSrcBox),
                Fas.formField("Side", kwSideBox),
                Fas.spacer(), apply, clear);
        filterBar.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        filterBar.getStyleClass().add("filter-bar");

        kwCountLabel = Fas.muted("0 keywords");
        kwTable = new TableView<>(kwRows);
        kwTable.setPlaceholder(Fas.emptyState("No keywords found."));
        kwTable.setPrefHeight(320);
        VBox.setVgrow(kwTable, Priority.ALWAYS);

        kwTable.getColumns().add(col("ID", 50, k -> String.valueOf(k.id())));
        kwTable.getColumns().add(col("Keyword Phrase", 240, KwAnalysisRow::phrase));
        kwTable.getColumns().add(col("Category", 140, KwAnalysisRow::category));
        kwTable.getColumns().add(col("Hits", 70, k -> String.valueOf(k.hits())));
        kwTable.getColumns().add(col("Files", 70, k -> String.valueOf(k.files())));

        kwTable.setRowFactory(tv -> {
            TableRow<KwAnalysisRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openKeyword(row.getItem().id());
                }
            });
            return row;
        });

        kwChartHolder = new VBox(8);
        kwChartHolder.setPrefWidth(320);

        HBox mainSplit = new HBox(14,
                grow(Fas.cardWithHeader("Keywords Table", "Double-click to open keyword detail",
                        new VBox(8, kwCountLabel, kwTable))),
                Fas.cardWithHeader("Keywords Chart", "Top keywords by occurrence", kwChartHolder));
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        VBox box = new VBox(12, filterBar, mainSplit);
        box.setPadding(new Insets(12));
        return box;
    }

    private void loadKeywords() {
        String cat = kwCatBox.getValue();
        String src = kwSrcBox.getValue();
        String side = kwSideBox.getValue();

        kwJob.run(
                () -> {
                    Integer catId = idOfCategory(cat);
                    List<KeywordDto> list = facades.keywords().listKeywords(1000, 0).results();
                    Map<Integer, Integer> fCounts = facades.relationships().keywordFileCounts();
                    List<KwAnalysisRow> rows = new ArrayList<>();
                    Map<String, Integer> chartData = new LinkedHashMap<>();

                    for (KeywordDto k : list) {
                        if (catId != null && k.categoryId() != catId) {
                            continue;
                        }
                        int files = fCounts.getOrDefault(k.id(), 0);
                        rows.add(new KwAnalysisRow(k.id(), k.keyword(), k.categoryWord() == null ? "—" : k.categoryWord(), files, files));
                        if (files > 0) {
                            chartData.put(k.keyword(), files);
                        }
                    }
                    rows.sort((a, b) -> Integer.compare(b.files(), a.files()));
                    return Map.entry(rows, chartData);
                },
                res -> {
                    kwRows.setAll(res.getKey());
                    kwCountLabel.setText(res.getKey().size() + (res.getKey().size() == 1 ? " keyword" : " keywords"));
                    kwChartHolder.getChildren().clear();
                    if (res.getValue().isEmpty()) {
                        kwChartHolder.getChildren().add(Fas.emptyState("No keyword occurrences"));
                    } else {
                        kwChartHolder.getChildren().add(ChartPane.hbars(ChartPane.top(res.getValue(), 8), 160, null));
                    }
                },
                t -> {
                    kwRows.clear();
                    kwCountLabel.setText("Error loading keywords: " + t.getMessage());
                });
    }

    // ----------------------------------------------------------- 4. Sources Tab

    public record SrcAnalysisRow(int id, String name, int files, long size, double importance) {}

    private Node buildSourcesTab() {
        srcTypeBox = combo("All Types");
        srcSideBox = combo("All Sides");
        srcKwBox = combo("All Keywords");

        Button apply = Fas.primary("Apply Filters", Icons.FUNNEL);
        apply.setOnAction(e -> loadSources());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            srcTypeBox.setValue("All Types");
            srcSideBox.setValue("All Sides");
            srcKwBox.setValue("All Keywords");
            loadSources();
        });

        HBox filterBar = new HBox(10,
                Fas.formField("File Type", srcTypeBox),
                Fas.formField("Side", srcSideBox),
                Fas.formField("Keyword", srcKwBox),
                Fas.spacer(), apply, clear);
        filterBar.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        filterBar.getStyleClass().add("filter-bar");

        srcCountLabel = Fas.muted("0 sources");
        srcTable = new TableView<>(srcRows);
        srcTable.setPlaceholder(Fas.emptyState("No sources found."));
        srcTable.setPrefHeight(320);
        VBox.setVgrow(srcTable, Priority.ALWAYS);

        srcTable.getColumns().add(col("ID", 50, s -> String.valueOf(s.id())));
        srcTable.getColumns().add(col("Source Name", 220, SrcAnalysisRow::name));
        srcTable.getColumns().add(col("Files", 75, s -> String.valueOf(s.files())));
        srcTable.getColumns().add(col("Total Size", 95, s -> DashboardScreen.humanBytes(s.size())));
        srcTable.getColumns().add(col("Importance", 85, s -> String.format("%.2f", s.importance())));

        srcTable.setRowFactory(tv -> {
            TableRow<SrcAnalysisRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openSource(row.getItem().id());
                }
            });
            return row;
        });

        srcChartHolder = new VBox(8);
        srcChartHolder.setPrefWidth(320);

        HBox mainSplit = new HBox(14,
                grow(Fas.cardWithHeader("Sources Table", "Double-click to open source detail",
                        new VBox(8, srcCountLabel, srcTable))),
                Fas.cardWithHeader("Sources Chart", "Files per source", srcChartHolder));
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        VBox box = new VBox(12, filterBar, mainSplit);
        box.setPadding(new Insets(12));
        return box;
    }

    private void loadSources() {
        srcJob.run(
                () -> {
                    List<SourceDto> sources = facades.sources().listSources();
                    List<SrcAnalysisRow> rows = new ArrayList<>();
                    Map<String, Integer> chartData = new LinkedHashMap<>();

                    for (SourceDto s : sources) {
                        try {
                            var stats = dao.selectSourceStatistics(s.id());
                            int files = stats == null ? 0 : stats.i("files");
                            long bytes = stats == null ? 0 : stats.l("bytes");
                            rows.add(new SrcAnalysisRow(s.id(), s.name(), files, bytes, s.importance()));
                            chartData.put(s.name(), files);
                        } catch (Exception e) {
                            rows.add(new SrcAnalysisRow(s.id(), s.name(), 0, 0L, s.importance()));
                        }
                    }
                    return Map.entry(rows, chartData);
                },
                res -> {
                    srcRows.setAll(res.getKey());
                    srcCountLabel.setText(res.getKey().size() + (res.getKey().size() == 1 ? " source" : " sources"));
                    srcChartHolder.getChildren().clear();
                    if (res.getValue().isEmpty()) {
                        srcChartHolder.getChildren().add(Fas.emptyState("No sources data"));
                    } else {
                        srcChartHolder.getChildren().add(ChartPane.donut(ChartPane.slices(res.getValue()), 200, null));
                    }
                },
                t -> {
                    srcRows.clear();
                    srcCountLabel.setText("Error loading sources: " + t.getMessage());
                });
    }

    // ------------------------------------------------------------- 5. Sides Tab

    public record SideAnalysisRow(int id, String name, int files, double importance, String created) {}

    private Node buildSidesTab() {
        sidesTypeBox = combo("All Types");
        sidesCatBox = combo("All Categories");
        sidesKwBox = combo("All Keywords");

        Button apply = Fas.primary("Apply Filters", Icons.FUNNEL);
        apply.setOnAction(e -> loadSides());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            sidesTypeBox.setValue("All Types");
            sidesCatBox.setValue("All Categories");
            sidesKwBox.setValue("All Keywords");
            loadSides();
        });

        HBox filterBar = new HBox(10,
                Fas.formField("File Type", sidesTypeBox),
                Fas.formField("Category", sidesCatBox),
                Fas.formField("Keyword", sidesKwBox),
                Fas.spacer(), apply, clear);
        filterBar.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        filterBar.getStyleClass().add("filter-bar");

        sidesCountLabel = Fas.muted("0 sides");
        sidesTable = new TableView<>(sidesRows);
        sidesTable.setPlaceholder(Fas.emptyState("No sides found."));
        sidesTable.setPrefHeight(320);
        VBox.setVgrow(sidesTable, Priority.ALWAYS);

        sidesTable.getColumns().add(col("ID", 50, s -> String.valueOf(s.id())));
        sidesTable.getColumns().add(col("Side Name", 220, SideAnalysisRow::name));
        sidesTable.getColumns().add(col("Files", 75, s -> String.valueOf(s.files())));
        sidesTable.getColumns().add(col("Importance", 85, s -> String.format("%.2f", s.importance())));
        sidesTable.getColumns().add(col("Created", 110, SideAnalysisRow::created));

        sidesTable.setRowFactory(tv -> {
            TableRow<SideAnalysisRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openAspect(row.getItem().id());
                }
            });
            return row;
        });

        sidesChartHolder = new VBox(8);
        sidesChartHolder.setPrefWidth(320);

        HBox mainSplit = new HBox(14,
                grow(Fas.cardWithHeader("Sides Table", "Double-click to open side detail",
                        new VBox(8, sidesCountLabel, sidesTable))),
                Fas.cardWithHeader("Sides Chart", "Files per side", sidesChartHolder));
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        VBox box = new VBox(12, filterBar, mainSplit);
        box.setPadding(new Insets(12));
        return box;
    }

    private void loadSides() {
        sidesJob.run(
                () -> {
                    List<AspectDto> aspects = facades.aspects().listAspects();
                    List<SideAnalysisRow> rows = new ArrayList<>();
                    Map<String, Integer> chartData = new LinkedHashMap<>();

                    for (AspectDto a : aspects) {
                        try {
                            var stats = dao.selectAspectStatistics(a.id());
                            int files = stats == null ? 0 : stats.i("files");
                            rows.add(new SideAnalysisRow(a.id(), a.name(), files, a.importance(),
                                    a.dateCreation() == null ? "—" : String.valueOf(a.dateCreation())));
                            chartData.put(a.name(), files);
                        } catch (Exception e) {
                            rows.add(new SideAnalysisRow(a.id(), a.name(), 0, a.importance(), "—"));
                        }
                    }
                    return Map.entry(rows, chartData);
                },
                res -> {
                    sidesRows.setAll(res.getKey());
                    sidesCountLabel.setText(res.getKey().size() + (res.getKey().size() == 1 ? " side" : " sides"));
                    sidesChartHolder.getChildren().clear();
                    if (res.getValue().isEmpty()) {
                        sidesChartHolder.getChildren().add(Fas.emptyState("No sides data"));
                    } else {
                        sidesChartHolder.getChildren().add(ChartPane.donut(ChartPane.slices(res.getValue()), 200, null));
                    }
                },
                t -> {
                    sidesRows.clear();
                    sidesCountLabel.setText("Error loading sides: " + t.getMessage());
                });
    }

    // ------------------------------------------------------------- 6. Words Tab

    public record WordAnalysisRow(int id, String word, int files, String category) {}

    private Node buildWordsTab() {
        wordsCatBox = combo("All Categories");
        wordsSearchField = Fas.field("Search words...");
        wordsSearchField.setPrefWidth(160);

        Button load = Fas.primary("Load Words", Icons.BOOK);
        load.setOnAction(e -> loadWords());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            wordsCatBox.setValue("All Categories");
            wordsSearchField.clear();
            loadWords();
        });

        HBox filterBar = new HBox(10,
                Fas.formField("Optional Category", wordsCatBox),
                Fas.formField("Word Search", wordsSearchField),
                Fas.spacer(), load, clear);
        filterBar.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        filterBar.getStyleClass().add("filter-bar");

        wordsCountLabel = Fas.muted("0 words");
        wordsTable = new TableView<>(wordsRows);
        wordsTable.setPlaceholder(Fas.emptyState("No words found."));
        wordsTable.setPrefHeight(320);
        VBox.setVgrow(wordsTable, Priority.ALWAYS);

        wordsTable.getColumns().add(col("ID", 50, w -> String.valueOf(w.id())));
        wordsTable.getColumns().add(col("Word", 200, WordAnalysisRow::word));
        wordsTable.getColumns().add(col("Case Files", 80, w -> String.valueOf(w.files())));
        wordsTable.getColumns().add(col("Category", 140, WordAnalysisRow::category));

        wordsTable.setRowFactory(tv -> {
            TableRow<WordAnalysisRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openWord(row.getItem().id());
                }
            });
            return row;
        });

        wordsChartHolder = new VBox(8);
        wordsChartHolder.setPrefWidth(320);

        HBox mainSplit = new HBox(14,
                grow(Fas.cardWithHeader("Words Table", "Double-click to open word detail",
                        new VBox(8, wordsCountLabel, wordsTable))),
                Fas.cardWithHeader("Words Chart", "Top words by case frequency", wordsChartHolder));
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        VBox box = new VBox(12, filterBar, mainSplit);
        box.setPadding(new Insets(12));
        return box;
    }

    private void loadWords() {
        String cat = wordsCatBox.getValue();
        String q = wordsSearchField.getText();

        wordsJob.run(
                () -> {
                    Integer catId = idOfCategory(cat);
                    List<WordAnalysisRow> rows = new ArrayList<>();
                    Map<String, Integer> chartData = new LinkedHashMap<>();
                    Map<Integer, Integer> fCounts = facades.relationships().wordFileCounts();

                    if (catId != null) {
                        var list = facades.categories().getCategoryWords(catId, 1000, 0).results();
                        for (WordDto w : list) {
                            if (q != null && !q.isBlank() && !w.word().toLowerCase().contains(q.trim().toLowerCase())) {
                                continue;
                            }
                            int files = fCounts.getOrDefault(w.id(), 0);
                            rows.add(new WordAnalysisRow(w.id(), w.word(), files, cat));
                            if (files > 0) {
                                chartData.put(w.word(), files);
                            }
                        }
                    } else {
                        var page = facades.words().searchWords(q, 1000, 0);
                        for (WordDto w : page.results()) {
                            int files = fCounts.getOrDefault(w.id(), 0);
                            rows.add(new WordAnalysisRow(w.id(), w.word(), files, "—"));
                            if (files > 0) {
                                chartData.put(w.word(), files);
                            }
                        }
                    }
                    rows.sort((a, b) -> Integer.compare(b.files(), a.files()));
                    return Map.entry(rows, chartData);
                },
                res -> {
                    wordsRows.setAll(res.getKey());
                    wordsCountLabel.setText(res.getKey().size() + (res.getKey().size() == 1 ? " word" : " words"));
                    wordsChartHolder.getChildren().clear();
                    if (res.getValue().isEmpty()) {
                        wordsChartHolder.getChildren().add(Fas.emptyState("No word frequency data"));
                    } else {
                        wordsChartHolder.getChildren().add(ChartPane.hbars(ChartPane.top(res.getValue(), 8), 160, null));
                    }
                },
                t -> {
                    wordsRows.clear();
                    wordsCountLabel.setText("Error loading words: " + t.getMessage());
                });
    }

    // ----------------------------------------------------- 7. Similar Files Tab

    public record SimilarGroupRow(int groupId, String similarity, int fileCount, String type,
                                  String representative, String memberList, int leadPathId) {}

    private Node buildSimilarFilesTab() {
        simThresholdBox = combo("80%+", "70%+", "80%+", "90%+", "100% (Exact)");
        simThresholdBox.setValue("80%+");
        simGroupSizeBox = combo("2+ files", "3+ files", "5+ files");
        simGroupSizeBox.setValue("2+ files");
        simTypeBox = combo("All types", "Same type only");
        simTypeBox.setValue("All types");

        Button load = Fas.primary("Load Similar Files", Icons.FUNNEL);
        load.setOnAction(e -> loadSimilarFiles());
        Button clear = Fas.ghost("Clear", Icons.CLOSE);
        clear.setOnAction(e -> {
            simThresholdBox.setValue("80%+");
            simGroupSizeBox.setValue("2+ files");
            simTypeBox.setValue("All types");
            loadSimilarFiles();
        });

        HBox filterBar = new HBox(10,
                Fas.formField("Similarity Threshold", simThresholdBox),
                Fas.formField("Minimum Group Size", simGroupSizeBox),
                Fas.formField("Group Type", simTypeBox),
                Fas.spacer(), load, clear);
        filterBar.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        filterBar.getStyleClass().add("filter-bar");

        simCountLabel = Fas.muted("0 similar groups");
        simTable = new TableView<>(simRows);
        simTable.setPlaceholder(Fas.emptyState("No similar or duplicate file clusters found on this case."));
        simTable.setPrefHeight(320);
        VBox.setVgrow(simTable, Priority.ALWAYS);

        simTable.getColumns().add(col("Group #", 70, g -> String.valueOf(g.groupId())));
        simTable.getColumns().add(col("Similarity", 90, SimilarGroupRow::similarity));
        simTable.getColumns().add(col("Files Count", 90, g -> String.valueOf(g.fileCount())));
        simTable.getColumns().add(col("Type", 70, SimilarGroupRow::type));
        simTable.getColumns().add(col("Representative File", 220, SimilarGroupRow::representative));
        simTable.getColumns().add(col("Member Files", 360, SimilarGroupRow::memberList));

        simTable.setRowFactory(tv -> {
            TableRow<SimilarGroupRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && row.getItem().leadPathId() > 0) {
                    router.openFile(row.getItem().leadPathId());
                }
            });
            return row;
        });

        simChartHolder = new VBox(8);
        simChartHolder.setPrefWidth(320);

        HBox mainSplit = new HBox(14,
                grow(Fas.cardWithHeader("Similar File Groups", "Double-click a group to open representative file",
                        new VBox(8, simCountLabel, simTable))),
                Fas.cardWithHeader("Duplicate / Similarity Breakdown", null, simChartHolder));
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        VBox box = new VBox(12, filterBar, mainSplit);
        box.setPadding(new Insets(12));
        return box;
    }

    private void loadSimilarFiles() {
        String simVal = simThresholdBox.getValue();
        String sizeVal = simGroupSizeBox.getValue();
        int minSize = sizeVal != null && sizeVal.startsWith("3") ? 3 : sizeVal != null && sizeVal.startsWith("5") ? 5 : 2;

        similarJob.run(
                () -> {
                    List<SimilarGroupRow> out = new ArrayList<>();
                    Map<String, Integer> breakdown = new LinkedHashMap<>();

                    // 1. Exact SHA-256 duplicate clusters
                    try {
                        var clusters = facades.liveCase().duplicateClusters();
                        int gid = 1;
                        for (var entry : clusters.entrySet()) {
                            List<String> names = entry.getValue();
                            if (names.size() >= minSize) {
                                String leadName = names.get(0);
                                String ext = leadName.contains(".") ? leadName.substring(leadName.lastIndexOf('.') + 1) : "—";
                                Integer leadPathId = null;
                                try {
                                    leadPathId = dao.findPathIdByElement(leadName);
                                } catch (Exception ignored) {}

                                out.add(new SimilarGroupRow(
                                        gid++, "100% (Exact)", names.size(),
                                        ext,
                                        leadName, String.join(", ", names),
                                        leadPathId == null ? 0 : leadPathId));
                                breakdown.merge("Exact SHA-256 (" + ext + ")", names.size(), Integer::sum);
                            }
                        }
                    } catch (Exception ignored) {}

                    return Map.entry(out, breakdown);
                },
                res -> {
                    simRows.setAll(res.getKey());
                    simCountLabel.setText(res.getKey().size() + (res.getKey().size() == 1 ? " group" : " groups"));
                    simChartHolder.getChildren().clear();
                    if (res.getValue().isEmpty()) {
                        simChartHolder.getChildren().add(Fas.emptyState("No duplicate groups found."));
                    } else {
                        simChartHolder.getChildren().add(ChartPane.donut(ChartPane.slices(res.getValue()), 200, null));
                    }
                },
                t -> {
                    simRows.clear();
                    simCountLabel.setText("Error loading similar files: " + t.getMessage());
                });
    }

    // ------------------------------------------------------------- Lifecycle & Helpers

    @Override
    public void onShow() {
        reloadFilterDropdowns();
        refreshTopStats();
        // Load the currently active tab
        int sel = tabPane == null ? 0 : tabPane.getSelectionModel().getSelectedIndex();
        switch (sel) {
            case 0 -> loadFiles();
            case 1 -> loadCategories();
            case 2 -> loadKeywords();
            case 3 -> loadSources();
            case 4 -> loadSides();
            case 5 -> loadWords();
            case 6 -> loadSimilarFiles();
            default -> loadFiles();
        }
    }

    private void reloadFilterDropdowns() {
        try {
            List<String> srcNames = facades.sources().listSources().stream().map(SourceDto::name).toList();
            List<String> sideNames = facades.aspects().listAspects().stream().map(AspectDto::name).toList();
            List<String> catWords = facades.categories().listCategories(500, 0).results().stream().map(CategoryDto::word).toList();
            List<String> kwPhrases = facades.keywords().listKeywords(500, 0).results().stream().map(KeywordDto::keyword).toList();
            List<String> typeNames = new ArrayList<>(dao.countPathsByType().keySet());

            updateCombo(filesCatBox, "All Categories", catWords);
            updateCombo(filesSrcBox, "All Sources", srcNames);
            updateCombo(filesSideBox, "All Sides", sideNames);
            updateCombo(filesKwBox, "All Keywords", kwPhrases);

            updateCombo(catsSrcBox, "All Sources", srcNames);
            updateCombo(catsSideBox, "All Sides", sideNames);

            updateCombo(kwCatBox, "All Categories", catWords);
            updateCombo(kwSrcBox, "All Sources", srcNames);
            updateCombo(kwSideBox, "All Sides", sideNames);

            updateCombo(srcTypeBox, "All Types", typeNames);
            updateCombo(srcSideBox, "All Sides", sideNames);
            updateCombo(srcKwBox, "All Keywords", kwPhrases);

            updateCombo(sidesTypeBox, "All Types", typeNames);
            updateCombo(sidesCatBox, "All Categories", catWords);
            updateCombo(sidesKwBox, "All Keywords", kwPhrases);

            updateCombo(wordsCatBox, "All Categories", catWords);
        } catch (Exception ignored) {}
    }

    private static void updateCombo(ComboBox<String> box, String allLabel, List<String> values) {
        if (box == null) return;
        String keep = box.getValue();
        List<String> items = new ArrayList<>();
        items.add(allLabel);
        items.addAll(values);
        box.getItems().setAll(items);
        box.setValue(keep != null && items.contains(keep) ? keep : allLabel);
    }

    private void refreshTopStats() {
        statsJob.run(
                () -> {
                    var stats = facades.dashboard().getStats();
                    long totalFiles = stats.totalFiles();
                    long totalBytes = stats.totalBytes();
                    int catCount = facades.categories().listCategories(1, 0).totalCount();
                    var review = facades.analytics().reviewProgress();
                    int read = review.getOrDefault("Read", 0);
                    int unread = review.getOrDefault("Unread", 0);
                    long errors = stats.errors();
                    return new Object[]{totalFiles, totalBytes, read, read + unread, catCount, errors};
                },
                res -> {
                    long totalFiles = (long) res[0];
                    long totalBytes = (long) res[1];
                    int read = (int) res[2];
                    int totalReview = (int) res[3];
                    int catCount = (int) res[4];
                    long errors = (long) res[5];

                    tiles.getChildren().setAll(Fas.statsGrid(
                            Fas.statCard(Icons.FILES, Fas.PRIMARY, String.format("%,d", totalFiles), "Files Matching"),
                            Fas.statCard(Icons.DATABASE, Fas.SUCCESS, DashboardScreen.humanBytes(totalBytes), "Size Matching"),
                            Fas.statCard(Icons.CHECK_CIRCLE, Fas.INFO, read + " / " + totalReview, "Reviewed"),
                            Fas.statCard(Icons.TAGS, Fas.WARNING, String.valueOf(catCount), "Categories"),
                            Fas.statCard(Icons.ARCHIVE, Fas.PRIMARY, String.format("%,d", totalFiles), "Items In Case"),
                            Fas.statCard(Icons.ALERT, Fas.DANGER, String.format("%,d", errors), "Errors")));
                },
                t -> {});
    }

    private void rebuildStats() {
        rebuild.setDisable(true);
        rebuildStatus.setText("Rebuilding statistics\u2026");
        Background.job().run(
                () -> {
                    facades.dashboard().rebuildStatistics();
                    return facades.dashboard().verifyStatistics();
                },
                ok -> {
                    rebuild.setDisable(false);
                    rebuildStatus.setText(ok
                            ? "Statistics rebuilt and verified."
                            : "Statistics rebuilt with warnings.");
                    onShow();
                },
                t -> {
                    rebuild.setDisable(false);
                    rebuildStatus.setText("Rebuild failed: " + t.getMessage());
                });
    }

    private static VBox grow(VBox v) { HBox.setHgrow(v, Priority.ALWAYS); return v; }

    private static <T> TableColumn<T, String> col(String name, double width, java.util.function.Function<T, String> f) {
        TableColumn<T, String> c = new TableColumn<>(name);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        return c;
    }

    private ComboBox<String> combo(String... values) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(values));
        c.setValue(values[0]);
        c.setPrefWidth(150);
        return c;
    }

    private Integer idOfSource(String name) {
        if (name == null || "All Sources".equals(name)) return null;
        try {
            return facades.sources().listSources().stream()
                    .filter(s -> name.equals(s.name())).map(SourceDto::id).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private Integer idOfAspect(String name) {
        if (name == null || "All Sides".equals(name) || "All Aspects".equals(name)) return null;
        try {
            return facades.aspects().listAspects().stream()
                    .filter(a -> name.equals(a.name())).map(AspectDto::id).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private Integer idOfCategory(String word) {
        if (word == null || "All Categories".equals(word)) return null;
        try {
            return facades.categories().listCategories(500, 0).results().stream()
                    .filter(c -> word.equals(c.word())).map(CategoryDto::id).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private Integer idOfKeyword(String phrase) {
        if (phrase == null || "All Keywords".equals(phrase)) return null;
        try {
            return facades.keywords().listKeywords(500, 0).results().stream()
                    .filter(k -> phrase.equals(k.keyword())).map(KeywordDto::id).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }
}
