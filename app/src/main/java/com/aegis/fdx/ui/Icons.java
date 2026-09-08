package com.aegis.fdx.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * Icon set for the File Analysis System UI.
 *
 * <p>The Python reference project uses Bootstrap Icons ({@code bi bi-*}). JavaFX has no
 * icon-font pipeline, so each glyph the Python sidebar and screens use is reproduced
 * here as an SVG path under the <em>same name</em>. That keeps the visual language and
 * the naming identical while staying offline (N-06) and dependency-free.
 *
 * <p>Bootstrap Icons is MIT licensed; these paths are simplified equivalents drawn to
 * the same 16x16 grid rather than copies, so no third-party asset is redistributed.
 */
public final class Icons {

    private Icons() {
    }

    // --- sidebar glyphs, named after their Bootstrap Icons counterparts ---

    /** bi-speedometer2 — Dashboard */
    public static final String SPEEDOMETER2 =
            "M8 2a6 6 0 0 0-6 6c0 1.5.5 2.9 1.4 4h9.2A6 6 0 0 0 8 2zm0 2.5a.5.5 0 0 1 .5.5v1a.5.5 0 0 1-1 0V5a.5.5 0 0 1 .5-.5zM4.6 6l.8.8M11.4 6l-.8.8M8 11l2.5-3";

    /** bi-archive — Analysis */
    public static final String ARCHIVE =
            "M2 3h12v3H2V3zm1 3.5h10V13H3V6.5zM6.5 8.5h3";

    /** bi-search — Search */
    public static final String SEARCH =
            "M7 2.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9zm3.5 8 3 3";

    /** bi-building — Sources */
    public static final String BUILDING =
            "M3 14V2.5h7V14M10 6h3v8M4.8 4.5h1M7.2 4.5h1M4.8 7h1M7.2 7h1M4.8 9.5h1M7.2 9.5h1M11.2 8h1M11.2 10.5h1";

    /** bi-diagram-3 — Sides */
    public static final String DIAGRAM3 =
            "M6.5 2h3v2.5h-3V2zM2 11.5h3V14H2v-2.5zM11 11.5h3V14h-3v-2.5zM8 4.5v3M3.5 11.5v-2h9v2M8 7.5v2";

    /** bi-envelope-at — Email Words */
    public static final String ENVELOPE_AT =
            "M2 4h12v6.5H2V4zm0 0 6 4 6-4M11 12.5a2 2 0 1 0 1.6-3.2";

    /** bi-key — Keywords */
    public static final String KEY =
            "M10.5 3a3 3 0 1 0-2.6 4.5L3 12.4V14h2v-1.3h1.3V11.4h1.4l.6-.6A3 3 0 0 0 10.5 3zm.6 2.4a.9.9 0 1 1-1.3-1.3.9.9 0 0 1 1.3 1.3z";

    /** bi-book — Words */
    public static final String BOOK =
            "M2.5 3.2c1.8-.8 3.7-.8 5.5 0v9.6c-1.8-.8-3.7-.8-5.5 0V3.2zM8 3.2c1.8-.8 3.7-.8 5.5 0v9.6c-1.8-.8-3.7-.8-5.5 0";

    /** bi-tags — Categories */
    public static final String TAGS =
            "M2.5 2.5h5L13 8l-5 5-5.5-5.5v-5zM4.6 4.6h.01";

    /** bi-cloud-upload — Upload Files */
    public static final String CLOUD_UPLOAD =
            "M4.4 12a2.9 2.9 0 0 1-.3-5.8 4 4 0 0 1 7.7-.6A2.7 2.7 0 0 1 12 12M8 7v6.5M5.8 9.2 8 7l2.2 2.2";

    /** bi-folder2-open — File Library */
    public static final String FOLDER_OPEN =
            "M2 12.5V4h4l1.2 1.5H12V7M2 12.5 4 7h11l-2 5.5H2z";

