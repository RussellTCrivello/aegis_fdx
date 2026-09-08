package com.aegis.fdx.facade;

/**
 * Review state of a registered file.
 *
 * <p>Distinct from {@link com.aegis.fdx.model.ItemStatus}, which records what the
 * ingest pipeline did with an element. This records what the <em>reviewer</em> has
 * done with it.
 */
public enum FileState {
    UNREAD("Unread"),
    READ("Read");

    private final String label;

    FileState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static FileState fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return UNREAD;
        }
        for (FileState s : values()) {
            if (s.label.equalsIgnoreCase(label.trim())) {
                return s;
            }
        }
        throw FacadeException.validation("unknown file state: " + label);
    }
}
