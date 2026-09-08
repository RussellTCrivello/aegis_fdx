package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.PathNode;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Router;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The directory structure of everything registered, as a navigable tree.
 *
 * <p>Folder rows carry rolled-up file counts and sizes, so an operator can see where
 * the bulk of the material sits. Double-clicking a file opens its detail destination.
 */
public final class PathAnalysisScreen implements Screen {

    private final AegisFacades facades;
    private final Router router;

    private TreeView<Object> tree;
    private ComboBox<String> sourceBox;
    private ComboBox<String> aspectBox;
    private Label summary;
    private VBox depthChart;

    public PathAnalysisScreen(AegisFacades facades, Router router) {
        this.facades = facades;
        this.router = router;
    }

    @Override public String title() { return "Path Analysis"; }
    @Override public String breadcrumb() { return "Home / Analysis / Path Analysis"; }
    @Override public String icon() { return Icons.FOLDER_OPEN; }

    @Override
    public Node build() {
        sourceBox = new ComboBox<>(FXCollections.observableArrayList("All Sources"));
        sourceBox.setValue("All Sources");
        sourceBox.setPrefWidth(180);
        sourceBox.setOnAction(e -> reload());

        aspectBox = new ComboBox<>(FXCollections.observableArrayList("All Aspects"));
        aspectBox.setValue("All Aspects");
        aspectBox.setPrefWidth(180);
        aspectBox.setOnAction(e -> reload());

        Button expand = Fas.outline("Expand All", null);
        expand.setOnAction(e -> setExpanded(tree.getRoot(), true));
        Button collapse = Fas.outline("Collapse All", null);
        collapse.setOnAction(e -> setExpanded(tree.getRoot(), false));
        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        summary = Fas.muted("");
        depthChart = new VBox(6);

        tree = new TreeView<>();
        tree.setShowRoot(true);
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setCellFactory(t -> new javafx.scene.control.TreeCell<>() {
            @Override protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                if (item instanceof PathNode n) {
                    setText(n.name() + "   (" + n.totalFiles() + " files, "
                            + DashboardScreen.humanBytes(n.totalBytes()) + ")");
                    setGraphic(com.aegis.fdx.ui.Icons.box(
                            n.isContainer() ? Icons.ARCHIVE : Icons.FOLDER, Fas.WARNING, 13));
                } else if (item instanceof PathNode.FileEntry f) {
                    setText(f.name() + "   " + DashboardScreen.humanBytes(f.size())
                            + "   " + (f.status() == null ? "" : f.status()));
                    setGraphic(com.aegis.fdx.ui.Icons.box(Icons.FILE_TEXT, Fas.TEXT_LIGHT, 13));
                }
            }
        });
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<Object> sel = tree.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue() instanceof PathNode.FileEntry f
                        && f.pathId() > 0) {
                    router.openFile(f.pathId());
                }
            }
        });

        VBox treeCard = Fas.cardWithHeader("Directory Structure",
                "Double-click a file to open it", new VBox(10, summary, tree));
        VBox.setVgrow(treeCard, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader("Path Analysis", null,
                        Fas.formField("Source", sourceBox),
                        Fas.formField("Aspect", aspectBox),
                        expand, collapse, refresh),
                new HBox(14, treeCard,
                        Fas.cardWithHeader("Folder Sizes", "Largest folders", depthChart)));
        content.setPadding(new Insets(20));
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    @Override
    public void onShow() {
        try {
            String keepS = sourceBox.getValue();
            sourceBox.getItems().setAll("All Sources");
            facades.sources().listSources().forEach(s -> sourceBox.getItems().add(s.name()));
            sourceBox.setValue(sourceBox.getItems().contains(keepS) ? keepS : "All Sources");

            String keepA = aspectBox.getValue();
            aspectBox.getItems().setAll("All Aspects");
            facades.aspects().listAspects().forEach(a -> aspectBox.getItems().add(a.name()));
            aspectBox.setValue(aspectBox.getItems().contains(keepA) ? keepA : "All Aspects");
        } catch (RuntimeException ignored) {
            // filters are optional
        }
        reload();
    }

    private void reload() {
        try {
            Integer sid = resolve(sourceBox.getValue(), "All Sources", true);
            Integer aid = resolve(aspectBox.getValue(), "All Aspects", false);
            PathNode root = facades.analytics().directoryTree(sid, aid);

            TreeItem<Object> rootItem = toTreeItem(root);
            rootItem.setExpanded(true);
            tree.setRoot(rootItem);

            summary.setText(String.format("%,d file(s) in %,d folder(s), %s, depth %d",
                    root.totalFiles(), root.nodeCount() - 1,
                    DashboardScreen.humanBytes(root.totalBytes()), root.depth()));

            var sizes = com.aegis.fdx.ui.ChartPane.emptyMap();
            collectFolderSizes(root, sizes);
            depthChart.getChildren().setAll(sizes.isEmpty()
                    ? Fas.emptyState("No folders yet.")
                    : com.aegis.fdx.ui.ChartPane.hbars(
                            com.aegis.fdx.ui.ChartPane.top(sizes, 8), 150, null));
        } catch (Exception e) {
            summary.setText("Could not build the tree: " + e.getMessage());
            tree.setRoot(null);
        }
    }

    private static void collectFolderSizes(PathNode node, java.util.Map<String, Integer> out) {
        for (PathNode f : node.folders()) {
            out.put(f.name(), f.totalFiles());
            collectFolderSizes(f, out);
        }
    }

    private Integer resolve(String display, String all, boolean source) {
        if (display == null || all.equals(display)) return null;
        try {
            if (source) {
                return facades.sources().listSources().stream()
                        .filter(s -> display.equals(s.name())).map(s -> s.id())
                        .findFirst().orElse(null);
            }
            return facades.aspects().listAspects().stream()
                    .filter(a -> display.equals(a.name())).map(a -> a.id())
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static TreeItem<Object> toTreeItem(PathNode node) {
        TreeItem<Object> item = new TreeItem<>(node);
        for (PathNode child : node.folders()) {
            item.getChildren().add(toTreeItem(child));
        }
        for (PathNode.FileEntry f : node.files()) {
            item.getChildren().add(new TreeItem<>(f));
        }
        return item;
    }

    private static void setExpanded(TreeItem<Object> item, boolean expanded) {
        if (item == null) return;
        item.setExpanded(expanded);
        for (TreeItem<Object> c : item.getChildren()) {
            setExpanded(c, expanded);
        }
    }
}