    /** bi-bell — Notifications */
    public static final String BELL =
            "M8 2a3.6 3.6 0 0 0-3.6 3.6c0 3-1.2 4.1-1.2 4.1h9.6s-1.2-1.1-1.2-4.1A3.6 3.6 0 0 0 8 2zM6.7 12a1.4 1.4 0 0 0 2.6 0";

    /** bi-gear — Settings */
    public static final String GEAR =
            "M8 5.6A2.4 2.4 0 1 0 8 10.4 2.4 2.4 0 0 0 8 5.6zM13.5 8l-1.2.3-.4.9.6 1.1-1.2 1.2-1.1-.6-.9.4-.3 1.2H7.3L7 11.3l-.9-.4-1.1.6L3.8 10.3l.6-1.1-.4-.9L2.5 8V6.4l1.5-.3.4-.9-.6-1.1 1.2-1.2 1.1.6.9-.4L7.3 1.7h1.4l.3 1.4.9.4 1.1-.6 1.2 1.2-.6 1.1.4.9 1.5.3V8z";

    /** bi-files — Total Files stat */
    public static final String FILES =
            "M4.5 2h5L12 4.5V13h-7.5V2zM9.5 2v2.5H12M2.5 4.5V14H10";

    /** bi-check-circle — Processed stat */
    public static final String CHECK_CIRCLE =
            "M8 2a6 6 0 1 0 0 12A6 6 0 0 0 8 2zm-2.4 6.2 1.8 1.8 3.4-3.6";

    /** bi-folder — File Types stat */
    public static final String FOLDER =
            "M2 4h4l1.2 1.5H14V12H2V4z";

    /** bi-fonts — Total Words stat */
    public static final String FONTS =
            "M3.5 13 7.2 3h1.6L12.5 13M5.4 9.6h5.2";

    /** bi-database — Storage stat */
    public static final String DATABASE =
            "M8 2c3 0 5.2.9 5.2 2s-2.2 2-5.2 2-5.2-.9-5.2-2S5 2 8 2zM2.8 4v8c0 1.1 2.2 2 5.2 2s5.2-.9 5.2-2V4M2.8 8c0 1.1 2.2 2 5.2 2s5.2-.9 5.2-2";

    /** bi-bar-chart — Charts and performance */
    public static final String CHART =
            "M3 13V8.5M6.5 13V4M10 13v-6M13.5 13V6M2 13.8h12.5";

    /** bi-hdd-stack — Database Size stat */
    public static final String HDD_STACK =
            "M2 3h12v4H2V3zm0 6h12v4H2V9zm2.5-4h.01M4.5 11h.01";

    /** bi-file-earmark-text — Contents */
    public static final String FILE_TEXT =
            "M4 2h5l3 3v9H4V2zm5 0v3h3M6 8h4M6 10.5h4";

    /** bi-house-door — Home */
    public static final String HOUSE =
            "M2.5 7.5 8 3l5.5 4.5V13H10v-3.5H6V13H2.5V7.5z";

    /** bi-trash — Delete */
    public static final String TRASH =
            "M3.5 4h9M6.5 4V2.5h3V4M4.5 4v9.5h7V4M6.5 6.5v5M9.5 6.5v5";

    /** bi-pencil — Edit */
    public static final String PENCIL =
            "M11.5 2.5 13.5 4.5 5.5 12.5 2.5 13.5 3.5 10.5 11.5 2.5z";

    /** bi-plus-lg — Add */
    public static final String PLUS =
            "M8 3v10M3 8h10";

    /** bi-eye — View */
    public static final String EYE =
            "M8 4C4.5 4 2 8 2 8s2.5 4 6 4 6-4 6-4-2.5-4-6-4zm0 2.2A1.8 1.8 0 1 1 8 9.8 1.8 1.8 0 0 1 8 6.2z";

