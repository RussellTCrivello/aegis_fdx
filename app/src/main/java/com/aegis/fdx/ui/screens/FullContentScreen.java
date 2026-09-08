package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.ui.Detail;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The complete extracted text of one file, with in-document search.
 *
 * <p>Separate from {@link FileDetailScreen} because reading a long document is its own
 * task: the whole viewport is given to the text.
 */
public final class FullContentScreen implements Detail {

    private final AegisFacades facades;
    private final Router router;

    private int pathId;
    private PathDto path;
    private String fullText = "";

    private Label heading;
    private Label stats;
    private TextArea body;
    private TextField findField;
    private Label findStatus;
    private int lastFindIndex = -1;

    public FullContentScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router;
    }

    @Override public String title() { return "Full Content"; }

    @Override public String breadcrumb() {
        return path == null ? "Home / File Library / Content"
                : "Home / File Library / " + path.fileName() + " / Content";
    }

    @Override public String icon() { return Icons.FILE_TEXT; }

    @Override public void setRecordId(int id) { this.pathId = id; }

    @Override
    public Node build() {
        heading = new Label();
        heading.getStyleClass().add("section-title");
        stats = Fas.muted("");

        body = new TextArea();
        body.setEditable(false);
        body.setWrapText(true);
        body.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12px;");
        VBox.setVgrow(body, Priority.ALWAYS);

        findField = Fas.field("Find in this document\u2026");
        findField.setPrefWidth(260);
        findField.setOnAction(e -> findNext());
        findStatus = Fas.muted("");

        Button findBtn = Fas.outline("Find Next", Icons.SEARCH);
        findBtn.setOnAction(e -> findNext());

        Button copy = Fas.outline("Copy All", Icons.FILES);
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(fullText);
            Clipboard.getSystemClipboard().setContent(c);
            findStatus.setText("Copied " + fullText.length() + " characters");
        });

        Button back = Fas.outline("Back to File", null);
        back.setOnAction(e -> router.openFile(pathId));

        VBox content = new VBox(14,
                Fas.pageHeader("Full Content", null, back, copy),
                new VBox(2, heading, stats),
                Fas.row(10, findField, findBtn, findStatus),
                body);
        content.setPadding(new Insets(20));
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    @Override
    public void onShow() {
        if (pathId <= 0) {
            heading.setText("No file selected");
            return;
        }
        try {
            path = facades.contents().getPath(pathId);
            heading.setText(path.fileName());
            fullText = facades.contents().getContentAsText(pathId);
            if (fullText == null) {
                fullText = "";
            }
            body.setText(fullText.isBlank()
                    ? "(no extracted text stored for this file)" : fullText);
            int words = fullText.isBlank() ? 0 : fullText.trim().split("\\s+").length;
            int lines = fullText.isBlank() ? 0 : fullText.split("\n").length;
            stats.setText(String.format("%,d characters \u00b7 %,d words \u00b7 %,d lines \u00b7 %s",
                    fullText.length(), words, lines, path.fileType()));
            lastFindIndex = -1;
            findStatus.setText("");
        } catch (FacadeException e) {
            heading.setText("Could not load content");
            stats.setText(e.getMessage());
        }
    }

    /** Wrapping case-insensitive search that selects the next occurrence. */
    private void findNext() {
        String needle = findField.getText();
        if (needle == null || needle.isBlank() || fullText.isBlank()) {
            findStatus.setText("");
            return;
        }
        String hay = fullText.toLowerCase();
        String n = needle.toLowerCase();
        int from = lastFindIndex + 1;
        int idx = hay.indexOf(n, from);
        if (idx < 0) {
            idx = hay.indexOf(n);   // wrap
        }
        if (idx < 0) {
            findStatus.setText("Not found");
            lastFindIndex = -1;
            return;
        }
        lastFindIndex = idx;
        body.selectRange(idx, idx + needle.length());
        body.requestFocus();

        int total = 0;
        int p = hay.indexOf(n);
        while (p >= 0) {
            total++;
            p = hay.indexOf(n, p + n.length());
        }
        findStatus.setText("Match at character " + idx + " of " + total + " total");
    }
}
