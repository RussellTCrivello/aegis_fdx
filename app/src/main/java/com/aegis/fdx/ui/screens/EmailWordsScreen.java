package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email Words: email-like strings extracted from stored document text.
 *
 * <p>Scans the extracted content of every registered file for
 * {@code something@example.com} patterns and treats each distinct address as a
 * first-class analytical entity — filterable by domain, countable per file and
 * exportable. All addresses come from text the pipeline actually extracted.
 */
public final class EmailWordsScreen implements Screen {

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    private final AegisFacades facades;
    private final Router router;

    private List<EmailRow> all = List.of();
    private final ObservableList<EmailRow> rows = FXCollections.observableArrayList();

    private TextField domainField;
    private ComboBox<String> domainMode;
    private ComboBox<String> domainSelect;
    private boolean updatingDomains;

    private VBox tileUnique;
    private VBox tilePage;
    private VBox tileCurrent;
    private VBox tilePages;
    private Label countLabel;
    private Label pageLabel;
    private HBox pageBar;
    private int page;
    private static final int PER_PAGE = 50;

    public EmailWordsScreen(AegisFacades facades) {
        this(facades, null);
    }

    public EmailWordsScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router == null ? Router.NONE : router;
    }

    @Override
    public String title() {
        return "Email Words";
    }

    @Override
    public String breadcrumb() {
        return "Home / Analysis / Email Words";
    }

    @Override
    public String icon() {
        return Icons.ENVELOPE_AT;
    }

    @Override
    public Node build() {
        tileUnique = Fas.summaryTile("0", "Unique Email-like Words");
        tilePage = Fas.summaryTile("0", "Words on This Page");
        tileCurrent = Fas.summaryTile("1", "Current Page");
        tilePages = Fas.summaryTile("1", "Total Pages");
        VBox tiles = new VBox(Fas.statsGrid(tileUnique, tilePage, tileCurrent, tilePages));

        domainField = Fas.field("e.g. gmail.com");
        domainField.setPrefWidth(180);
        domainMode = new ComboBox<>(FXCollections.observableArrayList("Exact", "Contains"));
        domainMode.setValue("Exact");
        domainMode.setPrefWidth(110);
        domainSelect = new ComboBox<>();
        domainSelect.setPromptText("Select a domain from result");
        domainSelect.setPrefWidth(220);
        domainSelect.setOnAction(e -> {
            if (updatingDomains) {
                return;
            }
            String d = domainSelect.getValue();
            if (d != null && !d.isBlank() && !"All domains".equals(d)) {
                domainField.setText(d);
                page = 0;
                applyFilter();
            }
        });

        Button apply = Fas.primary("Apply Filters", Icons.FUNNEL);
        apply.setOnAction(e -> {
            page = 0;
            applyFilter();
        });
        Button reset = Fas.ghost("Reset", Icons.CLOSE);
        reset.setOnAction(e -> {
            domainField.clear();
            domainMode.setValue("Exact");
            if (!domainSelect.getItems().isEmpty()) {
                domainSelect.setValue(domainSelect.getItems().get(0));
            }
            page = 0;
            applyFilter();
        });
        Button export = Fas.outline("Export", Icons.DOWNLOAD);
        export.setOnAction(e -> exportCsv());
        Button copy = Fas.outline("Copy", Icons.CLIPBOARD);
        copy.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for (EmailRow r : rows) {
                sb.append(r.email()).append("\n");
            }
            Fas.copyText(sb.toString());
            Alert a = new Alert(Alert.AlertType.INFORMATION,
                    "Copied " + rows.size() + " address(es) to the clipboard.", ButtonType.OK);
            a.setHeaderText("Copy complete");
            a.showAndWait();
        });
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        VBox filterBar = new VBox(10,
                Fas.row(10,
                        Fas.formField("Domain", domainField),
                        Fas.formField("Domain Mode", domainMode),
                        Fas.formField("Select Domain", domainSelect)),
                Fas.muted("The domain list is based on the current search."),
                Fas.row(8, apply, reset, export, copy, Fas.spacer(), refresh));
        filterBar.getStyleClass().add("filter-bar");

        countLabel = Fas.muted("0 email-like words");
        TableView<EmailRow> table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState(
                "No email-like words found. Ingest documents containing email addresses."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<EmailRow, String> cEmail = new TableColumn<>("Email-like Word");
        cEmail.setPrefWidth(280);
        cEmail.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().email()));
        TableColumn<EmailRow, EmailRow> cValid = new TableColumn<>("Valid");
        cValid.setPrefWidth(90);
        cValid.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cValid.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(EmailRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                setGraphic(Fas.badge(item.valid() ? "Valid" : "Invalid",
                        item.valid() ? "success" : "warning"));
            }
        });
        TableColumn<EmailRow, String> cDomain = new TableColumn<>("Domain");
        cDomain.setPrefWidth(180);
        cDomain.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().domain()));
        TableColumn<EmailRow, String> cUsage = new TableColumn<>("Usage");
        cUsage.setPrefWidth(100);
        cUsage.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().fileCount() + (c.getValue().fileCount() == 1 ? " file" : " files")));
        TableColumn<EmailRow, EmailRow> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(130);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(EmailRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Button open = Fas.ghost("Open file", Icons.EYE);
                open.setOnAction(e -> {
                    if (!item.pathIds().isEmpty()) {
                        router.openFile(item.pathIds().get(0));
                    }
                });
                Button find = Fas.ghost("", Icons.SEARCH);
                find.setOnAction(e -> router.openSearch("\"" + item.email() + "\""));
                setGraphic(Fas.row(2, open, find));
            }
        });
        table.getColumns().addAll(cEmail, cValid, cDomain, cUsage, cAct);
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<EmailRow> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()
                        && !row.getItem().pathIds().isEmpty()) {
                    router.openFile(row.getItem().pathIds().get(0));
                }
            });
            return row;
        });

        pageLabel = Fas.muted("");
        pageBar = new HBox(6);

        VBox content = new VBox(16,
                Fas.pageHeader("Email Words", breadcrumb()),
                tiles, filterBar,
                Fas.cardWithHeader("Detected Addresses",
                        "Double-click to open the first file carrying an address",
                        new VBox(10, countLabel, table,
                                Fas.row(8, pageLabel, Fas.spacer(), pageBar))));
        content.setPadding(new Insets(20));
        return content;
    }

    @Override
    public void onShow() {
        try {
            all = extract();
        } catch (RuntimeException e) {
            all = List.of();
        }
        refreshDomains(all);
        page = 0;
        applyFilter();
    }

    /** Scans stored content for email-like patterns. */
    private List<EmailRow> extract() {
        List<PathDto> paths = facades.contents()
                .getPaths(null, null, null, null, 100_000, 0).results();
        Map<String, EmailAcc> acc = new LinkedHashMap<>();
        for (PathDto p : paths) {
            String text;
            try {
                text = facades.contents().getContentAsText(p.id());
            } catch (RuntimeException e) {
                continue;
            }
            if (text == null || text.isBlank() || !text.contains("@")) {
                continue;
            }
            Matcher m = EMAIL.matcher(text);
            java.util.Set<String> seenInFile = new java.util.HashSet<>();
            while (m.find()) {
                String email = m.group();
                String norm = email.toLowerCase();
                if (!seenInFile.add(norm)) {
                    continue;
                }
                acc.computeIfAbsent(norm, k -> new EmailAcc(email)).add(p.id());
            }
        }
        List<EmailRow> out = new ArrayList<>();
        for (EmailAcc a : acc.values()) {
            out.add(a.toRow());
        }
        out.sort((a, b) -> {
            int c = Integer.compare(b.fileCount(), a.fileCount());
            return c != 0 ? c : a.email().compareToIgnoreCase(b.email());
        });
        return out;
    }

    private void refreshDomains(List<EmailRow> list) {
        updatingDomains = true;
        try {
            TreeSet<String> domains = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (EmailRow r : list) {
                domains.add(r.domain());
            }
            List<String> items = new ArrayList<>();
            items.add("All domains");
            items.addAll(domains);
            String keep = domainSelect.getValue();
            domainSelect.getItems().setAll(items);
            domainSelect.setValue(items.contains(keep) ? keep : "All domains");
        } finally {
            updatingDomains = false;
        }
    }

    private void applyFilter() {
        String domainQ = domainField == null || domainField.getText() == null
                ? "" : domainField.getText().trim().toLowerCase();
        boolean exact = domainMode == null || "Exact".equals(domainMode.getValue());
        List<EmailRow> filtered = new ArrayList<>();
        for (EmailRow r : all) {
            if (domainQ.isEmpty()) {
                filtered.add(r);
            } else if (exact) {
                if (r.domain().equalsIgnoreCase(domainQ)) {
                    filtered.add(r);
                }
            } else {
                if (r.domain().toLowerCase().contains(domainQ)) {
                    filtered.add(r);
                }
            }
        }
        int total = filtered.size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) PER_PAGE));
        page = Math.min(Math.max(0, page), pages - 1);
        int from = page * PER_PAGE;
        int to = Math.min(from + PER_PAGE, total);
        List<EmailRow> visible = from < to ? filtered.subList(from, to) : List.of();
        rows.setAll(visible);

        Fas.setSummary(tileUnique, String.valueOf(all.size()));
        Fas.setSummary(tilePage, String.valueOf(visible.size()));
        Fas.setSummary(tileCurrent, String.valueOf(page + 1));
        Fas.setSummary(tilePages, String.valueOf(pages));
        countLabel.setText(total + (total == 1 ? " email-like word" : " email-like words"));
        pageLabel.setText(total == 0 ? "" : "Page " + (page + 1) + " of " + pages);
        buildPageBar(pages);
    }

    private void buildPageBar(int pages) {
        pageBar.getChildren().clear();
        if (pages <= 1) {
            return;
        }
        Button prev = Fas.pageButton("Previous");
        prev.setDisable(page == 0);
        prev.setOnAction(e -> {
            page = Math.max(0, page - 1);
            applyFilter();
        });
        Button next = Fas.pageButton("Next");
        next.setDisable(page >= pages - 1);
        next.setOnAction(e -> {
            page = Math.min(pages - 1, page + 1);
            applyFilter();
        });
        pageBar.getChildren().addAll(prev, next);
    }

    private void exportCsv() {
        StringBuilder sb = new StringBuilder("\uFEFFemail,valid,domain,files\n");
        for (EmailRow r : all) {
            sb.append("\"").append(r.email().replace("\"", "\"\"")).append("\",")
                    .append(r.valid() ? "Valid" : "Invalid").append(",")
                    .append(r.domain()).append(",").append(r.fileCount()).append("\n");
        }
        Fas.saveBytes(domainField, "Export Email Words", "email-words.csv",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final class EmailAcc {
        private final String display;
        private final List<Integer> pathIds = new ArrayList<>();

        EmailAcc(String display) {
            this.display = display;
        }

        void add(int pathId) {
            pathIds.add(pathId);
        }

        EmailRow toRow() {
            String domain = display.substring(display.indexOf('@') + 1).toLowerCase();
            return new EmailRow(display, isValid(display, domain), domain,
                    pathIds.size(), List.copyOf(pathIds));
        }

        private static boolean isValid(String email, String domain) {
            int at = email.indexOf('@');
            if (at <= 0 || at == email.length() - 1) {
                return false;
            }
            int dot = domain.lastIndexOf('.');
            if (dot <= 0 || dot == domain.length() - 1) {
                return false;
            }
            String tld = domain.substring(dot + 1);
            if (tld.length() < 2) {
                return false;
            }
            for (char c : tld.toCharArray()) {
                if (!Character.isLetter(c)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One distinct email-like word with the files carrying it. */
    public record EmailRow(String email, boolean valid, String domain,
                            int fileCount, List<Integer> pathIds) {
    }
}
