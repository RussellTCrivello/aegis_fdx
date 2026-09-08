package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.PathNode;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Containers and their nested contents.
 *
 * <p>Built from the engine's own nesting model — {@code parentId} and {@code depth},
 * recorded when archives and mailboxes are expanded. This is a capability the reference
 * application does not have; it is surfaced here rather than discarded.
 */
public final class ArchivesScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private TreeView<Object> tree;
    private Label summary;
    private VBox stats;

    public ArchivesScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router;
    }

    @Override public String title() { return "Archives"; }
    @Override public String breadcrumb() { return "Home / Files / Archives"; }
    @Override public String icon() { return Icons.ARCHIVE; }

    @Override
    public Node build() {
        summary = Fas.muted("");
        stats = new VBox();

        Button expand = Fas.outline("Expand All", null);
        expand.setOnAction(e -> setExpanded(tree.getRoot(), true));
        Button collapse = Fas.outline("Collapse All", null);
        collapse.setOnAction(e -> setExpanded(tree.getRoot(), false));
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        tree = new TreeView<>();
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setCellFactory(t -> new javafx.scene.control.TreeCell<>() {
            @Override protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                if (item instanceof PathNode n) {
                    String type = n.containerType() == null ? "" : " ." + n.containerType();
                    setText(n.name() + type + "   (" + n.totalFiles() + " nested, "
                            + DashboardScreen.humanBytes(n.totalBytes()) + ")");
                    setGraphic(Icons.box(Icons.ARCHIVE, Fas.WARNING, 13));
                } else if (item instanceof PathNode.FileEntry f) {
                    setText(f.name() + "   " + DashboardScreen.humanBytes(f.size())
                            + "   " + (f.status() == null ? "" : f.status()));
                    setGraphic(Icons.box(Icons.FILE_TEXT, Fas.TEXT_LIGHT, 13));
                }
            }
        });

        VBox treeCard = Fas.cardWithHeader("Container Contents",
                "Archives and mailboxes expanded by the ingest pipeline",
                new VBox(10, summary, tree));
        VBox.setVgrow(treeCard, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader("Archives", null, expand, collapse, refresh),
                stats,
                new HBox(14, treeCard));
        content.setPadding(new Insets(20));
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    @Override
    public void onShow() {
        try {
            PathNode root = facades.analytics().archiveTree();
            TreeItem<Object> rootItem = toTreeItem(root);
            rootItem.setExpanded(true);
            tree.setRoot(rootItem);

            int containers = countContainers(root);
            summary.setText(String.format("%,d element(s), %,d container(s), max depth %d",
                    root.totalFiles(), containers, root.depth()));

            stats.getChildren().setAll(Fas.statsGrid(
                    Fas.statCard(Icons.ARCHIVE, Fas.WARNING,
                            String.valueOf(containers), "Containers"),
                    Fas.statCard(Icons.FILES, Fas.PRIMARY,
                            String.format("%,d", root.totalFiles()), "Nested Elements"),
                    Fas.statCard(Icons.DATABASE, Fas.SUCCESS,
                            DashboardScreen.humanBytes(root.totalBytes()), "Total Size"),
                    Fas.statCard(Icons.LIST, Fas.INFO,
                            String.valueOf(root.depth()), "Maximum Depth")));
        } catch (Exception e) {
            summary.setText("Could not build the container tree: " + e.getMessage());
            tree.setRoot(null);
        }
    }

    private static int countContainers(PathNode n) {
        int c = n.isContainer() ? 1 : 0;
        for (PathNode f : n.folders()) c += countContainers(f);
        return c;
    }

    private static TreeItem<Object> toTreeItem(PathNode node) {
        TreeItem<Object> item = new TreeItem<>(node);
        for (PathNode child : node.folders()) item.getChildren().add(toTreeItem(child));
        for (PathNode.FileEntry f : node.files()) item.getChildren().add(new TreeItem<>(f));
        return item;
    }

    private static void setExpanded(TreeItem<Object> item, boolean expanded) {
        if (item == null) return;
        item.setExpanded(expanded);
        for (TreeItem<Object> c : item.getChildren()) setExpanded(c, expanded);
    }
}
