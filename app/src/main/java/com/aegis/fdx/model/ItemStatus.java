package com.aegis.fdx.model;

/** F-07 lifecycle states for the durable ingest queue. */
public enum ItemStatus {
    PENDING("Pending", "#8b93a7"),
    PROCESSING("Processing", "#3b82f6"),
    INDEXED("Indexed", "#22c55e"),
    ERROR("Error", "#ef4444"),
    LOCKED("Locked", "#f59e0b"),
    UNSUPPORTED("Unsupported", "#a855f7");

    private final String label;
    private final String color;

    ItemStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String label() { return label; }
    public String color() { return color; }
}
