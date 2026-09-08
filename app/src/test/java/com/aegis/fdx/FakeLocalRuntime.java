package com.aegis.fdx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A scripted stand-in for a local model runtime, speaking the same HTTP protocol.
 *
 * <p>Why this exists: agent behaviour must be verifiable without depending on a
 * particular model being installed, and model output is not deterministic. This server
 * replays a fixed script, so the surrounding contract — request shape, tool-call
 * parsing, the observe/continue loop, evidence collection, error handling — can be
 * asserted exactly.
 *
 * <p>It binds to loopback only and serves the endpoints the provider calls:
 * {@code /api/tags}, {@code /api/chat} and {@code /api/embeddings}.
 */
public final class FakeLocalRuntime implements AutoCloseable {

    private final HttpServer server;
    private final Deque<String> scriptedReplies = new ArrayDeque<>();
    private final List<String> receivedBodies = new java.util.ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger requests =
            new java.util.concurrent.atomic.AtomicInteger();
    private final int port;
    private volatile boolean failNext;
    private volatile String rawNext;
    private volatile int delayMillis;

    public FakeLocalRuntime(int port) throws IOException {
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/tags", this::handleTags);
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/embeddings", this::handleEmbeddings);
        server.setExecutor(null);
    }

    public FakeLocalRuntime start() {
        server.start();
        return this;
    }

    public String endpoint() {
        return "http://127.0.0.1:" + port;
    }

    /** Queues the assistant text the runtime will return, in order. */
    public FakeLocalRuntime reply(String content) {
        scriptedReplies.add(content);
        return this;
    }

    /** Makes the next call return HTTP 500. */
    public FakeLocalRuntime failNextCall() {
        this.failNext = true;
        return this;
    }

    /** Makes the next chat call return this exact body (HTTP 200) — for malformed replies. */
    public FakeLocalRuntime rawNextBody(String body) {
        this.rawNext = body;
        return this;
    }

    /** Delays each reply, to exercise timeouts. */
    public FakeLocalRuntime withDelay(int millis) {
        this.delayMillis = millis;
        return this;
    }

    /** Raw request bodies received, so tests can assert what was sent. */
    public List<String> receivedBodies() {
        return List.copyOf(receivedBodies);
    }

    public int callCount() {
        return receivedBodies.size();
    }

    /**
     * Every HTTP request this runtime has served, including availability probes.
     *
     * <p>{@link #callCount()} counts only requests that carried a payload, which is the
     * right measure for "did anything ask the model to think". This counter is the
     * stricter one: it answers "did anything touch the runtime at all", which is what
     * the no-AI-at-startup rule needs.
     */
    public int requestCount() {
        return requests.get();
    }

    private void handleTags(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        send(ex, 200, "{\"models\":[{\"name\":\"test-model\"},{\"name\":\"nomic-embed-text\"}]}");
    }

    private void handleChat(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String body = read(ex.getRequestBody());
        receivedBodies.add(body);
        if (failNext) {
            failNext = false;
            send(ex, 500, "{\"error\":\"scripted failure\"}");
            return;
        }
        if (rawNext != null) {
            String raw = rawNext;
            rawNext = null;
            send(ex, 200, raw);
            return;
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String content = scriptedReplies.isEmpty()
                ? "No further scripted reply."
                : scriptedReplies.poll();
        send(ex, 200, "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":"
                + jsonString(content) + "},\"done\":true}");
    }

    private void handleEmbeddings(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        receivedBodies.add(read(ex.getRequestBody()));
        StringBuilder sb = new StringBuilder("{\"embedding\":[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.4f", Math.sin(i + 1)));
        }
        sb.append("]}");
        send(ex, 200, sb.toString());
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    @Override
    public void close() {
        server.stop(0);
    }

}
