package com.aegis.fdx.facade.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in a directory or container tree.
 *
 * <p>Mutable during construction, because a tree is assembled incrementally from a flat
 * result set; {@link #recomputeTotals()} rolls counts and bytes up once building is
 * finished.
 */
public final class PathNode {

    /** A leaf: one registered file or one ingested element. */
    public record FileEntry(int pathId, String name, String type, long size,
                            String status, String elementId, String sourceName,
                            String aspectName) {
    }

    private final String name;
    private final String fullPath;
    private final Map<String, PathNode> folders = new LinkedHashMap<>();
    private final List<FileEntry> files = new ArrayList<>();

    private int totalFiles;
    private long totalBytes;

    /** Set when this node is itself an ingested container rather than a plain folder. */
    private String containerElementId;
    private String containerType;
    private long containerSize;

    private PathNode(String name, String fullPath) {
        this.name = name;
        this.fullPath = fullPath;
    }

    public static PathNode folder(String name, String fullPath) {
        return new PathNode(name, fullPath);
    }

    /** Returns the named child, creating it when absent. */
    public PathNode childFolder(String childName, String childPath) {
        return folders.computeIfAbsent(childName, n -> new PathNode(n, childPath));
    }

    public void addFile(FileEntry entry) {
        files.add(entry);
    }

    /** Marks this node as an archive or mailbox rather than a filesystem folder. */
    public void markContainer(String elementId, String type, long size) {
        this.containerElementId = elementId;
        this.containerType = type;
        this.containerSize = size;
    }

    /** Rolls file counts and byte totals up through the tree. Call once, on the root. */
    public void recomputeTotals() {
        totalFiles = files.size();
        totalBytes = 0;
        for (FileEntry f : files) {
            totalBytes += f.size();
        }
        for (PathNode child : folders.values()) {
            child.recomputeTotals();
            totalFiles += child.totalFiles();
            totalBytes += child.totalBytes();
        }
    }

    public String name() {
        return name;
    }

    public String fullPath() {
        return fullPath;
    }

    public List<PathNode> folders() {
        return List.copyOf(folders.values());
    }

    public List<FileEntry> files() {
        return List.copyOf(files);
    }

    public int totalFiles() {
        return totalFiles;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public boolean isContainer() {
        return containerElementId != null;
    }

    public String containerElementId() {
        return containerElementId;
    }

    public String containerType() {
        return containerType;
    }

    public long containerSize() {
        return containerSize;
    }

    public boolean isLeaf() {
        return folders.isEmpty();
    }

    /** Total nodes including this one, for tests and diagnostics. */
    public int nodeCount() {
        int n = 1;
        for (PathNode c : folders.values()) {
            n += c.nodeCount();
        }
        return n;
    }

    /** Depth of the deepest branch; a root with only files is depth 1. */
    public int depth() {
        int max = 0;
        for (PathNode c : folders.values()) {
            max = Math.max(max, c.depth());
        }
        return max + 1;
    }

    @Override
    public String toString() {
        return name + " (" + totalFiles + " files)";
    }
}
