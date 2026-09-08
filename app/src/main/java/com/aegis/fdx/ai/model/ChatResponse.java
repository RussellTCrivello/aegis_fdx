package com.aegis.fdx.ai.model;

import java.util.List;

/**
 * What a local chat model returned for one turn.
 *
 * <p>Either the model produced prose ({@code text}) or it asked to run tools
 * ({@code toolCalls}), or both.
 *
 * @param text        the assistant's message; may be blank when only tools were called
 * @param toolCalls   tools the model wants executed, in the order requested
 * @param millis      wall-clock time the runtime took
 * @param promptChars characters sent, for budget diagnostics
 */
public record ChatResponse(String text, List<ToolCall> toolCalls, long millis, int promptChars) {

    public ChatResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public static ChatResponse of(String text, long millis, int promptChars) {
        return new ChatResponse(text, List.of(), millis, promptChars);
    }
}
