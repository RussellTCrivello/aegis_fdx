package com.aegis.fdx.ai.model;

import java.util.List;

/**
 * A local text-generation model.
 *
 * <p>Implementations must run inference on this machine. An implementation that calls
 * a remote service breaks the offline guarantee and does not belong here.
 */
public interface LocalChatModel {

    /** Identifier of the model actually in use, for the audit trail. */
    String modelId();

    /**
     * Generates one turn.
     *
     * @param messages  the conversation so far, oldest first
     * @param toolSpecs tool descriptions the model may call; empty disables tool use
     * @throws ModelException if the runtime is unavailable, times out, or misbehaves
     */
    ChatResponse chat(List<ChatMessage> messages, List<String> toolSpecs);

    /** True when the runtime answers and the configured model is loadable. */
    boolean isAvailable();

    /** Human-readable reason {@link #isAvailable()} is false; null when it is true. */
    String unavailableReason();
}
