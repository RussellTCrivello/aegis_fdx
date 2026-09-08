package com.aegis.fdx.ai.model;

import java.util.Map;

/**
 * A model's request to run one application tool.
 *
 * @param tool      the tool's registered name
 * @param arguments argument name to value, already parsed out of the model's output
 * @param rawText   the model text this was parsed from, kept for the audit trail
 */
public record ToolCall(String tool, Map<String, String> arguments, String rawText) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public String arg(String name) {
        return arguments.get(name);
    }

    public String arg(String name, String fallback) {
        String v = arguments.get(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public int intArg(String name, int fallback) {
        try {
            String v = arguments.get(name);
            return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return tool + arguments;
    }
}
