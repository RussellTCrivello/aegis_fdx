package com.aegis.fdx.ui;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.store.CorpusDatabase;
import com.aegis.fdx.ui.screens.AdvancedSearchScreen;
import com.aegis.fdx.ui.screens.AgentScreen;
import com.aegis.fdx.ui.screens.ArchivesScreen;
import com.aegis.fdx.ui.screens.BatchAnalysisScreen;
import com.aegis.fdx.ui.screens.AspectDetailScreen;
import com.aegis.fdx.ui.screens.ChartsDashboardScreen;
import com.aegis.fdx.ui.screens.ComprehensiveDashboardScreen;
import com.aegis.fdx.ui.screens.ErrorDashboardScreen;
import com.aegis.fdx.ui.screens.FileDetailScreen;
import com.aegis.fdx.ui.screens.FullContentScreen;
import com.aegis.fdx.ui.screens.PathAnalysisScreen;
import com.aegis.fdx.ui.screens.PerformanceScreen;
import com.aegis.fdx.ui.screens.ProcessingMonitorScreen;
import com.aegis.fdx.ui.screens.RelationshipsScreen;
import com.aegis.fdx.ui.screens.SetupScreen;
import com.aegis.fdx.ui.screens.SourceDetailScreen;
import com.aegis.fdx.ui.screens.TermDetailScreen;
import com.aegis.fdx.ui.screens.AnalysisScreen;
import com.aegis.fdx.ui.screens.CategoriesScreen;
import com.aegis.fdx.ui.screens.DashboardScreen;
import com.aegis.fdx.ui.screens.EmailWordsScreen;
import com.aegis.fdx.ui.screens.FileLibraryScreen;
import com.aegis.fdx.ui.screens.ImportExportScreen;
import com.aegis.fdx.ui.screens.KeywordsScreen;
import com.aegis.fdx.ui.screens.NotificationsScreen;
import com.aegis.fdx.ui.screens.SavedSearchesScreen;
import com.aegis.fdx.ui.screens.SearchScreen;
import com.aegis.fdx.ui.screens.SettingsScreen;
import com.aegis.fdx.ui.screens.AspectsScreen;
import com.aegis.fdx.ui.screens.SourcesScreen;
import com.aegis.fdx.ui.screens.UploadScreen;
import com.aegis.fdx.ui.screens.WordsScreen;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File Analysis System — main window.
 *
 * <p>Front-end parity with the Python reference project: the fixed 260px dark sidebar,
 * its exact 13 navigation entries in the Python order, the white topbar with page title
 * and breadcrumb, and the light content canvas. Each Python route/template becomes a
 * {@link Screen}.
 *
 * <p>The back end is untouched. Every screen reaches the engine through
 * {@link AegisFacades}, which adapts to the existing {@link LiveCase} pipeline —
 * reading, extraction, hashing, OCR, indexing and metadata all remain exactly as they
 * were.
 *
 * <p>The original {@code AegisApp} forensic UI is preserved and still launchable, so
 * neither interface breaks the other.
 */
public final class FasApp extends Application implements Router {

    private final CaseSettings settings = new CaseSettings();
    private LiveCase liveCase;
    private AegisFacades facades;
    private AgentService agent;

    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private final Map<String, Node> built = new LinkedHashMap<>();
    private final List<Button> navButtons = new ArrayList<>();

    private StackPane contentArea;
    private Label pageTitle;
    private Label breadcrumb;
    private Label statusLeft;
    private Label statusRight;
    private String currentKey;
    private final java.util.Deque<String> historyStack = new java.util.ArrayDeque<>();
    private CorpusDatabase corpusDao;
    private Path settingsFile;

