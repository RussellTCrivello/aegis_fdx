package com.aegis.fdx.facade;

/** Notification priority. */
public enum Priority {
    LOW("low"), NORMAL("normal"), HIGH("high"), CRITICAL("critical");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Priority fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return NORMAL;
        }
        for (Priority p : values()) {
            if (p.label.equalsIgnoreCase(label.trim())) {
                return p;
            }
        }
        return NORMAL;
    }
}
