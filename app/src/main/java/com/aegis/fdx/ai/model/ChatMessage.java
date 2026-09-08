package com.aegis.fdx.ai.model;

/**
 * One turn in a conversation with a local model.
 *
 * @param role    who produced the message
 * @param content the text; for {@link Role#TOOL} this is the serialised tool result
 * @param name    tool name when {@code role} is {@link Role#TOOL}, otherwise null
 */
public record ChatMessage(Role role, String content, String name) {

    public enum Role {
        /** Instructions that frame the whole exchange. */
        SYSTEM,
        /** Something the operator asked. */
        USER,
        /** Something the model produced. */
        ASSISTANT,
        /** The observed result of running a tool. */
        TOOL
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null);
    }

    public static ChatMessage tool(String toolName, String content) {
        return new ChatMessage(Role.TOOL, content, toolName);
    }
}
