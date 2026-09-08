package com.aegis.fdx.ai.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes one tool to the model: what it does and what it accepts.
 *
 * <p>Rendered into the system prompt so the model can choose correctly, and used to
 * reject malformed calls before anything executes.
 */
public record ToolSchema(String name, String description, List<Param> parameters,
                         boolean mutating) {

    /**
     * @param required when true, a call omitting this parameter is rejected
     */
    public record Param(String name, String type, String description, boolean required,
                        String defaultValue) {

        public static Param required(String name, String type, String description) {
            return new Param(name, type, description, true, null);
        }

        public static Param optional(String name, String type, String description,
                                     String defaultValue) {
            return new Param(name, type, description, false, defaultValue);
        }
    }

    public ToolSchema {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** A read-only tool. */
    public static ToolSchema readOnly(String name, String description, Param... params) {
        return new ToolSchema(name, description, List.of(params), false);
    }

    /** A tool that changes application state; requires explicit confirmation. */
    public static ToolSchema mutating(String name, String description, Param... params) {
        return new ToolSchema(name, description, List.of(params), true);
    }

    /** Compact single-line form for the system prompt. */
    public String toPromptLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('(');
        for (int i = 0; i < parameters.size(); i++) {
            Param p = parameters.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(p.name()).append(':').append(p.type());
            if (!p.required()) {
                sb.append('?');
            }
        }
        sb.append(") — ").append(description);
        if (mutating) {
            sb.append("  [CHANGES DATA — needs confirmation]");
        }
        return sb.toString();
    }

    /** Validates arguments, returning a rejection reason or null when acceptable. */
    public String validate(Map<String, String> args) {
        Map<String, String> given = args == null ? Map.of() : args;
        for (Param p : parameters) {
            if (p.required()) {
                String v = given.get(p.name());
                if (v == null || v.isBlank()) {
                    return "missing required argument '" + p.name() + "'";
                }
            }
            String v = given.get(p.name());
            if (v != null && !v.isBlank() && "integer".equals(p.type())) {
                try {
                    Integer.parseInt(v.trim());
                } catch (NumberFormatException e) {
                    return "argument '" + p.name() + "' must be an integer, got '" + v + "'";
                }
            }
        }
        Map<String, Boolean> known = new LinkedHashMap<>();
        for (Param p : parameters) {
            known.put(p.name(), true);
        }
        for (String k : given.keySet()) {
            if (!known.containsKey(k)) {
                return "unknown argument '" + k + "'";
            }
        }
        return null;
    }
}
