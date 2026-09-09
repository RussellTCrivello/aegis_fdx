package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.FileState;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.ChartPane;
import com.aegis.fdx.ui.Detail;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ui.AnalyzeAction;
import com.aegis.fdx.ui.Background;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One registered file in full: content, analysis and metadata.
 *
 * <p>Three tabs separate the three tasks an analyst performs on a file. Content
 * is for reading (in-document search, copy, download, export, reprocess). Analysis
 * shows what the case knows about the file — classification distribution and word
 * frequency computed from the stored extracted text, plus the Categories /
 * Words / Keywords breakdown. Metadata preserves the full record: hashes,
 * dates, attribution, word count and engine status.
 */
public final class FileDetailScreen implements Detail {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern SENTENCE = Pattern.compile("[^.!?]+[.!?]+(\\s+|$)");

    private final AegisFacades facades;
    private final CorpusDatabase dao;
    private final Router router;
    private final AgentService agent;

    private int pathId;
    private PathDto path;
    private String fullText = "";

    private Label heading;
    private Label statusBadgeHolder;
    private GridPane metaDoc;
    private GridPane metaDetails;
    private GridPane metaSecurity;
    private FlowPane categoryChips;
    private FlowPane keywordChips;
    private FlowPane wordChips;
    private Button readToggle;
    private Label engineStatus;
    private Button reprocessBtn;

    // Content tab
    private TextArea contentArea;
    private TextField docSearch;
    private CheckBox caseSensitive;
    private CheckBox wholeWord;
    private Label docMatchLabel;
    private GridPane fileInfoGrid;
    private GridPane quickStatsGrid;

    // Pagination for content
    private int contentPage = 0;
    private static final int PAGE_SIZE = 5000;
    private Label contentPageLabel;
    private Button contentPrev;
    private Button contentNext;

    // Analysis tab
    private VBox classificationBox;
    private VBox freqBox;
    private ComboBox<String> freqType;
    private HBox subTabs;
    private StackPane subViews;
    private VBox catView;
    private VBox wordView;
    private VBox keywordView;
    private Button subCatBtn;
    private Button subWordBtn;
    private Button subKwBtn;

    private final ObservableList<WordFreq> freqRows = FXCollections.observableArrayList();
    private final ObservableList<CatRow> catRows = FXCollections.observableArrayList();
    private final ObservableList<KwRow> kwRows = FXCollections.observableArrayList();

    public FileDetailScreen(AegisFacades facades, CorpusDatabase dao, Router router,
                            AgentService agent) {
        this.facades = facades;
        this.dao = dao;
        this.router = router;
        this.agent = agent;
    }

    @Override public String title() { return "File Detail"; }

    @Override public String breadcrumb() {
        return path == null ? "Home / File Library / Detail"
                : "Home / File Library / " + path.fileName();
    }

    @Override public String icon() { return Icons.FILE_TEXT; }

    @Override public void setRecordId(int id) { this.pathId = id; }

