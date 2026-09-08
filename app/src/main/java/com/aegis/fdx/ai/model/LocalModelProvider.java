package com.aegis.fdx.ai.model;

/**
 * Supplies the local models the agent uses.
 *
 * <p>The indirection is the point: swapping runtime (an OpenAI-compatible server,
 * an embedded library, a different daemon) means adding one implementation, not
 * touching the agent.
 */
public interface LocalModelProvider {

    /** Short name of the runtime, e.g. {@code "ollama"}. */
    String runtimeName();

    LocalChatModel chatModel();

    /** May return null when no embedding model is configured. */
    EmbeddingProvider embeddingProvider();

    ModelConfig config();

    /** True when the runtime is reachable and the chat model is usable. */
    default boolean isAvailable() {
        return chatModel() != null && chatModel().isAvailable();
    }
}
