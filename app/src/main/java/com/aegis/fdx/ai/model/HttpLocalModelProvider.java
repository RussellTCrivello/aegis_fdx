package com.aegis.fdx.ai.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to a local inference runtime over its HTTP API.
 *
 * <p>Targets the request/response shape used by common local runtimes (Ollama and
 * OpenAI-compatible servers such as {@code llama.cpp}'s bundled server). The endpoint
 * is required to be a loopback address; a remote URL is refused, because the whole
 * point of this layer is that material never leaves the machine.
 *
 * <p>Tool calling is handled in the prompt rather than through a vendor-specific
 * function-calling field: the model is asked to emit a small JSON block, which this
 * class parses. That works across runtimes and small models, where native
 * function-calling support is inconsistent.
 */
public final class HttpLocalModelProvider implements LocalModelProvider {

    private final ModelConfig config;
    private final HttpClient http;
    private final ChatModel chat;
    private final Embeddings embeddings;

    public HttpLocalModelProvider(ModelConfig config) {
        if (!config.isLocalEndpoint()) {
            throw new ModelException(ModelException.Kind.INTERNAL,
                    "refusing a non-loopback AI endpoint: " + config.endpoint()
                            + " — the agent is local-only by design");
        }
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.chat = new ChatModel();
        this.embeddings = new Embeddings();
    }

    public static HttpLocalModelProvider fromEnvironment() {
        return new HttpLocalModelProvider(ModelConfig.fromEnvironment());
    }

    @Override
    public String runtimeName() {
        return "local-http";
    }

    @Override
    public LocalChatModel chatModel() {
        return chat;
    }

    @Override
    public EmbeddingProvider embeddingProvider() {
        return embeddings;
    }

    @Override
    public ModelConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ chat

    private final class ChatModel implements LocalChatModel {

        private volatile String lastFailure;

        @Override
        public String modelId() {
            return config.chatModel();
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<String> toolSpecs) {
            String body = buildChatRequest(messages, toolSpecs);
            long t0 = System.nanoTime();
            String raw = post("/api/chat", body);
            long millis = (System.nanoTime() - t0) / 1_000_000;

            Object root = Json.parse(raw);
            String content = Json.getString(root, "message.content");
            if (content == null) {
                // OpenAI-compatible shape
                List<Object> choices = Json.getList(root, "choices");
                if (!choices.isEmpty()) {
                    content = Json.getString(choices.get(0), "message.content");
                }
            }
            if (content == null) {
                throw new ModelException(ModelException.Kind.BAD_RESPONSE,
                        "runtime response contained no message content");
            }
            List<ToolCall> calls = parseToolCalls(content);
            String prose = stripToolCalls(content);
            return new ChatResponse(prose, calls, millis, body.length());
        }

        @Override
        public boolean isAvailable() {
            try {
                String raw = get("/api/tags");
                if (raw == null) {
                    lastFailure = "no response from " + config.endpoint();
                    return false;
                }
                // When the runtime lists models, check ours is among them.
                if (raw.contains("\"models\"")) {
                    String wanted = config.chatModel();
                    String base = wanted.contains(":") ? wanted.substring(0, wanted.indexOf(':')) : wanted;
                    if (!raw.contains(wanted) && !raw.contains(base)) {
                        lastFailure = "model '" + wanted + "' is not installed in the local runtime";
                        return false;
                    }
                }
                lastFailure = null;
                return true;
            } catch (ModelException e) {
                lastFailure = e.getMessage();
                return false;
            }
        }

        @Override
        public String unavailableReason() {
            return lastFailure;
        }
    }

    private String buildChatRequest(List<ChatMessage> messages, List<String> toolSpecs) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{')
                .append("\"model\":").append(Json.str(config.chatModel())).append(',')
                .append("\"stream\":false,")
                .append("\"options\":{")
                .append("\"temperature\":").append(config.temperature()).append(',')
                .append("\"num_predict\":").append(config.maxOutputTokens()).append(',')
                .append("\"num_ctx\":").append(config.contextTokens())
                .append("},")
                .append("\"messages\":[");
        boolean first = true;
        for (ChatMessage m : messages) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('{')
                    .append("\"role\":").append(Json.str(roleOf(m))).append(',')
                    .append("\"content\":").append(Json.str(contentOf(m)))
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String roleOf(ChatMessage m) {
        // Tool observations are fed back as user turns: small local models handle that
        // more reliably than a dedicated tool role, which many runtimes drop.
        return switch (m.role()) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case USER, TOOL -> "user";
        };
    }

    private static String contentOf(ChatMessage m) {
        if (m.role() == ChatMessage.Role.TOOL) {
            return "TOOL RESULT (" + m.name() + "):\n" + m.content();
        }
        return m.content();
    }

    // ------------------------------------------------------------ embeddings

    private final class Embeddings implements EmbeddingProvider {

        @Override
        public String modelId() {
            return config.embeddingModel();
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            List<float[]> out = new ArrayList<>(texts.size());
            for (String t : texts) {
                String body = "{\"model\":" + Json.str(config.embeddingModel())
                        + ",\"prompt\":" + Json.str(t) + "}";
                String raw = post("/api/embeddings", body);
                List<Object> vec = Json.getList(Json.parse(raw), "embedding");
                float[] f = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    f[i] = vec.get(i) instanceof Number n ? n.floatValue() : 0f;
                }
                out.add(f);
            }
            return out;
        }

        @Override
        public boolean isAvailable() {
            try {
                embedOne("probe");
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    // ---------------------------------------------------------------- transport

    private String post(String path, String body) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint() + path))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req);
    }

    private String get(String path) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint() + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return send(req);
    }

    private String send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                throw new ModelException(ModelException.Kind.MODEL_UNAVAILABLE,
                        "local runtime has no endpoint " + req.uri().getPath()
                                + " (is the model installed?)");
            }
            if (res.statusCode() >= 400) {
                throw new ModelException(ModelException.Kind.BAD_RESPONSE,
                        "local runtime returned HTTP " + res.statusCode());
            }
            return res.body();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelException(ModelException.Kind.TIMEOUT,
                    "local model did not respond within " + config.timeout().toSeconds() + "s", e);
        } catch (java.net.ConnectException e) {
            throw new ModelException(ModelException.Kind.RUNTIME_UNAVAILABLE,
                    "no local AI runtime at " + config.endpoint()
                            + " — start one, or disable the agent with -Daegis.ai.enabled=false", e);
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.RUNTIME_UNAVAILABLE,
                    "cannot reach the local AI runtime: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Kind.CANCELLED, "request interrupted", e);
        }
    }

    // ------------------------------------------------------------ tool parsing

    /** Matches the fenced or bare JSON block the agent asks the model to emit. */
    private static final Pattern TOOL_BLOCK = Pattern.compile(
            "\\{\\s*\"tool\"\\s*:\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*(?:,\\s*\"arguments\"\\s*:\\s*(\\{[^{}]*\\}))?\\s*\\}",
            Pattern.DOTALL);

    /**
     * Extracts tool requests from model output.
     *
     * <p>Public and static so the parsing contract can be tested without a running
     * model — the surrounding behaviour is deterministic even though generation is not.
     */
    public static List<ToolCall> parseToolCalls(String content) {
        List<ToolCall> calls = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return calls;
        }
        Matcher m = TOOL_BLOCK.matcher(content);
        while (m.find()) {
            String tool = m.group(1);
            Map<String, String> args = new LinkedHashMap<>();
            String argJson = m.group(2);
            if (argJson != null) {
                Object parsed = Json.parse(argJson);
                if (parsed instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        Object v = e.getValue();
                        if (v != null) {
                            args.put(String.valueOf(e.getKey()), asScalar(v));
                        }
                    }
                }
            }
            calls.add(new ToolCall(tool, args, m.group()));
        }
        return calls;
    }

    /** Removes tool blocks so only the model's prose is shown to the operator. */
    public static String stripToolCalls(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = TOOL_BLOCK.matcher(content).replaceAll("").trim();
        cleaned = cleaned.replaceAll("```json\\s*```", "").replaceAll("```\\s*```", "");
        return cleaned.trim();
    }

    private static String asScalar(Object v) {
        if (v instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(v);
    }
}
