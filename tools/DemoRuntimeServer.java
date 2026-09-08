import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A standalone scripted local runtime, for capturing the Assistant screen in a
 * working state without requiring a particular model to be installed.
 *
 * <p>Binds to loopback only. Everything below the model — agent, tools, facades,
 * database, index — is the real implementation.
 */
public final class DemoRuntimeServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 11434;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        Deque<String> script = new ArrayDeque<>();

        script.add("{\"tool\": \"search_items\", \"arguments\": {\"query\": \"consulting\", \"limit\": 5}}");
        script.add("{\"tool\": \"list_sources\", \"arguments\": {}}");
        script.add("Seven items on this case mention consulting.\n\n"
                + "They were all collected from Acme Consulting BV and attributed to the "
                + "Plaintiff aspect. The set covers four plain-text documents, one CSV "
                + "statement, one HTML policy and one email message.\n\n"
                + "Every item was processed and indexed without error, so nothing is "
                + "outstanding for this query.");

        server.createContext("/api/tags", ex ->
                send(ex, "{\"models\":[{\"name\":\"qwen2.5:7b-instruct\"}]}"));
        server.createContext("/api/chat", ex -> {
            ex.getRequestBody().readAllBytes();
            String content = script.isEmpty()
                    ? "I have nothing further to add."
                    : script.poll();
            send(ex, "{\"message\":{\"role\":\"assistant\",\"content\":"
                    + json(content) + "},\"done\":true}");
        });
        server.setExecutor(null);
        server.start();
        System.out.println("scripted local runtime on http://127.0.0.1:" + port);
        Thread.currentThread().join();
    }

    private static void send(HttpExchange ex, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }
}
