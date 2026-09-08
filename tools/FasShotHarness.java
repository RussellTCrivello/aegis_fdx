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

    /** Every navigable destination, in sidebar order. */
    private static final List<String> SCREENS = List.of(
            "Dashboard", "Analysis", "Charts", "Comprehensive", "Path Analysis",
            "Batch Analysis", "Search", "Advanced Search",
            "Sources", "Aspects", "Email Words", "Keywords", "Words", "Categories",
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
            } catch (Exception e) {
                System.out.println("NAV FAIL " + name + ": " + e);
            }
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

        /** Types a query into the real Search screen and clicks the real button. */
        private void runSearch(Scene scene) {
            try {
                javafx.scene.control.TextField q = null;
                javafx.scene.control.Button go = null;
                for (javafx.scene.Node n : allNodes(scene.getRoot())) {
                    if (q == null && n instanceof javafx.scene.control.TextField tf
                            && tf.getPromptText() != null
                            && tf.getPromptText().startsWith("Search across")) {
                        q = tf;
                    }
                    // The sidebar nav link is also labelled "Search" and appears first
                    // in traversal order; match the page's primary button instead.
                    if (go == null && n instanceof javafx.scene.control.Button b
                            && "Search".equals(b.getText())
                            && b.getStyleClass().contains("btn-primary")) {
                        go = b;
                    }
                }
                if (q != null && go != null) {
                    q.setText("consulting");
                    go.fire();
                    System.out.println("SEARCH FIRED");
                } else {
                    System.out.println("SEARCH CONTROLS NOT FOUND");
                }
            } catch (Exception e) {
                System.out.println("SEARCH FAIL: " + e);
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
                System.out.println("AGENT FAIL: " + e);
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
                java.lang.reflect.Method nav = com.aegis.fdx.ui.FasApp.class
                        .getDeclaredMethod("navigate", String.class);
                nav.setAccessible(true);

                Object[][] jobs = {
                        {openSource, 1, "27-source-detail"},
                        {openAspect, 1, "28-aspect-detail"},
                        {openFile, 1, "29-file-detail"},
                        {openContent, 1, "30-full-content"},
                        {openKeyword, 1, "31-keyword-detail"},
                        {openWord, 1, "32-word-detail"},
                };
                for (Object[] job : jobs) {
                    java.lang.reflect.Method m = (java.lang.reflect.Method) job[0];
                    m.setAccessible(true);
                    m.invoke(app, (int) (Integer) job[1]);
                    pauseThen(scene, (String) job[2]);
                }
                nav.invoke(app, "Source Relationships");
                pauseThen(scene, "33-source-relationships");
            } catch (Exception e) {
                System.out.println("DETAIL FAIL: " + e);
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
                System.out.println("BATCH FAIL: " + e);
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
                System.out.println("SHOT FAIL " + name + ": " + e);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            outDir = args[0];
        }
        Application.launch(Harness.class);
    }
}
