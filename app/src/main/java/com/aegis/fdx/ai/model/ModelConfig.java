package com.aegis.fdx.ai.model;

import java.time.Duration;

/**
 * Where the local model runtime lives and how it should behave.
 *
 * <p>Every value is overridable so the deployment, not the code, decides which model
 * runs. Defaults target a runtime listening on the loopback interface.
 *
 * <p>System properties, checked in {@link #fromEnvironment()}:
 * <ul>
 *   <li>{@code aegis.ai.endpoint} — base URL of the local runtime</li>
 *   <li>{@code aegis.ai.model} — chat model identifier</li>
 *   <li>{@code aegis.ai.embedModel} — embedding model identifier</li>
 *   <li>{@code aegis.ai.timeoutSeconds} — per-request budget</li>
 *   <li>{@code aegis.ai.contextTokens} — usable context window</li>
 *   <li>{@code aegis.ai.enabled} — set false to disable the agent entirely</li>
 * </ul>
 */
public record ModelConfig(
        String endpoint,
        String chatModel,
        String embeddingModel,
        Duration timeout,
        int contextTokens,
        double temperature,
        int maxOutputTokens,
        boolean enabled) {

    /** Loopback only: a cloud endpoint would violate the offline requirement. */
    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:11434";

    /**
     * Default chat model. Chosen for instruction-following and tool-calling quality at
     * a size that runs on CPU; replaceable without code changes.
     */
    public static final String DEFAULT_CHAT_MODEL = "qwen2.5:7b-instruct";

    /** Default embedding model for semantic retrieval. */
    public static final String DEFAULT_EMBEDDING_MODEL = "nomic-embed-text";

    public ModelConfig {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (contextTokens < 512) {
            throw new IllegalArgumentException("contextTokens must be at least 512");
        }
    }

    /** Defaults suitable for a workstation running a local runtime. */
    public static ModelConfig defaults() {
        return new ModelConfig(DEFAULT_ENDPOINT, DEFAULT_CHAT_MODEL, DEFAULT_EMBEDDING_MODEL,
                Duration.ofSeconds(120), 8192, 0.1, 1024, true);
    }

    /** Defaults overlaid with any system properties that are set. */
    public static ModelConfig fromEnvironment() {
        ModelConfig d = defaults();
        return new ModelConfig(
                prop("aegis.ai.endpoint", d.endpoint()),
                prop("aegis.ai.model", d.chatModel()),
                prop("aegis.ai.embedModel", d.embeddingModel()),
                Duration.ofSeconds(intProp("aegis.ai.timeoutSeconds", (int) d.timeout().toSeconds())),
                intProp("aegis.ai.contextTokens", d.contextTokens()),
                d.temperature(),
                intProp("aegis.ai.maxOutputTokens", d.maxOutputTokens()),
                !"false".equalsIgnoreCase(prop("aegis.ai.enabled", "true")));
    }

    /** True when the endpoint is a loopback address. */
    public boolean isLocalEndpoint() {
        String e = endpoint.toLowerCase();
        return e.startsWith("http://127.0.0.1") || e.startsWith("http://localhost")
                || e.startsWith("http://[::1]") || e.startsWith("http://0.0.0.0");
    }

    public ModelConfig withChatModel(String model) {
        return new ModelConfig(endpoint, model, embeddingModel, timeout, contextTokens,
                temperature, maxOutputTokens, enabled);
    }

    public ModelConfig withEndpoint(String url) {
        return new ModelConfig(url, chatModel, embeddingModel, timeout, contextTokens,
                temperature, maxOutputTokens, enabled);
    }

    public ModelConfig withTimeout(Duration t) {
        return new ModelConfig(endpoint, chatModel, embeddingModel, t, contextTokens,
                temperature, maxOutputTokens, enabled);
    }

    private static String prop(String key, String fallback) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static int intProp(String key, int fallback) {
        try {
            String v = System.getProperty(key);
            return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
