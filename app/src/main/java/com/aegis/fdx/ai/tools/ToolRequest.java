package com.aegis.fdx.ai.tools;

import java.util.Map;

/**
 * Arguments for one tool invocation, plus the interface context it was made from.
 *
 * <p>The context is what lets the operator ask "summarise this" instead of pasting an
 * identifier: whatever is on screen is already available to the tool.
 */
public record ToolRequest(Map<String, String> arguments, AgentContext context) {

    public ToolRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        context = context == null ? AgentContext.empty() : context;
    }

    public static ToolRequest of(Map<String, String> args) {
        return new ToolRequest(args, AgentContext.empty());
    }

    public String get(String name) {
        return arguments.get(name);
    }

    public String get(String name, String fallback) {
        String v = arguments.get(name);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    public int getInt(String name, int fallback) {
        try {
            String v = arguments.get(name);
            return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Integer getInteger(String name) {
        try {
            String v = arguments.get(name);
            return (v == null || v.isBlank()) ? null : Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean has(String name) {
        String v = arguments.get(name);
        return v != null && !v.isBlank();
    }
}
