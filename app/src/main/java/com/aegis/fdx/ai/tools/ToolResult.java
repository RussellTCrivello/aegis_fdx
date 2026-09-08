package com.aegis.fdx.ai.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * What a tool produced.
 *
 * <p>{@code text} is what the model reads. {@code evidence} is the machine-checkable
 * part: identifiers of real application records, which the interface can render as
 * links and which prove an answer was grounded in data rather than invented.
 */
public record ToolResult(boolean success, String text, List<Evidence> evidence,
                         String error, long millis) {

    /** A reference to a real record. */
    public record Evidence(String kind, String id, String label) {
        public static Evidence item(String id, String label) {
            return new Evidence("item", id, label);
        }

        public static Evidence source(int id, String label) {
            return new Evidence("source", String.valueOf(id), label);
        }

        public static Evidence aspect(int id, String label) {
            return new Evidence("aspect", String.valueOf(id), label);
        }

        public static Evidence category(int id, String label) {
            return new Evidence("category", String.valueOf(id), label);
        }

        public static Evidence keyword(int id, String label) {
            return new Evidence("keyword", String.valueOf(id), label);
        }

        public static Evidence content(int pathId, String label) {
            return new Evidence("content", String.valueOf(pathId), label);
        }

        @Override
        public String toString() {
            return kind + ":" + id;
        }
    }

    public ToolResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static ToolResult ok(String text) {
        return new ToolResult(true, text, List.of(), null, 0);
    }

    public static ToolResult ok(String text, List<Evidence> evidence) {
        return new ToolResult(true, text, evidence, null, 0);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, "", List.of(), error, 0);
    }

    /** No matches. Distinct from failure: the tool worked, the data is not there. */
    public static ToolResult empty(String explanation) {
        return new ToolResult(true, explanation, List.of(), null, 0);
    }

    public ToolResult withTiming(long millis) {
        return new ToolResult(success, text, evidence, error, millis);
    }

    /** Serialised form fed back to the model. */
    public String toObservation() {
        if (!success) {
            return "ERROR: " + error;
        }
        StringBuilder sb = new StringBuilder(text);
        if (!evidence.isEmpty()) {
            sb.append("\n\nRecords: ");
            List<String> refs = new ArrayList<>();
            for (Evidence e : evidence) {
                refs.add(e.toString());
            }
            sb.append(String.join(", ", refs));
        }
        return sb.toString();
    }
}
