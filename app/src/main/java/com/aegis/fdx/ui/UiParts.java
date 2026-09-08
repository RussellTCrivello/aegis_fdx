package com.aegis.fdx.ui;

import com.aegis.fdx.model.ItemStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/** Small presentation helpers: icons drawn as vector paths, chips, spacers. */
final class UiParts {

    private UiParts() {}

    // Original 24x24 glyph paths authored for this project.
    static final String I_CASE   = "M3 7h6l2 2h10v10H3V7zm0 0V5h6l2 2";
    static final String I_SEARCH = "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14zm11 18-6-6";
    static final String I_TAG    = "M3 3h8l10 10-8 8L3 11V3zm4 4h.01";
    static final String I_DOC    = "M6 2h8l6 6v14H6V2zm8 0v6h6";
    static final String I_MAIL   = "M2 5h20v14H2V5zm0 0 10 8 10-8";
    static final String I_ARCHIVE= "M2 4h20v5H2V4zm2 5h16v11H4V9zm5 4h6";
    static final String I_IMAGE  = "M3 4h18v16H3V4zm3 11 4-5 3 4 2-2 4 5";
    static final String I_PLAY   = "M6 3l14 9-14 9V3z";
    static final String I_PAUSE  = "M7 4h4v16H7V4zm6 0h4v16h-4V4z";
    static final String I_STOP   = "M5 5h14v14H5V5z";
    static final String I_EXPORT = "M12 3v12m0-12 4 4m-4-4-4 4M4 17v4h16v-4";
    static final String I_CHART  = "M4 20V10m5 10V4m5 16v-7m5 7V7";
    static final String I_GEAR   = "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm9 4-2 .5-.6 1.5 1 1.8-2 2-1.8-1-1.5.6L13 21h-2l-.5-2-1.5-.6-1.8 1-2-2 1-1.8L5.6 14 3.6 13v-2l2-.5.6-1.5-1-1.8 2-2 1.8 1L11 5.6 11.5 3.6h2l.5 2 1.5.6 1.8-1 2 2-1 1.8.6 1.5 2 .5v2z";
    static final String I_SHIELD = "M12 2l8 4v6c0 5-3.5 9-8 10-4.5-1-8-5-8-10V6l8-4zm-3 9 2.5 2.5L16 9";
    static final String I_LIST   = "M4 6h16M4 12h16M4 18h16";
    static final String I_LINK   = "M9 15l6-6M8 7H6a5 5 0 0 0 0 10h2m8-10h2a5 5 0 0 1 0 10h-2";

    static SVGPath icon(String path, String color, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setStyle("-fx-fill: transparent; -fx-stroke: " + color
                + "; -fx-stroke-width: 1.7; -fx-stroke-line-cap: round; -fx-stroke-line-join: round;");
        double s = size / 24.0;
        p.setScaleX(s);
        p.setScaleY(s);
        return p;
    }

    static Node iconBox(String path, String color, double size) {
        HBox box = new HBox(icon(path, color, size));
        box.setAlignment(Pos.CENTER);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }

    static Label statusPill(ItemStatus s) {
        Label l = new Label(s.label());
        l.getStyleClass().add("status-pill");
        l.setStyle("-fx-background-color: " + hexA(s.color(), "22")
                + "; -fx-text-fill: " + s.color() + ";");
        return l;
    }

    static Label tagChip(String name, String color) {
        Label l = new Label(name);
        l.getStyleClass().add("tag-chip");
        l.setStyle("-fx-background-color: " + color + ";");
        return l;
    }

    static String hexA(String hex, String alpha) {
        return "#" + hex.replace("#", "") + alpha;
    }

    static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    static Region vSpacer() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    static Label sectionTitle(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("section-title");
        l.setPadding(new Insets(10, 0, 4, 0));
        return l;
    }

    static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        return l;
    }

    static Label dim(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("dim");
        return l;
    }

    /** Icon chosen from the element's extension — mirrors the format matrix. */
    static String iconFor(String ext) {
        if (ext == null) return I_DOC;
        return switch (ext) {
            case "msg", "eml", "mbox" -> I_MAIL;
            case "pst", "ost" -> I_MAIL;
            case "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> I_ARCHIVE;
            case "jpg", "jpeg", "png", "gif", "bmp", "tiff" -> I_IMAGE;
            case "mp3", "mp4", "wav" -> I_PLAY;
            default -> I_DOC;
        };
    }
}