    @Override
    public Node build() {
        heading = new Label();
        heading.getStyleClass().add("section-title");
        statusBadgeHolder = new Label();
        engineStatus = Fas.muted("");

        metaDoc = new GridPane();
        metaDoc.setHgap(20);
        metaDoc.setVgap(8);

        metaDetails = new GridPane();
        metaDetails.setHgap(20);
        metaDetails.setVgap(8);

        metaSecurity = new GridPane();
        metaSecurity.setHgap(20);
        metaSecurity.setVgap(8);

        categoryChips = new FlowPane(6, 6);
        keywordChips = new FlowPane(6, 6);
        wordChips = new FlowPane(6, 6);

        Button back = Fas.outline("Back to Library", null);
        back.setOnAction(e -> router.open("File Library"));

        Button fullTextBtn = Fas.secondary("Full Content", Icons.FILE_TEXT);
        fullTextBtn.setOnAction(e -> router.openContent(pathId));

        readToggle = Fas.primary("Mark Read", Icons.CHECK_CIRCLE);
        readToggle.setOnAction(e -> toggleRead());

        reprocessBtn = Fas.outline("Reprocess File", Icons.REFRESH);
        reprocessBtn.setOnAction(e -> reprocessFile());

        Button analyze = AnalyzeAction.secondaryButton(agent, "Analyze File",
                "Summarise this file and explain what it relates to on this case.",
                () -> {
                    AgentContext c = AgentContext.ofScreen("File Library").withPath(pathId);
                    return path != null && path.elementId() != null
                            ? c.withElement(path.elementId()) : c;
                });

        Button classify = Fas.outline("Classify", Icons.TAGS);
        classify.setOnAction(e -> classify());

        Button openSource = Fas.ghost("Open Source", Icons.BUILDING);
        openSource.setOnAction(e -> {
            if (path != null && path.sourceId() != null) {
                router.openSource(path.sourceId());
            }
        });

        Button openAspect = Fas.ghost("Open Side", Icons.DIAGRAM3);
        openAspect.setOnAction(e -> {
            if (path != null && path.aspectId() != null) {
                router.openAspect(path.aspectId());
            }
        });

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Content", buildContentTab()),
                new Tab("Analysis", buildAnalysisTab()),
                new Tab("Metadata", buildMetadataTab()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox content = new VBox(14,
                Fas.pageHeader("File Detail", null, analyze, back, fullTextBtn, reprocessBtn, classify, readToggle),
                new HBox(10, heading, statusBadgeHolder, Fas.spacer(),
                        openSource, openAspect),
                engineStatus, tabs);
        content.setPadding(new Insets(20));
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    // ---- Content tab ----------------------------------------------------

    private Node buildContentTab() {
        contentArea = new TextArea();
        contentArea.setEditable(false);
        contentArea.setWrapText(true);
        contentArea.setPromptText("No extracted text stored for this file.");
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        docSearch = Fas.field("Search in document...");
        docSearch.setPrefWidth(220);
        docSearch.setOnAction(e -> findInDocument());
        caseSensitive = new CheckBox("Case-sensitive");
        wholeWord = new CheckBox("Whole word");
        docMatchLabel = Fas.muted("");

        Button find = Fas.outline("Find", Icons.SEARCH);
        find.setOnAction(e -> findInDocument());
        Button clearSearch = Fas.ghost("Clear", Icons.CLOSE);
        clearSearch.setOnAction(e -> {
            docSearch.clear();
            docMatchLabel.setText("");
            contentArea.deselect();
        });

        Button copy = Fas.outline("Copy", Icons.CLIPBOARD);
        copy.setOnAction(e -> {
            Fas.copyText(fullText);
            docMatchLabel.setText("Copied " + fullText.length() + " characters");
        });
        Button download = Fas.outline("Download", Icons.DOWNLOAD);
        download.setOnAction(e -> Fas.saveBytes(contentArea, "Download Content",
                safeName() + ".txt", fullText.getBytes(StandardCharsets.UTF_8)));

        Button viewFull = Fas.outline("View Full Content", Icons.FILE_TEXT);
        viewFull.setOnAction(e -> router.openContent(pathId));

        contentPageLabel = Fas.muted("Page 1 of 1");
        contentPrev = Fas.outline("Previous", null);
        contentPrev.setOnAction(e -> changeContentPage(-1));
        contentNext = Fas.outline("Next", null);
        contentNext.setOnAction(e -> changeContentPage(1));

        HBox pgnRow = new HBox(8, contentPageLabel, contentPrev, contentNext, Fas.spacer(), viewFull);
        pgnRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox leftViewer = new VBox(10,
                Fas.row(8, docSearch, find, clearSearch, caseSensitive, wholeWord,
                        docMatchLabel, Fas.spacer(), copy, download),
                contentArea,
                pgnRow);
        HBox.setHgrow(leftViewer, Priority.ALWAYS);

        fileInfoGrid = new GridPane();
        fileInfoGrid.setHgap(14);
        fileInfoGrid.setVgap(6);

        quickStatsGrid = new GridPane();
        quickStatsGrid.setHgap(14);
        quickStatsGrid.setVgap(6);

        VBox infoCard = Fas.cardWithHeader("File Information", null, fileInfoGrid);
        VBox statsCard = Fas.cardWithHeader("Quick Stats", null, quickStatsGrid);
        VBox rightSide = new VBox(12, infoCard, statsCard);
        rightSide.setPrefWidth(300);

        HBox split = new HBox(14, leftViewer, rightSide);
        split.setPadding(new Insets(14));
        VBox.setVgrow(split, Priority.ALWAYS);
        return split;
    }

    private void changeContentPage(int delta) {
        if (fullText == null || fullText.isEmpty()) {
            contentArea.setText("");
            contentPageLabel.setText("Page 1 of 1");
            return;
        }
        int totalPages = Math.max(1, (int) Math.ceil(fullText.length() / (double) PAGE_SIZE));
        contentPage = Math.max(0, Math.min(contentPage + delta, totalPages - 1));
        int start = contentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, fullText.length());
        contentArea.setText(fullText.substring(start, end));
        contentPageLabel.setText("Page " + (contentPage + 1) + " of " + totalPages);
        contentPrev.setDisable(contentPage == 0);
        contentNext.setDisable(contentPage >= totalPages - 1);
    }

    private void findInDocument() {
        String needle = docSearch.getText();
        if (needle == null || needle.isBlank() || fullText.isBlank()) {
            docMatchLabel.setText("");
            return;
        }
        String hay = caseSensitive.isSelected() ? fullText : fullText.toLowerCase();
        String n = caseSensitive.isSelected() ? needle : needle.toLowerCase();
        int count = 0;
        int idx = hay.indexOf(n);
        int first = idx;
        while (idx >= 0) {
            if (!wholeWord.isSelected() || isWholeWord(hay, idx, n.length())) {
                count++;
            }
            idx = hay.indexOf(n, idx + n.length());
        }
        if (count == 0) {
            docMatchLabel.setText("No matches");
            return;
        }
        docMatchLabel.setText(count + (count == 1 ? " match" : " matches"));
        if (first >= 0) {
            int targetPage = first / PAGE_SIZE;
            if (targetPage != contentPage) {
                contentPage = targetPage;
                changeContentPage(0);
            }
            int offsetInPage = first % PAGE_SIZE;
            contentArea.selectRange(offsetInPage, offsetInPage + needle.length());
            contentArea.requestFocus();
        }
    }

    private static boolean isWholeWord(String hay, int idx, int len) {
        boolean leftOk = idx == 0 || !Character.isLetterOrDigit(hay.charAt(idx - 1));
        int end = idx + len;
        boolean rightOk = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end));
        return leftOk && rightOk;
    }

    private String safeName() {
        if (path == null || path.fileName() == null) {
            return "content";
        }
        return path.fileName().replaceAll("[^A-Za-z0-9._\\-]+", "_");
    }

    private void reprocessFile() {
        if (path == null) return;
        reprocessBtn.setDisable(true);
        engineStatus.setText("Reprocessing file...");
        Background.job().run(
                () -> {
                    if (path.elementId() != null) {
                        return facades.processing().retryFile(path.elementId());
                    }
                    return null;
                },
                res -> {
                    reprocessBtn.setDisable(false);
                    if (res != null && res.success()) {
                        engineStatus.setText("Reprocessing completed successfully.");
                    } else if (res != null) {
                        engineStatus.setText("Reprocessing result: " + res.error());
                    } else {
                        engineStatus.setText("No engine element found for direct retry.");
                    }
                    onShow();
                },
                t -> {
                    reprocessBtn.setDisable(false);
                    engineStatus.setText("Reprocess error: " + t.getMessage());
                });
    }

    // ---- Analysis tab ---------------------------------------------------

    private Node buildAnalysisTab() {
        classificationBox = new VBox(8);
        freqBox = new VBox(8);
        freqType = new ComboBox<>(FXCollections.observableArrayList("Bar", "Pie"));
        freqType.setValue("Bar");
        freqType.setPrefWidth(110);
        freqType.setOnAction(e -> renderFrequency());

        VBox left = Fas.cardWithHeader("Classification Analysis",
                "Categories this file is linked to", classificationBox);
        VBox right = Fas.cardWithHeader("Word Frequency", null,
                new VBox(10, Fas.row(8, Fas.fieldLabel("Chart Type"), freqType), freqBox));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox top = new HBox(14, left, right);

        subCatBtn = Fas.subTab("Categories", true);
        subWordBtn = Fas.subTab("Words", false);
        subKwBtn = Fas.subTab("Top Keywords", false);
        subCatBtn.setOnAction(e -> showSub(0));
        subWordBtn.setOnAction(e -> showSub(1));
        subKwBtn.setOnAction(e -> showSub(2));
        subTabs = new HBox(6, subCatBtn, subWordBtn, subKwBtn);

        catView = buildCatView();
        wordView = buildWordView();
        keywordView = buildKeywordView();
        subViews = new StackPane(catView, wordView, keywordView);
        catView.setVisible(true);
        wordView.setVisible(false);
        keywordView.setVisible(false);

        VBox bottom = Fas.cardWithHeader("Classification & Analysis",
                "What the stored text reveals about this file",
                new VBox(12, subTabs, subViews));

        VBox box = new VBox(14, top, bottom);
        box.setPadding(new Insets(14));
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        return sp;
    }

    private VBox buildCatView() {
        TableView<CatRow> table = new TableView<>(catRows);
        table.setPlaceholder(Fas.emptyState("No data available"));
        table.setPrefHeight(220);
        TableColumn<CatRow, String> c1 = new TableColumn<>("Category");
        c1.setPrefWidth(200);
        c1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        TableColumn<CatRow, String> c2 = new TableColumn<>("Hits");
        c2.setPrefWidth(90);
        c2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().hits())));
        TableColumn<CatRow, String> c3 = new TableColumn<>("Share");
        c3.setPrefWidth(90);
        c3.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.format("%.1f%%", c.getValue().share() * 100)));
        table.getColumns().addAll(c1, c2, c3);
        Label tableLabel = Fas.muted("Data Table (0)");
        tableLabel.setId("catTableLabel");
        VBox chartHolder = new VBox(8);
        chartHolder.setId("catChart");
        VBox tableBox = new VBox(8, tableLabel, table);
        HBox split = new HBox(14, chartHolder, tableBox);
        HBox.setHgrow(chartHolder, Priority.ALWAYS);
        HBox.setHgrow(tableBox, Priority.ALWAYS);
        VBox out = new VBox(10, split);
        out.setUserData(new Object[]{chartHolder, tableLabel});
        return out;
    }

    private VBox buildWordView() {
        TableView<WordFreq> table = new TableView<>(freqRows);
        table.setPlaceholder(Fas.emptyState("No words counted yet."));
        table.setPrefHeight(260);
        TableColumn<WordFreq, String> c1 = new TableColumn<>("Word");
        c1.setPrefWidth(220);
        c1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().word()));
        TableColumn<WordFreq, String> c2 = new TableColumn<>("Frequency");
        c2.setPrefWidth(100);
        c2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().count())));
        table.getColumns().addAll(c1, c2);
        return new VBox(10, Fas.muted("Top words by frequency in the stored text"), table);
    }

    private VBox buildKeywordView() {
        TableView<KwRow> table = new TableView<>(kwRows);
        table.setPlaceholder(Fas.emptyState("No keyword phrase occurs in this file"));
        table.setPrefHeight(260);
        TableColumn<KwRow, String> c1 = new TableColumn<>("Keyword");
        c1.setPrefWidth(240);
        c1.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().phrase()));
        TableColumn<KwRow, String> c2 = new TableColumn<>("Hits in File");
        c2.setPrefWidth(100);
        c2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().hits())));
        TableColumn<KwRow, String> c3 = new TableColumn<>("Files (case)");
        c3.setPrefWidth(100);
        c3.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().files())));
        table.getColumns().addAll(c1, c2, c3);
        table.setRowFactory(t -> {
            TableRow<KwRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    router.openKeyword(row.getItem().id());
                }
            });
            return row;
        });
        return new VBox(10, Fas.muted("Keyword phrases found in this file — double-click to open"), table);
    }

    private void showSub(int idx) {
        catView.setVisible(idx == 0);
        catView.setManaged(idx == 0);
        wordView.setVisible(idx == 1);
        wordView.setManaged(idx == 1);
        keywordView.setVisible(idx == 2);
        keywordView.setManaged(idx == 2);
        styleSub(subCatBtn, idx == 0);
        styleSub(subWordBtn, idx == 1);
        styleSub(subKwBtn, idx == 2);
    }

    private static void styleSub(Button b, boolean active) {
        b.getStyleClass().remove("active");
        if (active) {
            b.getStyleClass().add("active");
        }
    }

    // ---- Metadata tab ---------------------------------------------------

    private Node buildMetadataTab() {
        VBox docCard = Fas.cardWithHeader("Document Metadata", "Origin, dates and taxonomy", metaDoc);
        VBox techCard = Fas.cardWithHeader("File Details", "Engine attributes and filesystem properties", metaDetails);
        VBox secCard = Fas.cardWithHeader("Security & Hash", "Cryptographic hashes and evidence identity", metaSecurity);

        VBox relCard = Fas.cardWithHeader("Relationships",
                "Keywords, categories and category words this file is related to; click to open",
                new VBox(12,
                        Fas.fieldLabel("KEYWORDS"), keywordChips,
                        Fas.fieldLabel("CATEGORIES"), categoryChips,
                        Fas.fieldLabel("CATEGORY WORDS"), wordChips));

        HBox left = new HBox(14, grow(docCard), grow(techCard));
        HBox right = new HBox(14, grow(secCard), grow(relCard));

        VBox box = new VBox(14, left, right);
        box.setPadding(new Insets(14));
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox grow(VBox v) { HBox.setHgrow(v, Priority.ALWAYS); return v; }

    // ---- load -----------------------------------------------------------

    @Override
    public void onShow() {
        if (pathId <= 0) {
            heading.setText("No file selected");
            return;
        }
        try {
            path = facades.contents().getPath(pathId);
            heading.setText(path.fileName());

            boolean read = FileState.READ.label().equals(path.fileStatus());
            statusBadgeHolder.setGraphic(
                    Fas.badge(path.fileStatus(), read ? "success" : "muted"));
            readToggle.setText(read ? "Mark Unread" : "Mark Read");

            fullText = facades.contents().getContentAsText(pathId);
            if (fullText == null) {
                fullText = "";
            }

            contentPage = 0;
            changeContentPage(0);
            docMatchLabel.setText("");

            com.aegis.fdx.model.Item item = null;
            engineStatus.setText("");
            if (path.elementId() != null) {
                try {
                    item = facades.liveCase().byId(path.elementId());
                } catch (Exception ignored) {
                }
            }

            int words = fullText.isBlank() ? 0 : countWords(fullText);
            int sentences = fullText.isBlank() ? 0 : countSentences(fullText);
            int paragraphs = fullText.isBlank() ? 0 : countParagraphs(fullText);
            int chars = fullText.length();

            // Populate Quick Stats in Content Tab
            quickStatsGrid.getChildren().clear();
            addGridRow(quickStatsGrid, 0, "Word Count", String.format("%,d", words));
            addGridRow(quickStatsGrid, 1, "Sentence Count", String.format("%,d", sentences));
            addGridRow(quickStatsGrid, 2, "Paragraph Count", String.format("%,d", paragraphs));
            addGridRow(quickStatsGrid, 3, "Character Count", String.format("%,d", chars));

            // Populate File Information in Content Tab
            fileInfoGrid.getChildren().clear();
            addGridRow(fileInfoGrid, 0, "Type", path.fileType());
            addGridRow(fileInfoGrid, 1, "Size", DashboardScreen.humanBytes(path.fileSize()));
            addGridRow(fileInfoGrid, 2, "Status", path.fileStatus());
            addGridRow(fileInfoGrid, 3, "Source", path.sourceName());
            addGridRow(fileInfoGrid, 4, "Side", path.aspectName());
            addGridRow(fileInfoGrid, 5, "Date", String.valueOf(path.fileDate()));

            // Populate Metadata Grids
            metaDoc.getChildren().clear();
            int r1 = 0;
            r1 = addGridRow(metaDoc, r1, "File Name", path.fileName());
            r1 = addGridRow(metaDoc, r1, "File Type", path.fileType());
            r1 = addGridRow(metaDoc, r1, "Source", path.sourceName());
            r1 = addGridRow(metaDoc, r1, "Side", path.aspectName());
            r1 = addGridRow(metaDoc, r1, "File Date", String.valueOf(path.fileDate()));
            r1 = addGridRow(metaDoc, r1, "Date Created", String.valueOf(path.dateCreation()));
            r1 = addGridRow(metaDoc, r1, "Notes", path.coordinates() != null ? path.coordinates() : "—");
            addGridRow(metaDoc, r1, "Status", path.fileStatus());

            metaDetails.getChildren().clear();
            int r2 = 0;
            r2 = addGridRow(metaDetails, r2, "ID", String.valueOf(path.id()));
            r2 = addGridRow(metaDetails, r2, "Path", path.filePath());
            r2 = addGridRow(metaDetails, r2, "Size", DashboardScreen.humanBytes(path.fileSize()) + " (" + String.format("%,d", path.fileSize()) + " bytes)");
            r2 = addGridRow(metaDetails, r2, "Element ID", path.elementId() != null ? path.elementId() : "—");
            r2 = addGridRow(metaDetails, r2, "MIME Type", item == null ? "—" : item.mediaType());
            r2 = addGridRow(metaDetails, r2, "OCR Status", item == null ? "—" : item.ocrApplied() ? "Applied" : item.needsOcr() ? "Candidate" : "Not needed");
            addGridRow(metaDetails, r2, "Processing Status", item == null ? String.valueOf(path.fileStatus()) : String.valueOf(item.status()));

            metaSecurity.getChildren().clear();
            int r3 = 0;
            String sha = path.hashValue() != null ? path.hashValue() : item != null ? item.sha256() : null;
            String md5 = item != null ? item.md5() : null;
            r3 = addGridRow(metaSecurity, r3, "SHA-256", sha != null ? sha : "—");
            r3 = addGridRow(metaSecurity, r3, "MD5", md5 != null ? md5 : "—");
            addGridRow(metaSecurity, r3, "Hash ID", path.hashValue() != null ? path.hashValue() : "—");

            if (item != null && item.errors() != null && !item.errors().isEmpty()) {
                engineStatus.setText("Processing errors: " + String.join("; ", item.errors()));
            }

            // Relationships chips
            var rel = facades.relationships().forFile(pathId);
            keywordChips.getChildren().clear();
            kwRows.clear();
            for (var k : rel.keywords()) {
                int hits = 0;
                for (var prow : dao.selectPathKeywords(pathId)) {
                    if (prow.i("id") == k.id()) {
                        hits = prow.i("hits");
                    }
                }
                Button chip = Fas.ghost(k.text() + (hits > 0 ? " \u00d7" + hits : "")
                        + "  (" + k.fileCount() + " files)", Icons.KEY);
                final int kid = k.id();
                chip.setOnAction(e -> router.openKeyword(kid));
                keywordChips.getChildren().add(chip);
                kwRows.add(new KwRow(k.id(), k.text(), hits, k.fileCount()));
            }
            if (keywordChips.getChildren().isEmpty()) {
                keywordChips.getChildren().add(Fas.muted("No keyword phrase occurs in this file"));
            }

            categoryChips.getChildren().clear();
            for (var c : rel.categories()) {
                Button chip = Fas.ghost(c.text() + "  (" + c.fileCount() + " files)", Icons.TAGS);
                final int cid = c.id();
                chip.setOnAction(e -> router.openCategoryDetail(cid));
                categoryChips.getChildren().add(chip);
            }
            if (categoryChips.getChildren().isEmpty()) {
                categoryChips.getChildren().add(Fas.muted("None applied or reached"));
            }

            wordChips.getChildren().clear();
            for (var w : rel.categoryWords()) {
                Button chip = Fas.ghost(w.text() + "  (" + w.fileCount() + " files)", Icons.BOOK);
                final int wid = w.id();
                chip.setOnAction(e -> router.openWord(wid));
                wordChips.getChildren().add(chip);
            }
            if (wordChips.getChildren().isEmpty()) {
                wordChips.getChildren().add(Fas.muted("No category word occurs in this file"));
            }

            // Word frequency from stored text
            List<WordFreq> freq = wordFrequency(fullText, 20);
            freqRows.setAll(freq);
            renderFrequency();

            // Classification: keyword hits grouped by category for this file
            renderClassification(rel);
        } catch (Exception e) {
            heading.setText("Could not load this file");
            engineStatus.setText(String.valueOf(e.getMessage()));
        }
    }

    private void renderFrequency() {
        freqBox.getChildren().clear();
        if (freqRows.isEmpty()) {
            freqBox.getChildren().add(Fas.emptyState("No data available"));
            return;
        }
        Map<String, Integer> data = new LinkedHashMap<>();
        for (WordFreq w : freqRows) {
            data.put(w.word(), w.count());
        }
        List<ChartPane.Slice> slices = ChartPane.top(data, 12);
        if ("Pie".equals(freqType.getValue())) {
            freqBox.getChildren().add(ChartPane.donut(slices, 220, null));
        } else {
            freqBox.getChildren().add(ChartPane.bars(slices, 420, 240, null));
        }
    }

    private void renderClassification(com.aegis.fdx.facade.dto.FileRelationships rel) {
        classificationBox.getChildren().clear();
        Map<String, Integer> byCat = new LinkedHashMap<>();
        Map<String, Integer> catIds = new LinkedHashMap<>();
        try {
            for (var prow : dao.selectPathKeywords(pathId)) {
                int kid = prow.i("id");
                int hits = prow.i("hits");
                try {
                    var kw = facades.keywords().getKeyword(kid);
                    String cat = kw.categoryWord() == null ? "(uncategorised)" : kw.categoryWord();
                    byCat.merge(cat, Math.max(1, hits), Integer::sum);
                    catIds.putIfAbsent(cat, kw.categoryId());
                } catch (RuntimeException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        if (byCat.isEmpty()) {
            for (var c : rel.categories()) {
                byCat.put(c.text(), 1);
                catIds.put(c.text(), c.id());
            }
        }
        if (byCat.isEmpty()) {
            classificationBox.getChildren().add(Fas.emptyState("No data available"));
        } else {
            List<ChartPane.Slice> slices = ChartPane.top(byCat, 8);
            classificationBox.getChildren().add(ChartPane.hbars(slices, 180, label -> {
                Integer id = catIds.get(label);
                if (id != null) {
                    router.openCategoryDetail(id);
                }
            }));
        }

        catRows.clear();
        int total = byCat.values().stream().mapToInt(Integer::intValue).sum();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(byCat.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : entries) {
            double share = total == 0 ? 0 : e.getValue() / (double) total;
            catRows.add(new CatRow(e.getKey(), e.getValue(), share));
        }
        Object[] ud = (Object[]) catView.getUserData();
        VBox chartHolder = (VBox) ud[0];
        Label tableLabel = (Label) ud[1];
        chartHolder.getChildren().clear();
        tableLabel.setText("Data Table (" + catRows.size() + ")");
        if (byCat.isEmpty()) {
            chartHolder.getChildren().add(Fas.emptyState("No data available"));
        } else {
            chartHolder.getChildren().add(ChartPane.donut(ChartPane.top(byCat, 20), 230, label -> {
                Integer id = catIds.get(label);
                if (id != null) {
                    router.openCategoryDetail(id);
                }
            }));
        }
    }

    private static List<WordFreq> wordFrequency(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher m = WORD.matcher(text.toLowerCase());
        while (m.find()) {
            String w = m.group();
            if (w.length() < 2) {
                continue;
            }
            counts.merge(w, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<WordFreq> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            out.add(new WordFreq(entries.get(i).getKey(), entries.get(i).getValue()));
        }
        return out;
    }

    private static int countWords(String text) {
        Matcher m = WORD.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static int countSentences(String text) {
        if (text == null || text.isBlank()) return 0;
        Matcher m = SENTENCE.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static int countParagraphs(String text) {
        if (text == null || text.isBlank()) return 0;
        String[] parts = text.split("(\\r?\\n){2,}");
        int count = 0;
        for (String p : parts) {
            if (!p.isBlank()) count++;
        }
        return Math.max(1, count);
    }

    private int addGridRow(GridPane g, int r, String label, String value) {
        g.add(Fas.fieldLabel(label), 0, r);
        Label v = new Label(value == null || value.isBlank() ? "\u2014" : value);
        v.setWrapText(true);
        v.setMaxWidth(380);
        v.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Fas.TEXT_DARK + ";");
        g.add(v, 1, r);
        return r + 1;
    }

    private void toggleRead() {
        if (path == null) return;
        boolean read = FileState.READ.label().equals(path.fileStatus());
        facades.contents().setPathStatus(pathId,
                read ? FileState.UNREAD.label() : FileState.READ.label());
        onShow();
    }

    /** Applies a category to this file, persisting the link. */
    private void classify() {
        if (path == null) return;
        try {
            List<CategoryDto> cats = facades.categories().listCategories(500, 0).results();
            if (cats.isEmpty()) {
                err("Create a category first, on the Categories destination.");
                return;
            }
            List<String> names = new ArrayList<>();
            for (CategoryDto c : cats) {
                names.add(c.word());
            }
            ChoiceDialog<String> d = new ChoiceDialog<>(names.get(0), names);
            d.setTitle("Classify File");
            d.setHeaderText("Apply a category to " + path.fileName());
            d.setContentText("Category:");
            d.showAndWait().ifPresent(chosen -> {
                for (CategoryDto c : cats) {
                    if (c.word().equals(chosen)) {
                        try {
                            dao.linkPathToCategory(pathId, c.id());
                            onShow();
                        } catch (Exception ex) {
                            err("Could not classify: " + ex.getMessage());
                        }
                        return;
                    }
                }
            });
        } catch (FacadeException e) {
            err(e.getMessage());
        }
    }

    private static void err(String m) {
        Alert a = new Alert(Alert.AlertType.ERROR, m, ButtonType.OK);
        a.setHeaderText("An error occurred");
        a.showAndWait();
    }

    public record WordFreq(String word, int count) {}
    public record CatRow(String name, int hits, double share) {}
    public record KwRow(int id, String phrase, int hits, int files) {}
}
