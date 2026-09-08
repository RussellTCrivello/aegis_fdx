package com.aegis.fdx;

import com.aegis.fdx.ai.agent.AgentActivity;
import com.aegis.fdx.ai.agent.AgentOrchestrator;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.model.ChatMessage;
import com.aegis.fdx.ai.model.ChatResponse;
import com.aegis.fdx.ai.model.HttpLocalModelProvider;
import com.aegis.fdx.ai.model.LocalChatModel;
import com.aegis.fdx.ai.model.LocalModelProvider;
import com.aegis.fdx.ai.model.ModelConfig;
import com.aegis.fdx.ai.model.ModelException;
import com.aegis.fdx.ai.model.ToolCall;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ai.tools.AgentTool;
import com.aegis.fdx.ai.tools.CaseTools;
import com.aegis.fdx.ai.tools.MutatingTools;
import com.aegis.fdx.ai.tools.ToolRequest;
import com.aegis.fdx.ai.tools.ToolResult;
import com.aegis.fdx.ai.tools.ToolSchema;
import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the local AI agent.
 *
 * <p>Model generation is not deterministic, so these tests assert the surrounding
 * contracts instead: the wire protocol, tool-call parsing, the multi-step loop, schema
 * validation, the read-only safety boundary, evidence grounding, failure handling and
 * offline behaviour. A scripted local HTTP runtime stands in for the model so the
 * assertions are exact.
 */
class AiAgentTest {

    private static int nextPort = 11700;

