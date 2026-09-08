package com.aegis.fdx.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Shared UI building blocks for the File Analysis System screens.
 *
 * <p>Each factory here is the JavaFX counterpart of a component class in the Python
 * project's stylesheet — {@code .stat-card}, {@code .section-card}, {@code .badge},
 * {@code .page-header}, {@code .btn-*} — so the screens compose from the same
 * vocabulary the Python templates do.
 */
public final class Fas {

    /** Python token {@code --primary-color}. */
    public static final String PRIMARY = "#4f46e5";
    public static final String SECONDARY = "#06b6d4";
    public static final String SUCCESS = "#10b981";
    public static final String DANGER = "#ef4444";
    public static final String WARNING = "#f59e0b";
    public static final String INFO = "#3b82f6";
    public static final String TEXT_DARK = "#1e293b";
    public static final String TEXT_LIGHT = "#64748b";
    public static final String TEXT_MUTED = "#94a3b8";
    public static final String SIDEBAR_TEXT = "#e2e8f0";

    private Fas() {
    }

    // ---- layout ----------------------------------------------------------

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Region vspacer() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static HBox row(double gap, Node... kids) {
        HBox h = new HBox(gap, kids);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static VBox col(double gap, Node... kids) {
        return new VBox(gap, kids);
    }

    // ---- text ------------------------------------------------------------

    public static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("page-title");
        return l;
    }

    public static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    public static Label subtitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-subtitle");
        return l;
    }

    public static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        return l;
    }

    public static Label fieldLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
        return l;
    }

    // ---- cards -----------------------------------------------------------

    /** Python {@code .section-card}. */
    public static VBox card(Node... kids) {
        VBox v = new VBox(12, kids);
        v.getStyleClass().add("section-card");
        return v;
    }

    /** Python {@code .section-card} with the standard heading row. */
    public static VBox cardWithHeader(String heading, String sub, Node body) {
        VBox head = new VBox(2, sectionTitle(heading));
        if (sub != null && !sub.isBlank()) {
            head.getChildren().add(subtitle(sub));
        }
        VBox v = new VBox(14, head, body);
        v.getStyleClass().add("section-card");
        return v;
    }

    /**
     * Python {@code .stat-card} — the tile used across the dashboard, with a tinted
     * icon chip, a large value and a caption.
     */
    public static VBox statCard(String iconPath, String accent, String value, String label) {
        StackPane chip = new StackPane(Icons.box(iconPath, accent, 16));
        chip.getStyleClass().add("stat-icon");
        chip.setStyle("-fx-background-color: " + tint(accent) + ";");
        chip.setMaxWidth(Region.USE_PREF_SIZE);

        Label v = new Label(value);
        v.getStyleClass().add("stat-value");
        Label l = new Label(label);
        l.getStyleClass().add("stat-label");

        VBox box = new VBox(8, chip, v, l);
        box.getStyleClass().add("stat-card");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Updates the value label of a stat card produced by {@link #statCard}. */
    public static void setStat(VBox statCard, String value) {
        if (statCard.getChildren().size() > 1
                && statCard.getChildren().get(1) instanceof Label l) {
            l.setText(value);
        }
    }

    // ---- badges ----------------------------------------------------------

    /** Python {@code .badge}. */
    public static Label badge(String text, String variant) {
        Label l = new Label(text);
        l.getStyleClass().addAll("badge", "badge-" + variant);
        return l;
    }

    // ---- buttons ---------------------------------------------------------

    public static Button primary(String text, String iconPath) {
        return button(text, iconPath, "btn-primary", "white");
    }

    public static Button secondary(String text, String iconPath) {
        return button(text, iconPath, "btn-secondary", "white");
    }

    public static Button outline(String text, String iconPath) {
        return button(text, iconPath, "btn-outline", PRIMARY);
    }

    public static Button danger(String text, String iconPath) {
        return button(text, iconPath, "btn-danger", "white");
    }

    public static Button ghost(String text, String iconPath) {
        return button(text, iconPath, "btn-ghost", TEXT_LIGHT);
    }

    private static Button button(String text, String iconPath, String cls, String iconColor) {
        Button b = new Button(text);
        b.getStyleClass().add(cls);
        if (iconPath != null) {
            b.setGraphic(Icons.box(iconPath, iconColor, 14));
        }
        return b;
    }

    // ---- inputs ----------------------------------------------------------

    public static TextField field(String prompt) {
        TextField t = new TextField();
        t.setPromptText(prompt);
        return t;
    }

    /** A labelled form field, as used on the Python detail/create forms. */
    public static VBox formField(String label, Node input) {
        VBox v = new VBox(5, fieldLabel(label), input);
        if (input instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        return v;
    }

    // ---- page scaffolding ------------------------------------------------

    /**
     * The action strip above a page's content.
     *
     * <p>In the Python templates the page name is printed by the shared {@code base.html}
     * topbar, not by each page. The window topbar already does that here, so this strip
     * carries only the page's action buttons -- printing the heading again would be a
     * duplicate the reference layout does not have. The {@code heading} and
     * {@code breadcrumb} arguments are kept so each screen still declares them at its
     * call site, matching the template structure.
     */
    public static HBox pageHeader(String heading, String breadcrumb, Node... actions) {
        HBox h = new HBox(10, spacer());
        h.getChildren().addAll(actions);
        h.setAlignment(Pos.CENTER_RIGHT);
        h.setPadding(new Insets(0, 0, 2, 0));
        h.setVisible(actions.length > 0);
        h.setManaged(actions.length > 0);
        return h;
    }

    /** Python {@code .stats-grid} — a responsive row of stat cards. */
    public static HBox statsGrid(Node... cards) {
        HBox h = new HBox(14, cards);
        h.setFillHeight(true);
        return h;
    }

    public static Label emptyState(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("empty-state");
        l.setPadding(new Insets(24));
        return l;
    }

    /** 12% tint of an accent colour, matching the Python icon chips. */
    private static String tint(String hex) {
        return hex + "1f";
    }
}
