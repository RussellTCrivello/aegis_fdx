import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Drives the real FasApp through every sidebar screen and writes a PNG per screen.
 *
 * <p>Deliberately launches the actual application rather than rebuilding a mock scene,
 * so the screenshots are evidence that the shipped UI renders.
 */
public class FasShotHarness {

    private static String outDir = "docs/screens-fas";

    private static int failures = 0;

    /** Records a gate failure loudly; the process exit code carries the count. */
    static synchronized void fail(String msg) {
        failures++;
        System.out.println(msg);
    }

    /** Every navigable destination, in sidebar order. */
    private static final List<String> SCREENS = List.of(
            "Dashboard", "Analysis", "Charts", "Comprehensive", "Path Analysis",
            "Batch Analysis", "Search", "Advanced Search",
            "Sources", "Aspects", "Email Words", "Keywords", "Words", "Categories",
            "Titles", "Relations", "Geolocation",
            "Upload Files", "File Library", "Archives", "Import / Export",
            "Assistant",
            "Saved Searches", "Notifications", "Processing", "Errors",
            "Performance", "Setup", "Settings");

    /**
     * Launches the real FasApp instance and drives it. FasApp is final (correctly so),
     * so this harness composes rather than subclasses: it instantiates the app, calls
     * its real start(), then walks the navigation via reflection.
     */
    public static class Harness extends Application {

        private final com.aegis.fdx.ui.FasApp app = new com.aegis.fdx.ui.FasApp();

        @Override
        public void start(Stage stage) throws Exception {
            app.start(stage);
            new File(outDir).mkdirs();
            Scene scene = stage.getScene();
            step(scene, 0);
        }

        private void step(Scene scene, int i) {
            if (i >= SCREENS.size()) {
                shotDetails(scene);
                Platform.exit();
                return;
            }
            String name = SCREENS.get(i);
            try {
                Method nav = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("navigate", String.class);
                nav.setAccessible(true);
                nav.invoke(app, name);
                Field currentKey = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredField("currentKey");
                currentKey.setAccessible(true);
                Object landed = currentKey.get(app);
                if (!name.equals(landed)) {
                    fail("NAV FAIL " + name + ": app is showing " + landed);
                }
            } catch (Exception e) {
                fail("NAV FAIL " + name + ": " + e);
            }
            census(scene, name);
            // Drive a live query on the Search page so the screenshot shows real
            // engine results rather than an idle form.
            if ("Search".equals(name)) {
                runSearch(scene);
            }
            if ("Assistant".equals(name)) {
                runAgent(scene);
            }
            if ("Batch Analysis".equals(name)) {
                runBatch(scene);
            }
            PauseTransition p = new PauseTransition(Duration.millis(420));
            p.setOnFinished(e -> {
                shot(scene, String.format("%02d-%s.png", i + 1,
                        name.toLowerCase().replace(" / ", "-").replace(' ', '-')));
                step(scene, i + 1);
            });
            p.play();
        }

        /** Counts the live controls on the current screen; a screen whose controls
         * did not build is a failure, not a screenshot. */
        private void census(Scene scene, String name) {
            try {
                int buttons = 0, disabled = 0, fields = 0, tables = 0, lists = 0;
                for (javafx.scene.Node n : allNodes(scene.getRoot())) {
                    if (n instanceof javafx.scene.control.Button b) {
                        buttons++;
                        if (b.isDisable()) {
                            disabled++;
                        }
                    } else if (n instanceof javafx.scene.control.TextInputControl) {
                        fields++;
                    } else if (n instanceof javafx.scene.control.TableView) {
                        tables++;
                    } else if (n instanceof javafx.scene.control.ListView) {
                        lists++;
                    }
                }
                System.out.println("SCREEN " + name + ": buttons=" + buttons
                        + " disabled=" + disabled + " fields=" + fields
                        + " tables=" + tables + " lists=" + lists);
                if (buttons == 0 && fields == 0 && tables == 0 && lists == 0) {
                    fail("CENSUS FAIL " + name + ": no controls rendered");
                }
            } catch (Exception e) {
                fail("CENSUS FAIL " + name + ": " + e);
            }
        }

