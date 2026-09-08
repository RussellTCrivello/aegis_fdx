import com.aegis.fdx.ui.AegisApp;
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

/**
 * Headless screenshot harness: boots the real AegisApp UI under Monocle and
 * captures the live scene graph at several points so the design can be reviewed
 * without a physical display.
 */
public class ShotHarness extends Application {

    private static String outDir = "/home/user/aegis-fdx/docs/screens";

    @Override
    public void start(Stage stage) throws Exception {
        AegisApp app = new AegisApp();
        app.start(stage);
        Scene scene = stage.getScene();
        new File(outDir).mkdirs();

        step(2.2, () -> shot(scene, "01-ingest-running.png"), () ->
        step(1.5, () -> type(scene, "settlement"), () ->
        step(1.2, () -> shot(scene, "02-search-keyword.png"), () ->
        step(0.2, () -> type(scene, "subject:\"Wire instructions\" OR type:pdf"), () ->
        step(1.2, () -> shot(scene, "03-search-fielded.png"), () ->
        step(0.2, () -> type(scene, "/INV-\\d{5}/"), () ->
        step(1.2, () -> shot(scene, "04-search-regex.png"), () ->
        step(0.2, () -> type(scene, "wire"), () ->
        step(1.2, () -> shot(scene, "05-email-preview.png"), () ->
        step(0.2, () -> type(scene, "\"OCR-CANARY-IMAGE\" OR \"OCR-CANARY-SCANNED\""), () ->
        step(1.2, () -> {
            shot(scene, "06-ocr-recovered.png");
            Platform.exit();
        }, null)))))))))));
    }

    private void type(Scene scene, String q) {
        javafx.scene.control.TextField f =
                (javafx.scene.control.TextField) scene.lookup(".search-field");
        f.setText(q);
        f.fireEvent(new javafx.event.ActionEvent());
        // NOTE: never sleep here. Search is async and delivers via Platform.runLater;
        // blocking the FX thread would prevent the result page from ever landing.
        // The caller inserts a PauseTransition before snapshotting instead.
    }

    private void shot(Scene scene, String name) {
        WritableImage img = scene.snapshot(null);
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(outDir, name));
            System.out.println("wrote " + name + " " + (int) img.getWidth() + "x" + (int) img.getHeight());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void step(double secs, Runnable body, Runnable next) {
        PauseTransition p = new PauseTransition(Duration.seconds(secs));
        p.setOnFinished(e -> {
            body.run();
            if (next != null) next.run();
        });
        p.play();
    }

    public static void main(String[] args) {
        if (args.length > 0) outDir = args[0];
        launch(args);
    }
}
