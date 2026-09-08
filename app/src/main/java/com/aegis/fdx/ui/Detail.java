package com.aegis.fdx.ui;

/**
 * A destination that is opened for a particular record.
 *
 * <p>List destinations implement {@link Screen} alone. Detail destinations also
 * implement this, so the router can tell them which record to show before they are
 * displayed.
 */
public interface Detail extends Screen {

    /**
     * Selects the record to display.
     *
     * <p>Called before {@code onShow()}. Implementations should only store the
     * identifier here and do their loading in {@code onShow()}, so a slow query does
     * not run during navigation setup.
     */
    void setRecordId(int id);

    /** Whether this destination appears in the sidebar. Detail views normally do not. */
    default boolean showInNavigation() {
        return false;
    }
}