        /** Types a query into the real Search screen, clicks the real button, and
         * demands real results: the seeded case contains consulting material, so an
         * empty table here is a product failure, not an empty state. */
        private void runSearch(Scene scene) {
            try {
                java.util.List<javafx.scene.Node> nodes = allNodes(scene.getRoot());
                javafx.scene.control.TextField q = null;
                int qIndex = -1;
                for (int i = 0; i < nodes.size(); i++) {
                    if (nodes.get(i) instanceof javafx.scene.control.TextField tf
                            && tf.getPromptText() != null
                            && tf.getPromptText().startsWith("Search across")) {
                        q = tf;
                        qIndex = i;
                        break;
                    }
                }
                // The sidebar link and the topbar global-search button are also
                // labelled "Search" and both precede the page form in traversal
                // order; the page button is the first primary "Search" AFTER the
                // query field.
                javafx.scene.control.Button go = null;
                if (qIndex >= 0) {
                    for (int i = qIndex + 1; i < nodes.size(); i++) {
                        if (nodes.get(i) instanceof javafx.scene.control.Button b
                                && "Search".equals(b.getText())
                                && b.getStyleClass().contains("btn-primary")) {
                            go = b;
                            break;
                        }
                    }
                }
                if (q != null && go != null) {
                    q.setText("consulting");
                    go.fire();
                    System.out.println("SEARCH FIRED");
                    // The query runs off the UI thread; wait, then count rows.
                    Thread.sleep(1500);
                    System.out.println("SEARCH DIAG: field now reads <"
                            + q.getText() + ">");
                    int shown = 0;
                    for (javafx.scene.Node n : allNodes(scene.getRoot())) {
                        if (shown >= 6) {
                            break;
                        }
                        if (n instanceof javafx.scene.control.Label l
                                && l.getText() != null
                                && l.getText().matches(
                                        "(?is).*((result|begin|match|error|fail)|\\d+\\s*ms).*")) {
                            String t = l.getText().replaceAll("\\s+", " ").trim();
                            System.out.println("SEARCH DIAG: label <"
                                    + t.substring(0, Math.min(160, t.length())) + ">");
                            shown++;
                        }
                    }
                    for (javafx.scene.Node n : allNodes(scene.getRoot())) {
                        if (n instanceof javafx.scene.control.TableView tv) {
                            rows += tv.getItems().size();
                        }
                    }
                    System.out.println("SEARCH RESULTS: " + rows + " rows");
                    if (rows == 0) {
                        fail("SEARCH FAIL: 'consulting' returned no rows on the seeded case");
                    }
                } else {
                    fail("SEARCH FAIL: controls not found");
                }
            } catch (Exception e) {
                fail("SEARCH FAIL: " + e);
            }
        }

        private static java.util.List<javafx.scene.Node> allNodes(javafx.scene.Node root) {
            java.util.List<javafx.scene.Node> out = new java.util.ArrayList<>();
            collect(root, out);
            return out;
        }

        private static void collect(javafx.scene.Node n, java.util.List<javafx.scene.Node> out) {
            out.add(n);
            // A ScrollPane holds its body in getContent(), not among its children, so a
            // plain child walk misses every control on a scrolling screen.
            if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
                collect(sp.getContent(), out);
            }
            if (n instanceof javafx.scene.Parent p) {
                for (javafx.scene.Node c : p.getChildrenUnmodifiable()) collect(c, out);
            }
        }

        /** Types a question into the real Assistant screen and clicks Ask. */
        private void runAgent(Scene scene) {
            try {
                javafx.scene.control.TextField q = null;
                javafx.scene.control.Button ask = null;
                for (javafx.scene.Node n : allNodes(scene.getRoot())) {
                    if (q == null && n instanceof javafx.scene.control.TextField tf
                            && tf.getPromptText() != null
                            && tf.getPromptText().startsWith("Ask about")) {
                        q = tf;
                    }
                    if (ask == null && n instanceof javafx.scene.control.Button b
                            && "Ask".equals(b.getText())
                            && b.getStyleClass().contains("btn-primary")) {
                        ask = b;
                    }
                }
                if (q != null && ask != null && !ask.isDisable()) {
                    q.setText("What consulting material is on this case?");
                    ask.fire();
                    System.out.println("AGENT FIRED");
                    // the agent runs off-thread; give it time to publish
                    Thread.sleep(2500);
                } else {
                    System.out.println("AGENT SKIP: field=" + (q != null)
                            + " button=" + (ask != null)
                            + " disable=" + (ask != null ? ask.isDisable() : "n/a"));
                }
            } catch (Exception e) {
                fail("AGENT FAIL: " + e);
            }
        }