    /** bi-download — Export */
    public static final String DOWNLOAD =
            "M8 2.5v7M5.3 7 8 9.7 10.7 7M3 12.5h10";

    /** bi-play-fill */
    public static final String PLAY = "M4 2.5 13 8 4 13.5V2.5z";

    /** bi-pause-fill */
    public static final String PAUSE = "M4.5 3h2.5v10H4.5V3zm4.5 0h2.5v10H9V3z";

    /** bi-stop-fill */
    public static final String STOP = "M3.5 3.5h9v9h-9v-9z";

    /** bi-arrow-clockwise — Refresh */
    public static final String REFRESH =
            "M13 8a5 5 0 1 1-1.6-3.7M13 2.5V5.5H10";

    /** bi-funnel — Filter */
    public static final String FUNNEL = "M2 3h12L9.5 8.2V13l-3 1V8.2L2 3z";

    /** bi-link-45deg — Relationships */
    public static final String LINK =
            "M6.5 9.5 9.5 6.5M6 4.5H4.5a3 3 0 0 0 0 6H6m4-6h1.5a3 3 0 0 1 0 6H10";

    /** bi-shield-check — Integrity */
    public static final String SHIELD =
            "M8 1.5 13 3.5v4c0 3.3-2.1 6-5 6.8-2.9-.8-5-3.5-5-6.8v-4l5-2zm-1.9 6 1.5 1.5 2.5-2.6";

    /** bi-clock-history — History */
    public static final String CLOCK =
            "M8 2a6 6 0 1 0 6 6M8 4.5V8l2.4 1.4M13.5 2v3h-3";

    /** bi-bookmark-star — Saved searches */
    public static final String BOOKMARK =
            "M4 2.5h8v11L8 11l-4 2.5v-11z";

    /** bi-list-ul */
    public static final String LIST = "M5.5 4h9M5.5 8h9M5.5 12h9M2.8 4h.01M2.8 8h.01M2.8 12h.01";

    /** bi-x-lg */
    public static final String CLOSE = "M3.5 3.5 12.5 12.5M12.5 3.5 3.5 12.5";

    /** bi-chevron-right */
    public static final String CHEVRON_RIGHT = "M6 3.5 10.5 8 6 12.5";

    /** bi-info-circle */
    public static final String INFO =
            "M8 2a6 6 0 1 0 0 12A6 6 0 0 0 8 2zm0 2.6h.01M8 7v4.2";

    /** bi-exclamation-triangle — Errors and processing failures */
    public static final String ALERT =
            "M8 2 15 13.5H1L8 2zM8 6.2v3.2M8 11.3h.01";

    // --- factories --------------------------------------------------------

    /** A stroked glyph, which is how most Bootstrap Icons read at small sizes. */
    public static SVGPath outline(String path, String color, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setStroke(Color.web(color));
        p.setStrokeWidth(1.3);
        p.setFill(Color.TRANSPARENT);
        double scale = size / 16.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        return p;
    }

    /** A filled glyph, for solid shapes such as play/stop. */
    public static SVGPath filled(String path, String color, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFill(Color.web(color));
        double scale = size / 16.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        return p;
    }

    /**
     * Wraps a glyph in a fixed-size box so rows of icons align regardless of the
     * glyph's own bounds — the JavaFX equivalent of an icon font's fixed advance.
     */
    public static Node box(String path, String color, double size) {
        StackPane sp = new StackPane(new Group(outline(path, color, size)));
        sp.setMinSize(size + 4, size + 4);
        sp.setPrefSize(size + 4, size + 4);
        sp.setMaxSize(size + 4, size + 4);
        return sp;
    }

    public static Node boxFilled(String path, String color, double size) {
        StackPane sp = new StackPane(new Group(filled(path, color, size)));
        sp.setMinSize(size + 4, size + 4);
        sp.setPrefSize(size + 4, size + 4);
        sp.setMaxSize(size + 4, size + 4);
        return sp;
    }
}
