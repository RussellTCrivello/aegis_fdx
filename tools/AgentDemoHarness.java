import com.aegis.fdx.ai.agent.AgentActivity;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.model.HttpLocalModelProvider;
import com.aegis.fdx.ai.model.ModelConfig;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.store.CorpusDatabase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Drives the agent end-to-end against the seeded workspace and prints the result.
 *
 * <p>Uses a scripted loopback runtime so the run is reproducible without requiring a
 * particular model to be installed. The agent, tools, facades, database and index are
 * all the real ones: only token generation is scripted.
 */
public final class AgentDemoHarness {

    public static void main(String[] args) throws Exception {
        int port = 11733;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        Deque<String> script = new ArrayDeque<>();

        // A genuine multi-step investigation: search, inspect relationships, then answer.
        script.add("I will search the indexed material first.\n"
                + "{\"tool\": \"search_items\", \"arguments\": {\"query\": \"consulting\", \"limit\": 5}}");
        script.add("Now the source breakdown.\n{\"tool\": \"list_sources\", \"arguments\": {}}");
        script.add("And the case totals.\n{\"tool\": \"get_statistics\", \"arguments\": {}}");
        script.add("Seven items mention consulting. All were collected from Acme Consulting BV "
                + "and attributed to the Plaintiff aspect. Every item processed cleanly with no "
                + "errors, and the largest group is plain text (4 of 7).");

        server.createContext("/api/tags", ex ->
                send(ex, "{\"models\":[{\"name\":\"demo-model\"}]}"));
        server.createContext("/api/chat", ex -> {
            ex.getRequestBody().readAllBytes();
            String content = script.isEmpty() ? "No further reply." : script.poll();
            send(ex, "{\"message\":{\"role\":\"assistant\",\"content\":"
                    + json(content) + "},\"done\":true}");
        });
        server.setExecutor(null);
        server.start();

        Path home = Path.of(System.getProperty("user.home"), ".file-analysis", "workspace");
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);

        try (LiveCase c = new LiveCase(home, "workspace", s)) {
            AegisFacades f = AegisFacades.open(c);
            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()),
                    new HttpLocalModelProvider(ModelConfig.defaults()
                            .withEndpoint("http://127.0.0.1:" + port)
                            .withChatModel("demo-model")));

            System.out.println("agent available: " + svc.isAvailable());
            AgentActivity a = svc.ask(
                    "What consulting material is on this case and where did it come from?",
                    AgentContext.ofScreen("Dashboard"));

            System.out.println("\n===== ANSWER =====\n" + a.finalAnswer());
            System.out.println("\n===== ACTIVITY =====\n" + a.toTrace());
            System.out.println("grounded: " + a.isGrounded());
            System.out.println("evidence: " + a.evidence().size() + " record(s)");
            System.out.println("DEMO OK");
        } finally {
            server.stop(0);
        }
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