    private static synchronized int port() {
        return nextPort++;
    }

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Agent", s);
    }

    /** A case with real processed material and the five concepts populated. */
    private AegisFacades seeded(LiveCase c, Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev);
        Files.writeString(ev.resolve("invoice.txt"),
                "Invoice 2024 from Acme for consulting services. Payment due in 30 days.",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("memo.txt"),
                "Internal memo about the Acme consulting agreement and its payment terms.",
                StandardCharsets.UTF_8);

        AegisFacades f = AegisFacades.open(c);
        int src = f.sources().createSource("Acme", "NL", "custodian", 0.8);
        int asp = f.aspects().createAspect("Plaintiff", 0.9);
        f.categories().createCategory("finance");
        f.keywords().createKeyword("payment due", "finance");
        f.processing("Acme", "Plaintiff").processFolder(ev.toString());
        f.contents().registerIngestedItems(src, asp);
        return f;
    }

    // ============================================================ wire protocol

    @Test
    @DisplayName("Provider speaks the local runtime protocol and parses its reply")
    void wireProtocol(@TempDir Path tmp) throws Exception {
        try (FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            rt.reply("The case contains seven items.");
            ModelConfig cfg = ModelConfig.defaults()
                    .withEndpoint(rt.endpoint()).withChatModel("test-model");
            LocalModelProvider p = new HttpLocalModelProvider(cfg);

            assertTrue(p.chatModel().isAvailable(), "runtime should report available");

            ChatResponse r = p.chatModel().chat(
                    List.of(ChatMessage.system("sys"), ChatMessage.user("how many items?")),
                    List.of());
            assertEquals("The case contains seven items.", r.text());
            assertFalse(r.hasToolCalls());

            // the request carried the model, both turns and the no-streaming flag
            String sent = rt.receivedBodies().get(0);
            assertTrue(sent.contains("\"model\":\"test-model\""));
            assertTrue(sent.contains("\"stream\":false"));
            assertTrue(sent.contains("how many items?"));
            assertTrue(sent.contains("\"role\":\"system\""));
        }
    }

    @Test
    @DisplayName("A non-loopback endpoint is refused outright")
    void refusesRemoteEndpoint() {
        ModelConfig remote = ModelConfig.defaults().withEndpoint("https://api.example.com");
        ModelException e = assertThrows(ModelException.class,
                () -> new HttpLocalModelProvider(remote));
        assertTrue(e.getMessage().contains("local-only"),
                "should explain why a remote endpoint is rejected");
    }

    @Test
    @DisplayName("Runtime failures are reported as typed, actionable errors")
    void runtimeFailures() throws Exception {
        // nothing listening
        ModelConfig dead = ModelConfig.defaults()
                .withEndpoint("http://127.0.0.1:" + port()).withChatModel("test-model");
        LocalModelProvider p = new HttpLocalModelProvider(dead);
        assertFalse(p.chatModel().isAvailable());
        assertNotNull(p.chatModel().unavailableReason());

        ModelException e = assertThrows(ModelException.class,
                () -> p.chatModel().chat(List.of(ChatMessage.user("hi")), List.of()));
        assertEquals(ModelException.Kind.RUNTIME_UNAVAILABLE, e.kind());
        assertTrue(e.isSetupProblem());

        // reachable but erroring
        try (FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            rt.failNextCall();
            LocalModelProvider bad = new HttpLocalModelProvider(ModelConfig.defaults()
                    .withEndpoint(rt.endpoint()).withChatModel("test-model"));
            ModelException be = assertThrows(ModelException.class,
                    () -> bad.chatModel().chat(List.of(ChatMessage.user("hi")), List.of()));
            assertEquals(ModelException.Kind.BAD_RESPONSE, be.kind());
        }
    }

    @Test
    @DisplayName("A slow runtime produces a timeout, not a hang")
    void timeout() throws Exception {
        try (FakeLocalRuntime rt = new FakeLocalRuntime(port()).withDelay(1500).start()) {
            rt.reply("too late");
            LocalModelProvider p = new HttpLocalModelProvider(ModelConfig.defaults()
                    .withEndpoint(rt.endpoint()).withChatModel("test-model")
                    .withTimeout(Duration.ofMillis(300)));
            ModelException e = assertThrows(ModelException.class,
                    () -> p.chatModel().chat(List.of(ChatMessage.user("hi")), List.of()));
            assertEquals(ModelException.Kind.TIMEOUT, e.kind());
        }
    }

    // =========================================================== tool call parsing

    @Test
    @DisplayName("Tool calls are parsed out of model text; prose is preserved")
    void toolCallParsing() {
        List<ToolCall> calls = HttpLocalModelProvider.parseToolCalls(
                "Let me check.\n{\"tool\": \"search_items\", "
                        + "\"arguments\": {\"query\": \"invoice\", \"limit\": 5}}");
        assertEquals(1, calls.size());
        assertEquals("search_items", calls.get(0).tool());
        assertEquals("invoice", calls.get(0).arg("query"));
        assertEquals(5, calls.get(0).intArg("limit", 0),
                "numeric arguments arrive without a decimal tail");

        assertEquals("Let me check.", HttpLocalModelProvider.stripToolCalls(
                "Let me check.\n{\"tool\": \"search_items\", \"arguments\": {\"query\": \"x\"}}"));

        // no-argument form
        List<ToolCall> none = HttpLocalModelProvider.parseToolCalls("{\"tool\": \"get_statistics\"}");
        assertEquals(1, none.size());
        assertTrue(none.get(0).arguments().isEmpty());

        // several in one turn
        assertEquals(2, HttpLocalModelProvider.parseToolCalls(
                "{\"tool\": \"list_sources\"} and {\"tool\": \"list_aspects\"}").size());

        // plain prose yields none
        assertTrue(HttpLocalModelProvider.parseToolCalls("Just an answer.").isEmpty());
    }

    // ============================================================ tool contracts

    @Test
    @DisplayName("Every tool exposes a schema and validates its arguments")
    void toolSchemas(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            List<AgentTool> tools = new CaseTools(f, new CorpusDatabase(c.db())).all();
            assertEquals(10, tools.size(), "expected the full read-only tool set");

            for (AgentTool t : tools) {
                assertNotNull(t.name());
                assertNotNull(t.schema());
                assertFalse(t.schema().description().isBlank(),
                        t.name() + " must describe itself for the model");
                assertFalse(t.isMutating(), t.name() + " must be read-only");
                assertTrue(t.schema().toPromptLine().startsWith(t.name()));
            }

            ToolSchema search = tools.get(0).schema();
            assertEquals("search_items", search.name());
            assertNotNull(search.validate(Map.of()), "missing required arg must be rejected");
            assertNull(search.validate(Map.of("query", "invoice")));
            assertNotNull(search.validate(Map.of("query", "x", "limit", "abc")),
                    "non-numeric integer must be rejected");
            assertNotNull(search.validate(Map.of("query", "x", "bogus", "1")),
                    "unknown argument must be rejected");
        }
    }

    @Test
    @DisplayName("Tools return real records from the case, with evidence identifiers")
    void toolsReturnRealData(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = seeded(c, tmp);
            CorpusDatabase dao = new CorpusDatabase(c.db());
            Map<String, AgentTool> tools = new java.util.LinkedHashMap<>();
            for (AgentTool t : new CaseTools(f, dao).all()) {
                tools.put(t.name(), t);
            }

            ToolResult search = tools.get("search_items")
                    .execute(ToolRequest.of(Map.of("query", "consulting")));
            assertTrue(search.success());
            assertFalse(search.evidence().isEmpty(), "search must cite the items it found");
            assertTrue(search.text().contains("invoice.txt") || search.text().contains("memo.txt"));

            ToolResult stats = tools.get("get_statistics").execute(ToolRequest.of(Map.of()));
            assertTrue(stats.success());
            assertTrue(stats.text().contains("total items"));

            ToolResult sources = tools.get("list_sources").execute(ToolRequest.of(Map.of()));
            assertTrue(sources.text().contains("Acme"));
            assertEquals("source", sources.evidence().get(0).kind());

            String itemId = search.evidence().get(0).id();
            ToolResult content = tools.get("get_content")
                    .execute(ToolRequest.of(Map.of("itemId", itemId)));
            assertTrue(content.success());
            assertTrue(content.text().toLowerCase().contains("consulting"),
                    "content tool must return the real extracted text");

            ToolResult rel = tools.get("get_relationships")
                    .execute(ToolRequest.of(Map.of("itemId", itemId)));
            assertTrue(rel.success());
            assertTrue(rel.text().contains("Acme"), "relationships must resolve the source");
            assertTrue(rel.text().contains("Plaintiff"), "relationships must resolve the aspect");
        }
    }

    @Test
    @DisplayName("A tool reports 'no data' distinctly from 'failed'")
    void emptyVersusFailure(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            Map<String, AgentTool> tools = new java.util.LinkedHashMap<>();
            for (AgentTool t : new CaseTools(f, new CorpusDatabase(c.db())).all()) {
                tools.put(t.name(), t);
            }
            ToolResult empty = tools.get("list_sources").execute(ToolRequest.of(Map.of()));
            assertTrue(empty.success(), "an empty case is not an error");
            assertTrue(empty.text().contains("No sources"));
            assertTrue(empty.evidence().isEmpty());

            ToolResult failed = tools.get("get_item").execute(ToolRequest.of(Map.of()));
            assertFalse(failed.success(), "a missing required input is an error");
            assertNotNull(failed.error());
        }
    }

    // ============================================================ safety boundary

    @Test
    @DisplayName("Mutating tools are unreachable unless the operator confirms")
    void readOnlyByDefault(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = seeded(c, tmp);
            CorpusDatabase dao = new CorpusDatabase(c.db());
            AgentService svc = new AgentService(f, dao, null);

            assertFalse(svc.availableTools(AgentContext.empty()).contains("classify_file"),
                    "a plain question must not expose data-changing tools");
            assertTrue(svc.availableTools(AgentContext.empty().allowingMutations())
                            .contains("classify_file"),
                    "confirmed context should expose them");

            // even called directly, a mutating tool refuses without permission
            AgentTool classify = new MutatingTools(f, dao).all().get(0);
            ToolResult denied = classify.execute(new ToolRequest(
                    Map.of("pathId", "1", "categoryId", "1"), AgentContext.empty()));
            assertFalse(denied.success());
            assertTrue(denied.error().contains("not permitted"));

            // and works once permitted
            int pathId = f.contents().getPaths().results().get(0).id();
            int catId = f.categories().listCategories().results().get(0).id();
            ToolResult allowed = classify.execute(new ToolRequest(
                    Map.of("pathId", String.valueOf(pathId), "categoryId", String.valueOf(catId)),
                    AgentContext.empty().allowingMutations()));
            assertTrue(allowed.success(), allowed.error());
            assertEquals(1, dao.selectPathCategories(pathId).size(),
                    "the confirmed action must actually persist");
        }
    }

    @Test
    @DisplayName("The agent has no tool for shell, SQL, filesystem or network access")
    void noEscapeHatches(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            CorpusDatabase dao = new CorpusDatabase(c.db());
            List<String> names = new java.util.ArrayList<>();
            for (AgentTool t : new CaseTools(f, dao).all()) {
                names.add(t.name());
            }
            for (AgentTool t : new MutatingTools(f, dao).all()) {
                names.add(t.name());
            }
            for (String forbidden : List.of("sql", "query_database", "shell", "exec",
                    "command", "read_file", "write_file", "http", "fetch", "eval")) {
                assertFalse(names.stream().anyMatch(n -> n.toLowerCase().contains(forbidden)),
                        "no tool may expose " + forbidden);
            }
        }
    }

    // ============================================================== agent loop

    @Test
    @DisplayName("The agent runs a multi-step loop: tool, observe, tool, then answer")
    void multiStepLoop(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = seeded(c, tmp);
            CorpusDatabase dao = new CorpusDatabase(c.db());

            rt.reply("I will search first.\n{\"tool\": \"search_items\", "
                            + "\"arguments\": {\"query\": \"consulting\"}}")
              .reply("Now the sources.\n{\"tool\": \"list_sources\", \"arguments\": {}}")
              .reply("Two documents mention consulting, both from Acme.");

            AgentService svc = new AgentService(f, dao, new HttpLocalModelProvider(
                    ModelConfig.defaults().withEndpoint(rt.endpoint()).withChatModel("test-model")));
            assertTrue(svc.isAvailable());

            AgentActivity a = svc.ask("What consulting material is on the case?",
                    AgentContext.ofScreen("Dashboard"));

            assertFalse(a.failed(), a.failure());
            assertEquals(2, a.steps().size(), "two tools should have run");
            assertEquals("search_items", a.steps().get(0).tool());
            assertEquals("list_sources", a.steps().get(1).tool());
            assertTrue(a.steps().get(0).success());
            assertEquals(3, a.modelCalls(), "three model turns: two tool rounds plus the answer");
            assertTrue(a.finalAnswer().contains("Acme"));
            assertTrue(a.isGrounded(), "the run must cite real records");
            assertFalse(a.evidence().isEmpty());
            assertTrue(a.toTrace().contains("search_items"));
        }
    }

    @Test
    @DisplayName("An unknown tool name is corrected, not fatal")
    void unknownToolRecovers(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = seeded(c, tmp);
            rt.reply("{\"tool\": \"delete_everything\", \"arguments\": {}}")
              .reply("{\"tool\": \"get_statistics\", \"arguments\": {}}")
              .reply("The case has material indexed.");

            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()),
                    new HttpLocalModelProvider(ModelConfig.defaults()
                            .withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("summarise", AgentContext.empty());

            assertFalse(a.failed());
            assertEquals(2, a.steps().size());
            assertFalse(a.steps().get(0).success(), "the bogus tool must fail");
            assertTrue(a.steps().get(1).success(), "the agent must recover and continue");
        }
    }

    @Test
    @DisplayName("Malformed arguments are rejected before the tool executes")
    void argumentValidationInLoop(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = seeded(c, tmp);
            rt.reply("{\"tool\": \"search_items\", \"arguments\": {\"limit\": \"10\"}}")
              .reply("I could not run that search.");

            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()),
                    new HttpLocalModelProvider(ModelConfig.defaults()
                            .withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("find things", AgentContext.empty());

            assertEquals(1, a.steps().size());
            assertFalse(a.steps().get(0).success());
            assertTrue(a.steps().get(0).summary().contains("missing required argument"));
        }
    }

    @Test
    @DisplayName("The loop is bounded and cannot spin forever")
    void stepBudget(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = seeded(c, tmp);
            for (int i = 0; i < 20; i++) {
                rt.reply("{\"tool\": \"get_statistics\", \"arguments\": {}}");
            }
            rt.reply("Final answer after the budget was reached.");

            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()),
                    new HttpLocalModelProvider(ModelConfig.defaults()
                            .withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("loop please", AgentContext.empty());

            assertFalse(a.failed());
            assertTrue(a.steps().size() <= AgentOrchestrator.DEFAULT_MAX_STEPS,
                    "must stop at the step budget, got " + a.steps().size());
        }
    }

    @Test
    @DisplayName("A run can be cancelled mid-flight")
    void cancellation(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = seeded(c, tmp);
            rt.reply("{\"tool\": \"get_statistics\", \"arguments\": {}}").reply("done");

            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()),
                    new HttpLocalModelProvider(ModelConfig.defaults()
                            .withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("anything", AgentContext.empty(), new AtomicBoolean(true));
            assertTrue(a.failed());
            assertTrue(a.failure().contains("cancelled"));
        }
    }

    // ========================================================= grounding & context

    @Test
    @DisplayName("An answer with no supporting records is flagged, not presented as fact")
    void ungroundedAnswerIsFlagged(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = AegisFacades.open(c); // deliberately empty case
            rt.reply("{\"tool\": \"search_items\", \"arguments\": {\"query\": \"zzz\"}}")
              .reply("There are three invoices from Globex.");   // an invented claim

            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()),
                    new HttpLocalModelProvider(ModelConfig.defaults()
                            .withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("what invoices are there?", AgentContext.empty());

            assertFalse(a.isGrounded(), "nothing was retrieved, so nothing is grounded");
            assertTrue(a.finalAnswer().contains("not supported by case data"),
                    "an unsupported answer must carry that caveat");
        }
    }

    @Test
    @DisplayName("Screen context reaches the tools without the operator pasting ids")
    void contextIsUsed(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = seeded(c, tmp);
            CorpusDatabase dao = new CorpusDatabase(c.db());
            String itemId = f.search().search("consulting").results().get(0).id();

            Map<String, AgentTool> tools = new java.util.LinkedHashMap<>();
            for (AgentTool t : new CaseTools(f, dao).all()) {
                tools.put(t.name(), t);
            }
            // no itemId argument: it must come from context
            ToolResult r = tools.get("get_item").execute(new ToolRequest(Map.of(),
                    AgentContext.ofScreen("File Library").withElement(itemId)));
            assertTrue(r.success(), r.error());
            assertTrue(r.text().contains(itemId));

            AgentContext ctx = AgentContext.ofScreen("Sources").withSource(1);
            assertTrue(ctx.toPromptBlock().contains("source id: 1"));
            assertTrue(new AgentOrchestrator(null, List.of()).systemPrompt(ctx)
                    .contains("read-only mode"));
        }
    }

    @Test
    @DisplayName("The system prompt advertises every tool and forbids invention")
    void systemPromptContract(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = AegisFacades.open(c);
            AgentOrchestrator orch = new AgentOrchestrator(null,
                    new CaseTools(f, new CorpusDatabase(c.db())).all());
            String prompt = orch.systemPrompt(AgentContext.ofScreen("Dashboard"));

            for (String tool : orch.toolNames()) {
                assertTrue(prompt.contains(tool), "prompt must advertise " + tool);
            }
            assertTrue(prompt.contains("Never invent"));
            assertTrue(prompt.contains("say so plainly"));
            assertTrue(prompt.contains("\"tool\""), "prompt must show the call format");
        }
    }

    // ================================================================== offline

    @Test
    @DisplayName("Offline: the application and the agent work with no external network")
    void offlineOperation(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = seeded(c, tmp);

            // the whole application path works with only a loopback runtime present
            assertEquals(2, f.search().search("consulting").totalCount());
            assertEquals(2, f.contents().getPaths().totalCount());

            rt.reply("{\"tool\": \"get_statistics\", \"arguments\": {}}")
              .reply("Two items are indexed on this case.");

            ModelConfig cfg = ModelConfig.defaults()
                    .withEndpoint(rt.endpoint()).withChatModel("test-model");
            assertTrue(cfg.isLocalEndpoint(), "the configured endpoint must be loopback");

            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()),
                    new HttpLocalModelProvider(cfg));
            AgentActivity a = svc.ask("how much material is here?", AgentContext.empty());

            assertFalse(a.failed(), a.failure());
            assertTrue(a.isGrounded());
            assertEquals(1, a.steps().size());
        }
    }

    @Test
    @DisplayName("With no runtime installed the application still works and explains why")
    void degradesWithoutRuntime(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            AegisFacades f = seeded(c, tmp);
            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()), null);

            assertFalse(svc.isAvailable());
            assertNotNull(svc.unavailableReason());
            assertTrue(svc.unavailableReason().toLowerCase().contains("runtime")
                            || svc.unavailableReason().toLowerCase().contains("disabled"),
                    "the reason must tell the operator what to do");

            // the rest of the application is unaffected
            assertEquals(2, f.search().search("consulting").totalCount());

            AgentActivity a = svc.ask("anything", AgentContext.empty());
            assertTrue(a.failed(), "the agent should decline cleanly, not throw");
        }
    }

    @Test
    @DisplayName("Model configuration is replaceable without code changes")
    void configurableModel() {
        ModelConfig d = ModelConfig.defaults();
        assertTrue(d.isLocalEndpoint());
        assertTrue(d.enabled());

        ModelConfig swapped = d.withChatModel("some-other-model:14b");
        assertEquals("some-other-model:14b", swapped.chatModel());
        assertEquals(d.endpoint(), swapped.endpoint());

        assertThrows(IllegalArgumentException.class,
                () -> new ModelConfig("", "m", "e", Duration.ofSeconds(1), 4096, 0.1, 256, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelConfig("http://127.0.0.1", "m", "e",
                        Duration.ofSeconds(1), 16, 0.1, 256, true));
    }

    @Test
    @DisplayName("Activity records tool inputs, outcomes, evidence and timing")
    void auditTrail(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp); FakeLocalRuntime rt = new FakeLocalRuntime(port()).start()) {
            AegisFacades f = seeded(c, tmp);
            rt.reply("{\"tool\": \"search_items\", \"arguments\": {\"query\": \"invoice\"}}")
              .reply("One invoice was found.");

            AgentService svc = new AgentService(f, new CorpusDatabase(c.db()),
                    new HttpLocalModelProvider(ModelConfig.defaults()
                            .withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("find invoices", AgentContext.empty());

            AgentActivity.Step s = a.steps().get(0);
            assertEquals(1, s.index());
            assertEquals("search_items", s.tool());
            assertEquals("invoice", s.arguments().get("query"));
            assertTrue(s.success());
            assertTrue(s.evidenceCount() > 0);
            assertTrue(s.millis() >= 0);

            String trace = a.toTrace();
            assertTrue(trace.contains("Request: find invoices"));
            assertTrue(trace.contains("search_items"));
            assertTrue(trace.contains("evidence:"));
            assertEquals(1, svc.history().size(), "runs are retained for the activity panel");
        }
    }

    @Test
    @DisplayName("Suggestions are contextual to the screen")
    void suggestions() {
        assertFalse(AgentService.suggestionsFor(AgentContext.ofScreen("Search")).isEmpty());
        assertNotEquals(AgentService.suggestionsFor(AgentContext.ofScreen("Search")),
                AgentService.suggestionsFor(AgentContext.ofScreen("Sources")));
        assertFalse(AgentService.suggestionsFor(AgentContext.empty()).isEmpty());
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }

    private static void assertNotEquals(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
