package com.aegis.fdx.ui;

/**
 * Drill-through navigation between destinations.
 *
 * <p>Screens do not know about each other; they ask the router to open a destination
 * for a given record. That keeps a source row on the Sources list able to open the
 * Source detail destination without either screen holding a reference to the other.
 */
public interface Router {

    /** Opens a destination by its registered title. */
    void open(String destination);

    /** Opens a source's detail destination. */
    void openSource(int sourceId);

    /** Opens an aspect's detail destination. */
    void openAspect(int aspectId);

    /** Opens a word's detail destination. */
    void openWord(int wordId);

    /** Opens a keyword's detail destination. */
    void openKeyword(int keywordId);

    /** Opens a registered file's detail destination. */
    void openFile(int pathId);

    /** Opens the extracted text of a registered file. */
    void openContent(int pathId);

    /** Opens the search destination pre-loaded with a query. */
    void openSearch(String query);

    /** Opens the file library filtered to one category. */
    void openCategory(int categoryId);

    /** Returns to the previously shown destination, if any. */
    void back();

    /** True when {@link #back()} would do something. */
    boolean canGoBack();
}
