package com.aegis.fdx.ui;

import javafx.scene.Node;

/**
 * One navigable screen of the File Analysis System.
 *
 * <p>The Python reference is a multi-page Flask app: each sidebar entry is a route that
 * renders a template. JavaFX is single-window, so each Python page becomes a
 * {@code Screen} swapped into the content area — the same navigation model and the same
 * page inventory, expressed the way a desktop app must express it.
 */
public interface Screen {

    /** Sidebar label, matching the Python nav text. */
    String title();

    /** Breadcrumb shown under the page title, mirroring the Python breadcrumb bar. */
    default String breadcrumb() {
        return "Home / " + title();
    }

    /** Icon path from {@link Icons}, named after the Python Bootstrap Icon. */
    String icon();

    /** Builds the page body. Called once, lazily, on first navigation. */
    Node build();

    /**
     * Called every time the screen becomes visible, so a page reflects data created on
     * another page. The Flask app gets this for free by re-rendering per request.
     */
    default void onShow() {
    }
}