    @Override
    public void start(Stage stage) throws Exception {
        settings.ocrEnabled(false);

        Path root = Path.of(System.getProperty("user.home"), ".file-analysis", "workspace");
        Files.createDirectories(root);
        liveCase = new LiveCase(root, "workspace", settings);
        // Settings belong to the case: anything the operator changed last time is
        // reapplied here, before any processing can read them.
        settingsFile = liveCase.folder().root().resolve(CaseSettings.FILE_NAME);
        settings.loadFrom(settingsFile);
        facades = AegisFacades.open(liveCase);
        corpusDao = new CorpusDatabase(liveCase.db());
        agent = AgentService.fromEnvironment(facades, corpusDao);

        // If the case had to be repaired on the way in, that is a fact about the
        // evidence and belongs in the case, not in a dialog nobody reads twice.
        String repair = liveCase.indexRepair();
        if (repair != null) {
            try {
                facades.notifications().createNotification("case_repair", "high",
                        "Search index rebuilt", repair, null, null);
            } catch (Exception ignored) {
                // A notification that cannot be stored must not stop the case opening.
            }
        }

        register(new DashboardScreen(facades));
        register(new AnalysisScreen(facades));
        register(new SearchScreen(facades, agent));
        register(new SourcesScreen(facades));
        register(new AspectsScreen(facades));
        register(new EmailWordsScreen(facades));
        register(new KeywordsScreen(facades, agent));
        register(new WordsScreen(facades));
        register(new CategoriesScreen(facades, agent));
        register(new UploadScreen(facades));
        register(new FileLibraryScreen(facades));
        register(new NotificationsScreen(facades));
        register(new SettingsScreen(facades, settings, settingsFile));
        register(new AgentScreen(facades, agent));

        // analytics destinations
        register(new ChartsDashboardScreen(facades, corpusDao, this));
        register(new ComprehensiveDashboardScreen(facades, corpusDao, this));
        register(new BatchAnalysisScreen(facades, this));
        register(new PathAnalysisScreen(facades, this));
        register(new ArchivesScreen(facades, this));
        register(new AdvancedSearchScreen(facades, this));

        // system destinations
        register(new SetupScreen(facades, corpusDao, this));
        register(new ErrorDashboardScreen(facades, this));
        register(new PerformanceScreen(facades, corpusDao, this));
        register(new ProcessingMonitorScreen(facades, this));

        // detail destinations, reached by drilling through rather than the sidebar
        register(new SourceDetailScreen(facades, this, agent));
        register(new AspectDetailScreen(facades, this, agent));
        register(new RelationshipsScreen(facades, this, true));
        register(new RelationshipsScreen(facades, this, false));
        register(new TermDetailScreen(facades, this, TermDetailScreen.Kind.WORD, agent));
        register(new TermDetailScreen(facades, this, TermDetailScreen.Kind.KEYWORD, agent));
        register(new FileDetailScreen(facades, corpusDao, this, agent));
        register(new FullContentScreen(facades, this));
        // secondary screens, reachable from their parent pages
        register(new SavedSearchesScreen(facades));
        register(new ImportExportScreen(facades));

        // Python layout: the 260px sidebar is full height on the left, and the
        // topbar/content/statusbar form the column beside it. A single BorderPane with
        // top spanning the full width would push the sidebar down, which is not the
        // reference layout.
        BorderPane main = new BorderPane();
        main.setTop(buildTopbar());
        main.setCenter(buildContent());
        main.setBottom(buildStatusBar());

        BorderPane rootPane = new BorderPane();
        rootPane.setLeft(buildSidebar());
        rootPane.setCenter(main);

        Scene scene = new Scene(rootPane, 1440, 900);
        var css = FasApp.class.getResource("/com/aegis/fdx/ui/fas.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        stage.setTitle("File Analysis System");
        stage.setScene(scene);
        stage.setMinWidth(1120);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        navigate("Dashboard");
    }

    private void register(Screen s) {
        screens.put(s.title(), s);
    }

    // ---- sidebar (Python .sidebar) --------------------------------------

    private Node buildSidebar() {
        Label brand = new Label("File Analysis");
        brand.getStyleClass().add("sidebar-logo-title");
        Label sub = new Label("Document Intelligence");
        sub.getStyleClass().add("sidebar-logo-sub");

        HBox logoRow = new HBox(10,
                Icons.box(Icons.SHIELD, "#4f46e5", 22),
                new VBox(1, brand, sub));
        logoRow.setAlignment(Pos.CENTER_LEFT);
        logoRow.getStyleClass().add("sidebar-logo");

        VBox nav = new VBox(3);
        nav.getChildren().add(sectionLabel("OVERVIEW"));
        nav.getChildren().add(navLink("Dashboard"));
        nav.getChildren().add(navLink("Analysis"));
        nav.getChildren().add(navLink("Charts"));
        nav.getChildren().add(navLink("Comprehensive"));
        nav.getChildren().add(navLink("Path Analysis"));
        nav.getChildren().add(navLink("Batch Analysis"));
        nav.getChildren().add(navLink("Search"));
        nav.getChildren().add(navLink("Advanced Search"));

        nav.getChildren().add(sectionLabel("ENTITIES"));
        nav.getChildren().add(navLink("Sources"));
        nav.getChildren().add(navLink("Aspects"));
        nav.getChildren().add(navLink("Email Words"));
        nav.getChildren().add(navLink("Keywords"));
        nav.getChildren().add(navLink("Words"));
        nav.getChildren().add(navLink("Categories"));

        nav.getChildren().add(sectionLabel("FILES"));
        nav.getChildren().add(navLink("Upload Files"));
        nav.getChildren().add(navLink("File Library"));
        nav.getChildren().add(navLink("Archives"));
        nav.getChildren().add(navLink("Import / Export"));

        nav.getChildren().add(sectionLabel("ASSISTANT"));
        nav.getChildren().add(navLink("Assistant"));

        nav.getChildren().add(sectionLabel("SYSTEM"));
        nav.getChildren().add(navLink("Saved Searches"));
        nav.getChildren().add(navLink("Notifications"));
        nav.getChildren().add(navLink("Processing"));
        nav.getChildren().add(navLink("Errors"));
        nav.getChildren().add(navLink("Performance"));
        nav.getChildren().add(navLink("Setup"));
        nav.getChildren().add(navLink("Settings"));

        ScrollPane sp = new ScrollPane(nav);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(sp, Priority.ALWAYS);

        Label version = new Label("Version 1.0.0  \u2022  Offline");
        version.getStyleClass().add("sidebar-footer");

        VBox side = new VBox(6, logoRow, sp, version);
        side.getStyleClass().add("sidebar");
        return side;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        return l;
    }

    private Button navLink(String key) {
        Screen s = screens.get(key);
        if (s instanceof Detail d && !d.showInNavigation()) {
            return new Button();   // never added; guards against a mis-typed key
        }
        Button b = new Button(key);
        b.getStyleClass().add("sidebar-nav-link");
        b.setGraphic(Icons.box(s == null ? Icons.LIST : s.icon(), "#e2e8f0", 15));
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setOnAction(e -> navigate(key));
        b.setUserData(key);
        navButtons.add(b);
        return b;
    }

    // ---- topbar ---------------------------------------------------------

    private Node buildTopbar() {
        pageTitle = new Label("Dashboard");
        pageTitle.getStyleClass().add("page-title");
        breadcrumb = new Label("Home / Dashboard");
        breadcrumb.getStyleClass().add("breadcrumb");

        Button refresh = Fas.ghost("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> {
            Screen s = screens.get(currentKey);
            if (s != null) {
                s.onShow();
            }
        });

        HBox bar = new HBox(12, new VBox(1, pageTitle, breadcrumb), Fas.spacer(), refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("topbar");
        return bar;
    }

    private Node buildContent() {
        contentArea = new StackPane();
        contentArea.setPadding(Insets.EMPTY);
        return contentArea;
    }

    private Node buildStatusBar() {
        statusLeft = new Label("Ready");
        statusLeft.getStyleClass().add("statusbar-text");
        statusRight = new Label("Offline \u2022 all processing is local");
        statusRight.getStyleClass().add("statusbar-text");
        HBox bar = new HBox(10, statusLeft, Fas.spacer(), statusRight);
        bar.getStyleClass().add("statusbar");
        return bar;
    }

    // ---- navigation -----------------------------------------------------

    private void navigate(String key) {
        Screen s = screens.get(key);
        if (s == null) {
            return;
        }
        // Let a polling destination stop before it is swapped out.
        Screen previous = currentKey == null ? null : screens.get(currentKey);
        if (previous != null && previous != s) {
            try {
                previous.onHide();
            } catch (RuntimeException ignored) {
                // leaving a screen must never block navigation
            }
        }
        if (currentKey != null && !currentKey.equals(key)) {
            historyStack.push(currentKey);
        }
        currentKey = key;

        Node node = built.get(key);
        if (node == null) {
            node = s.build();
            built.put(key, node);
        }
        contentArea.getChildren().setAll(node);

        pageTitle.setText(s.title());
        breadcrumb.setText(s.breadcrumb());

        for (Button b : navButtons) {
            b.getStyleClass().remove("active");
            if (key.equals(b.getUserData())) {
                b.getStyleClass().add("active");
            }
        }

        long t0 = System.nanoTime();
        try {
            s.onShow();
            statusLeft.setText(String.format("%s loaded in %.0f ms",
                    s.title(), (System.nanoTime() - t0) / 1_000_000.0));
        } catch (RuntimeException e) {
            statusLeft.setText(s.title() + " \u2014 " + e.getMessage());
        }
    }

    // ---- Router -----------------------------------------------------------

    @Override
    public void open(String destination) {
        navigate(destination);
    }

    @Override
    public void openSource(int sourceId) {
        openDetail("Source Detail", sourceId);
    }

    @Override
    public void openAspect(int aspectId) {
        openDetail("Aspect Detail", aspectId);
    }

    @Override
    public void openWord(int wordId) {
        openDetail("Word Detail", wordId);
    }

    @Override
    public void openKeyword(int keywordId) {
        openDetail("Keyword Detail", keywordId);
    }

    @Override
    public void openFile(int pathId) {
        openDetail("File Detail", pathId);
    }

    @Override
    public void openContent(int pathId) {
        openDetail("Full Content", pathId);
    }

    @Override
    public void openCategory(int categoryId) {
        Screen s = screens.get("Categories");
        navigate("Categories");
        if (s instanceof com.aegis.fdx.ui.screens.CategoriesScreen cs) {
            cs.selectCategory(categoryId);
        }
    }

    @Override
    public void openSearch(String query) {
        navigate("Search");
        Screen s = screens.get("Search");
        if (s instanceof com.aegis.fdx.ui.screens.SearchScreen ss) {
            ss.runQuery(query);
        }
    }

    @Override
    public void back() {
        if (historyStack.isEmpty()) {
            return;
        }
        String previous = historyStack.pop();
        // navigate() would push the current key back on, producing a loop.
        String restore = currentKey;
        currentKey = null;
        navigate(previous);
        if (restore != null && !historyStack.isEmpty()
                && restore.equals(historyStack.peek())) {
            historyStack.pop();
        }
    }

    @Override
    public boolean canGoBack() {
        return !historyStack.isEmpty();
    }

    /** Sets the record on a detail destination, then shows it. */
    private void openDetail(String destination, int recordId) {
        Screen s = screens.get(destination);
        if (s instanceof Detail d) {
            d.setRecordId(recordId);
        }
        navigate(destination);
    }

    private void shutdown() {
        // Stop any destination holding a running animation, or the toolkit will not exit.
        for (Screen s : screens.values()) {
            try {
                s.dispose();
            } catch (RuntimeException ignored) {
                // shutdown is best effort
            }
        }
        try {
            if (liveCase != null) {
                liveCase.close();
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    @Override
    public void stop() {
        shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