        /** Drives the detail destinations with a real record id from the seeded case. */
        private void shotDetails(Scene scene) {
            try {
                java.lang.reflect.Method openSource = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("openSource", int.class);
                java.lang.reflect.Method openAspect = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("openAspect", int.class);
                java.lang.reflect.Method openFile = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("openFile", int.class);
                java.lang.reflect.Method openContent = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("openContent", int.class);
                java.lang.reflect.Method openKeyword = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("openKeyword", int.class);
                java.lang.reflect.Method openWord = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("openWord", int.class);
                java.lang.reflect.Method openCategoryDetail = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("openCategoryDetail", int.class);
                java.lang.reflect.Method nav = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("navigate", String.class);
                nav.setAccessible(true);

                Object[][] jobs = {
                        {openSource, 1, "source-detail"},
                        {openAspect, 1, "aspect-detail"},
                        {openFile, 1, "file-detail"},
                        {openContent, 1, "full-content"},
                        {openKeyword, 1, "keyword-detail"},
                        {openWord, 1, "word-detail"},
                };
                int n = SCREENS.size();
                for (Object[] job : jobs) {
                    n++;
                    java.lang.reflect.Method m = (java.lang.reflect.Method) job[0];
                    m.setAccessible(true);
                    m.invoke(app, (int) (Integer) job[1]);
                    pauseThen(scene, String.format("%02d-%s", n, (String) job[2]));
                }
                nav.invoke(app, "Source Relationships");
                n++;
                pauseThen(scene, String.format("%02d-source-relationships", n));
                openCategoryDetail.setAccessible(true);
                openCategoryDetail.invoke(app, 1);
                n++;
                pauseThen(scene, String.format("%02d-category-detail", n));
                nav.invoke(app, "Aspect Relationships");
                n++;
                pauseThen(scene, String.format("%02d-aspect-relationships", n));
            } catch (Exception e) {
                fail("DETAIL FAIL: " + e);
            }
        }

        private void pauseThen(Scene scene, String name) throws InterruptedException {
            Thread.sleep(350);
            shot(scene, name + ".png");
        }

        /** Clicks Start Analysis on the real screen and waits for the run to finish. */
        private void runBatch(Scene scene) {
            try {
                javafx.scene.control.Button start = null;
                for (javafx.scene.Node n : allNodes(scene.getRoot())) {
                    if (n instanceof javafx.scene.control.Button b
                            && "Start Analysis".equals(b.getText())) {
                        start = b;
                        break;
                    }
                }
                if (start != null && !start.isDisable()) {
                    start.fire();
                    System.out.println("BATCH FIRED");
                    Thread.sleep(2500);
                } else {
                    System.out.println("BATCH SKIP: button=" + (start != null));
                }
            } catch (Exception e) {
                fail("BATCH FAIL: " + e);
            }
        }

        private void shot(Scene scene, String name) {
            try {
                WritableImage img = scene.snapshot(null);
                File f = new File(outDir, name);
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
                System.out.println("WROTE " + f.getPath() + "  "
                        + (int) img.getWidth() + "x" + (int) img.getHeight());
            } catch (Exception e) {
                fail("SHOT FAIL " + name + ": " + e);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            outDir = args[0];
        }
        try {
            Application.launch(Harness.class);
        } catch (Throwable t) {
            // A failure to start the toolkit is a statement about the machine,
            // not about the product — same rule as JUnitRunner's ENV bucket.
            for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
                String message = c.getMessage() == null ? "" : c.getMessage();
                if (message.contains("No toolkit found")
                        || message.contains("Error initializing QuantumRenderer")
                        || message.contains("Graphics Device initialization failed")
                        || message.contains("Unable to open DISPLAY")
                        || (c instanceof UnsatisfiedLinkError
                                && (message.contains("glass") || message.contains("prism")
                                        || message.contains("javafx")))) {
                    System.out.println("GUI GATE: ENV \u2014 needs a graphics device; not run here");
                    return;
                }
            }
            throw t;
        }
        // 9 detail destinations: 7 open*(id) shots plus the two relationship graphs.
        System.out.println("GUI GATE: " + failures + " failures over "
                + (SCREENS.size() + 9) + " destinations");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
