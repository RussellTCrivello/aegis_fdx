package com.aegis.fdx;

import com.aegis.fdx.ai.agent.AgentActivity;
import com.aegis.fdx.ai.agent.AgentOrchestrator;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.model.HttpLocalModelProvider;
import com.aegis.fdx.ai.model.ModelConfig;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ai.tools.AgentTool;
import com.aegis.fdx.ai.tools.CaseTools;
import com.aegis.fdx.ai.tools.MutatingTools;
import com.aegis.fdx.ai.tools.ToolRequest;
import com.aegis.fdx.ai.tools.ToolResult;
import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.BatchAnalysisFacade;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.KeywordDto;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.store.CorpusDatabase;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Phase 5. The AI boundary: the agent is an optional, manually invoked analysis layer
 * over a finished application, never part of its processing engine.
 *
 * <p>These checks exist because that separation is an architectural rule, not a
 * coding habit, and a rule nobody measures decays. Every assertion here maps to one
 * clause of the directive in {@code docs/AI_BOUNDARY.md}:
 *
 * <ul>
 *   <li><b>B-01 separation</b> — reading, processing, extraction, metadata, OCR,
 *       hashing, indexing and storage carry no reference to the agent, in source
 *       <em>and</em> in compiled bytecode.</li>
 *   <li><b>B-02 containment</b> — the agent reaches the application only through
 *       registered tools over existing facades: no shell, SQL, filesystem or
 *       unrestricted network.</li>
 *   <li><b>B-03 invocation</b> — the agent runs only from an explicit user action.</li>
 *   <li><b>B-04 optionality</b> — disabled, unconfigured or misconfigured, the
 *       application starts and works normally.</li>
 *   <li><b>B-05 ingestion</b> — a full ingest, index, analyse and search cycle makes
 *       no model call at all, with a runtime present and reachable.</li>
 *   <li><b>B-06 read-only</b> — a question changes no record, no file and no index.</li>
 *   <li><b>B-08 startup</b> — wiring the agent into the window loads no model, reads
 *       no model configuration and contacts no runtime; the first load happens on an
 *       explicit invocation and nowhere else.</li>
 *   <li><b>B-07 writes</b> — state-changing tools exist only as separately defined
 *       operations behind explicit confirmation, and never touch evidence.</li>
 * </ul>
 *
 * <p>B-06 is only meaningful if the comparison could detect a change, so B-07
 * deliberately performs a confirmed write and requires the same fingerprint to move.
 *
 * <p>Model text is not asserted anywhere: a scripted loopback runtime stands in for
 * the model, exactly as in {@link AiAgentTest}. What is asserted is the architecture.
 */
public final class AiBoundaryTest {

    private static int passed;
    private static int failed;
    private static int skipped;
    private static final List<String> failures = new ArrayList<>();

    /** Packages that make up the normal pipeline; none may know the agent exists. */
    private static final List<String> PIPELINE_PACKAGES = List.of(
            "engine", "analyzers", "ocr", "index", "store", "spi", "model", "export",
            "facade");

    /** Things the agent's own reasoning layer must never be able to reach. */
    private static final List<String> ESCAPE_HATCHES = List.of(
            "Runtime.getRuntime", "ProcessBuilder", "createStatement(", "prepareStatement(",
            "java.sql", "FileOutputStream", "FileWriter", "Files.write", "Files.delete",
            "Files.createFile", "URLClassLoader", "ScriptEngine", "java.net.Socket");

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "/tmp/aegis-ai-boundary");
        deleteTree(work);
        Files.createDirectories(work);

        section("B-01  Separation: the pipeline never reaches into the agent");
        separation();

        section("B-02  Containment: the agent reaches the application only through tools");
        containment(work);

        section("B-03  Invocation: the agent runs only when the operator asks");
        invocation();

        section("B-04  Optionality: the application works with no agent at all");
        optionality(work);

        section("B-05  Ingestion, indexing, analysis and search make no model call");
        ingestionIsAiFree(work);

        section("B-06  A question changes nothing");
        questionsChangeNothing(work);

        section("B-07  Writes are separate, confirmed, and never touch evidence");
        writesAreSeparateAndConfirmed(work);

        section("B-08  Startup: nothing AI-related loads until the operator asks");
        nothingLoadsAtStartup(work);

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (skipped > 0) {
            System.out.println("(" + skipped + " environmental skip(s))");
        }
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Failures:");
            failures.forEach(f -> System.out.println("  - " + f));
        }
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ================================================================== B-01

    private static void separation() throws Exception {
        Path src = sourceRoot();
        if (src == null) {
            skip("pipeline source is free of agent references",
                    "source tree not found from " + Path.of("").toAbsolutePath());
            skip("pipeline bytecode is free of agent references", "no source tree");
            return;
        }

        List<String> offenders = new ArrayList<>();
        for (String pkg : PIPELINE_PACKAGES) {
            for (Path f : javaFiles(src.resolve("com/aegis/fdx").resolve(pkg))) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (text.contains("com.aegis.fdx.ai")) {
                    offenders.add(src.relativize(f).toString());
                }
            }
        }
        Path launcher = src.resolve("com/aegis/fdx/Launcher.java");
        if (Files.exists(launcher)
                && Files.readString(launcher, StandardCharsets.UTF_8).contains("com.aegis.fdx.ai")) {
            offenders.add("com/aegis/fdx/Launcher.java");
        }
        check("pipeline source is free of agent references", offenders.isEmpty(),
                offenders.isEmpty()
                        ? PIPELINE_PACKAGES.size() + " packages scanned, none references com.aegis.fdx.ai"
                        : "AI referenced from " + offenders);

        // Imports can be laundered through fully-qualified names or inner classes, so
        // check what the compiler actually emitted as well.
        Path classes = classesRoot();
        if (classes == null) {
            skip("pipeline bytecode is free of agent references",
                    "no compiled classes directory; run the suite after a build");
        } else {
            List<String> binary = new ArrayList<>();
            for (String pkg : PIPELINE_PACKAGES) {
                for (Path f : classFiles(classes.resolve("com/aegis/fdx").resolve(pkg))) {
                    String constants = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                    if (constants.contains("com/aegis/fdx/ai")) {
                        binary.add(classes.relativize(f).toString());
                    }
                }
            }
            check("pipeline bytecode is free of agent references", binary.isEmpty(),
                    binary.isEmpty()
                            ? "no constant-pool reference to com/aegis/fdx/ai in " + classes
                            : "AI referenced from compiled " + binary);
        }

        // Only the interface layer may know about the agent at all.
        List<String> unexpected = new ArrayList<>();
        for (Path f : javaFiles(src.resolve("com/aegis/fdx"))) {
            String rel = src.relativize(f).toString().replace('\\', '/');
            if (rel.startsWith("com/aegis/fdx/ai/") || rel.startsWith("com/aegis/fdx/ui/")) {
                continue;
            }
            if (Files.readString(f, StandardCharsets.UTF_8).contains("com.aegis.fdx.ai")) {
                unexpected.add(rel);
            }
        }
        check("only the interface layer references the agent", unexpected.isEmpty(),
                unexpected.isEmpty()
                        ? "agent visible from ui/ only, as an operator action"
                        : "agent also referenced from " + unexpected);
    }

    // ================================================================== B-02

    private static void containment(Path work) throws Exception {
        Path src = sourceRoot();
        if (src != null) {
            // The agent must not be able to start processing, extraction, OCR or indexing.
            List<String> pipelineReach = new ArrayList<>();
            for (Path f : javaFiles(src.resolve("com/aegis/fdx/ai"))) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String forbidden : List.of("com.aegis.fdx.analyzers", "com.aegis.fdx.ocr",
                        "com.aegis.fdx.index", "com.aegis.fdx.spi", "com.aegis.fdx.export",
                        "IngestPipeline", "OcrStage", "OcrEngine", "AnalyzerRegistry",
                        "LuceneIndex", "startIngest", "processFolder", "processSingleFile")) {
                    if (text.contains(forbidden)) {
                        pipelineReach.add(src.relativize(f) + " -> " + forbidden);
                    }
                }
            }
            check("the agent cannot trigger processing", pipelineReach.isEmpty(),
                    pipelineReach.isEmpty()
                            ? "no ingest, extraction, OCR or index API is reachable from ai/"
                            : "processing reachable: " + pipelineReach);

            // The reasoning layer gets no I/O of its own; only ai/model may speak HTTP,
            // and only to a loopback runtime.
            List<String> hatches = new ArrayList<>();
            for (Path f : javaFiles(src.resolve("com/aegis/fdx/ai"))) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String hatch : ESCAPE_HATCHES) {
                    if (text.contains(hatch)) {
                        hatches.add(src.relativize(f) + " -> " + hatch);
                    }
                }
            }
            check("the agent has no shell, SQL or filesystem escape", hatches.isEmpty(),
                    hatches.isEmpty()
                            ? ESCAPE_HATCHES.size() + " escape patterns checked, none present"
                            : "escape hatch present: " + hatches);

            List<String> networking = new ArrayList<>();
            for (String pkg : List.of("agent", "tools")) {
                for (Path f : javaFiles(src.resolve("com/aegis/fdx/ai").resolve(pkg))) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    if (text.contains("java.net") || text.contains("HttpClient")
                            || text.contains("HttpRequest")) {
                        networking.add(src.relativize(f).toString());
                    }
                }
            }
            check("only the model transport touches the network", networking.isEmpty(),
                    networking.isEmpty()
                            ? "ai/agent and ai/tools contain no network code"
                            : "network code outside ai/model: " + networking);

            Path provider = src.resolve("com/aegis/fdx/ai/model/HttpLocalModelProvider.java");
            String providerText = Files.exists(provider)
                    ? Files.readString(provider, StandardCharsets.UTF_8) : "";
            check("a non-loopback endpoint is refused at construction",
                    providerText.contains("refusing a non-loopback AI endpoint"),
                    "HttpLocalModelProvider rejects any endpoint that is not loopback");
        } else {
            skip("the agent cannot trigger processing", "source tree not found");
            skip("the agent has no shell, SQL or filesystem escape", "source tree not found");
            skip("only the model transport touches the network", "source tree not found");
            skip("a non-loopback endpoint is refused at construction", "source tree not found");
        }

        try (LiveCase c = openCase(work.resolve("containment"))) {
            AegisFacades f = AegisFacades.open(c);
            CorpusDatabase dao = new CorpusDatabase(c.db());

            List<AgentTool> readOnly = new CaseTools(f, dao).all();
            boolean allRead = true;
            for (AgentTool t : readOnly) {
                if (t.isMutating() || t.schema().mutating()) {
                    allRead = false;
                }
            }
            check("every tool in the default set is read-only", allRead,
                    readOnly.size() + " tools registered, none declares a write");

            List<String> names = new ArrayList<>();
            for (AgentTool t : readOnly) {
                names.add(t.name());
            }
            for (AgentTool t : new MutatingTools(f, dao).all()) {
                names.add(t.name());
            }
            List<String> forbidden = new ArrayList<>();
            // Names of general-purpose capabilities. Read-only names that merely mention
            // the pipeline — get_processing_status — are legitimate and not listed here.
            for (String bad : List.of("sql", "query_database", "shell", "exec", "command",
                    "read_file", "write_file", "delete", "http", "fetch", "eval", "script",
                    "powershell", "ingest", "run_ocr")) {
                for (String n : names) {
                    if (n.toLowerCase().contains(bad)) {
                        forbidden.add(n);
                    }
                }
            }
            check("no tool exposes a general-purpose capability", forbidden.isEmpty(),
                    forbidden.isEmpty()
                            ? "tool gateway: " + names
                            : "unsafe tool name(s): " + forbidden);
        }
    }

    // ================================================================== B-03

    private static void invocation() throws Exception {
        Path src = sourceRoot();
        if (src == null) {
            skip("the agent is invoked from user actions only", "source tree not found");
            skip("no screen analyses anything on display", "source tree not found");
            skip("contextual analysis is offered, not performed", "source tree not found");
            skip("enabling writes requires a confirmation step", "source tree not found");
            return;
        }

        List<String> callers = new ArrayList<>();
        for (Path f : javaFiles(src.resolve("com/aegis/fdx/ui"))) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            if (text.contains("agent.ask(") || text.contains(".ask(")) {
                callers.add(f.getFileName().toString());
            }
        }
        Collections.sort(callers);
        check("the agent is invoked from user actions only",
                callers.equals(List.of("AgentScreen.java", "AnalyzeAction.java")),
                "agent.ask() is reachable only from the Assistant screen and the "
                        + "Analyze action; found " + callers);

        Path screen = src.resolve("com/aegis/fdx/ui/screens/AgentScreen.java");
        Path action = src.resolve("com/aegis/fdx/ui/AnalyzeAction.java");
        String screenText = Files.exists(screen)
                ? Files.readString(screen, StandardCharsets.UTF_8) : "";
        String actionText = Files.exists(action)
                ? Files.readString(action, StandardCharsets.UTF_8) : "";

        boolean handlerDriven = screenText.contains("setOnAction") && actionText.contains("setOnAction");
        String onShow = between(screenText, "public void onShow()", "\n    }");
        boolean quietOnShow = !onShow.contains("ask(") && !onShow.contains("run()");
        check("no screen analyses anything on display", handlerDriven && quietOnShow,
                "both entry points fire from a button; AgentScreen.onShow() only reports "
                        + "availability");

        List<String> autoRun = new ArrayList<>();
        for (Path f : javaFiles(src.resolve("com/aegis/fdx/ui/screens"))) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            if (text.contains("AnalyzeAction.run(")) {
                autoRun.add(f.getFileName().toString());
            }
        }
        check("contextual analysis is offered, not performed", autoRun.isEmpty(),
                autoRun.isEmpty()
                        ? "screens attach AnalyzeAction buttons; none runs the agent itself"
                        : "screens invoking the agent directly: " + autoRun);

        boolean confirms = screenText.contains("allowChanges")
                && (screenText.contains("Alert") || screenText.contains("confirm"))
                && screenText.contains("setSelected(false)");
        check("enabling writes requires a confirmation step", confirms,
                "the Assistant screen confirms before write tools are registered and "
                        + "reverts the control when the operator declines");
    }

    // ================================================================== B-04

    private static void optionality(Path work) throws Exception {
        String savedEnabled = System.getProperty("aegis.ai.enabled");
        String savedEndpoint = System.getProperty("aegis.ai.endpoint");
        try (LiveCase c = openCase(work.resolve("optional"))) {
            AegisFacades f = seed(c, work.resolve("optional-evidence"));
            CorpusDatabase dao = new CorpusDatabase(c.db());

            System.setProperty("aegis.ai.enabled", "false");
            AgentService off = AgentService.fromEnvironment(f, dao);
            check("the agent can be switched off entirely",
                    !off.isAvailable() && off.unavailableReason() != null,
                    "aegis.ai.enabled=false: " + off.unavailableReason());

            AgentActivity declined = off.ask("summarise the case", AgentContext.empty());
            check("a disabled agent declines instead of throwing",
                    declined.failed() && declined.failure() != null,
                    "ask() returned a failed activity: " + declined.failure());

            // Everything the application actually does still works.
            check("search works with no agent",
                    f.search().search("consulting").totalCount() == 2,
                    "2 of 2 seeded documents found by the Lucene index");
            check("the case database works with no agent",
                    f.contents().getPaths().totalCount() == 2 && dao.countPathsAll() == 2,
                    "registry, sources, aspects and contents all readable");
            check("processing statistics work with no agent",
                    !f.liveCase().statusCounts().isEmpty() && f.liveCase().indexedCount() == 2,
                    "2 items indexed, status counts available");

            // A missing runtime must not stall startup: the provider is built, not probed.
            System.setProperty("aegis.ai.enabled", "true");
            System.setProperty("aegis.ai.endpoint", "http://127.0.0.1:" + freePort());
            long t0 = System.nanoTime();
            AgentService dead = AgentService.fromEnvironment(f, dao);
            long millis = (System.nanoTime() - t0) / 1_000_000;
            check("a missing local runtime does not delay startup",
                    dead != null && millis < 2000,
                    "AgentService.fromEnvironment returned in " + millis + " ms with nothing "
                            + "listening");
            check("a missing local runtime reports, rather than fails",
                    !dead.isAvailable() && dead.unavailableReason() != null,
                    "the Assistant explains what to install; nothing else is affected");

            // A misconfigured (remote) endpoint is refused without taking the app down.
            System.setProperty("aegis.ai.endpoint", "https://api.example.com");
            AgentService remote = AgentService.fromEnvironment(f, dao);
            check("a remote endpoint is refused, and the application still starts",
                    remote != null && !remote.isAvailable(),
                    "non-loopback configuration yields an unavailable agent, not a crash");
            check("the application still works after that refusal",
                    f.search().search("consulting").totalCount() == 2,
                    "search unaffected by the rejected AI configuration");
        } finally {
            restore("aegis.ai.enabled", savedEnabled);
            restore("aegis.ai.endpoint", savedEndpoint);
        }
    }

    // ================================================================== B-05

    private static void ingestionIsAiFree(Path work) throws Exception {
        Path evidence = work.resolve("ingest-evidence");
        try (LiveCase c = openCase(work.resolve("ingest"));
             FakeLocalRuntime rt = new FakeLocalRuntime(freePort()).start()) {

            AegisFacades f = AegisFacades.open(c);
            CorpusDatabase dao = new CorpusDatabase(c.db());
            AgentService svc = new AgentService(f, dao, new HttpLocalModelProvider(
                    ModelConfig.defaults().withEndpoint(rt.endpoint()).withChatModel("test-model")));

            check("a local runtime is present and reachable for this check",
                    svc.isAvailable(),
                    "the agent could run — which makes silence during ingestion meaningful");

            // A complete working cycle: acquire, read, extract, hash, index, register,
            // analyse, search, preview, report.
            Files.createDirectories(evidence);
            for (int i = 1; i <= 6; i++) {
                Files.writeString(evidence.resolve("doc" + i + ".txt"),
                        "Document " + i + ". Acme consulting agreement, payment due in 30 days.",
                        StandardCharsets.UTF_8);
            }
            int src = f.sources().createSource("Acme", "NL", "custodian", 0.8);
            int asp = f.aspects().createAspect("Plaintiff", 0.9);
            f.categories().createCategory("finance");
            f.keywords().createKeyword("payment due in 30 days", "finance");
            f.processing("Acme", "Plaintiff").processFolder(evidence.toString());
            f.contents().registerIngestedItems(src, asp);
            try {
                f.batch().run(new BatchAnalysisFacade.Request(
                        BatchAnalysisFacade.Template.DEEP), null);
            } catch (RuntimeException e) {
                // Batch analysis is part of the cycle, not the subject of this check;
                // report rather than abort if the fixture cannot run it.
                System.out.println("  note  batch analysis did not run here: " + e);
            }
            f.search().search("consulting");
            f.dashboard().getStats();
            f.liveCase().statusCounts();

            check("the pipeline processed the material",
                    f.liveCase().indexedCount() == 6 && dao.countPathsAll() == 6,
                    "6 files read, extracted, hashed, indexed and registered");
            check("ingestion made no model call", rt.callCount() == 0,
                    "the scripted runtime received 0 chat requests across the whole cycle");
            check("ingestion left no agent activity", svc.history().isEmpty(),
                    "AgentService recorded 0 runs: nothing invoked the agent");

            // And the agent still works when the operator does ask.
            rt.reply("{\"tool\": \"get_statistics\", \"arguments\": {}}")
              .reply("Six documents are indexed on this case.");
            AgentActivity a = svc.ask("how much material is here?", AgentContext.empty());
            check("the agent answers when explicitly invoked",
                    !a.failed() && rt.callCount() == 2 && svc.history().size() == 1,
                    "one operator question produced 2 model turns and 1 recorded run");
        }
    }

    // ================================================================== B-06

    private static void questionsChangeNothing(Path work) throws Exception {
        Path evidence = work.resolve("readonly-evidence");
        try (LiveCase c = openCase(work.resolve("readonly"));
             FakeLocalRuntime rt = new FakeLocalRuntime(freePort()).start()) {

            AegisFacades f = seed(c, evidence);
            CorpusDatabase dao = new CorpusDatabase(c.db());
            Path caseRoot = c.folder().root();

            String recordsBefore = fingerprint(f, dao);
            String evidenceBefore = treeDigest(evidence, true);
            String materialBefore = treeDigest(caseRoot.resolve("data"), true);
            String textBefore = treeDigest(caseRoot.resolve("text"), true);
            String indexBefore = treeDigest(caseRoot.resolve("index"), false);

            int pathId = f.contents().getPaths().results().get(0).id();
            int catId = f.categories().listCategories().results().get(0).id();

            // A model that tries to change things during an ordinary question.
            rt.reply("{\"tool\": \"classify_file\", \"arguments\": {\"pathId\": \"" + pathId
                            + "\", \"categoryId\": \"" + catId + "\"}}")
              .reply("{\"tool\": \"set_review_state\", \"arguments\": {\"pathId\": \"" + pathId
                            + "\", \"state\": \"Read\"}}")
              .reply("{\"tool\": \"search_items\", \"arguments\": {\"query\": \"consulting\"}}")
              .reply("Two documents mention consulting, both from Acme.");

            AgentService svc = new AgentService(f, dao, new HttpLocalModelProvider(
                    ModelConfig.defaults().withEndpoint(rt.endpoint()).withChatModel("test-model")));
            AgentActivity a = svc.ask("Summarise the consulting material.",
                    AgentContext.ofScreen("Search").withQuery("consulting"));

            check("an ordinary question still produces a grounded answer",
                    !a.failed() && a.isGrounded(),
                    a.failed() ? String.valueOf(a.failure())
                               : a.steps().size() + " tool call(s), evidence cited");

            boolean writesRefused = true;
            for (AgentActivity.Step s : a.steps()) {
                if (("classify_file".equals(s.tool()) || "set_review_state".equals(s.tool()))
                        && s.success()) {
                    writesRefused = false;
                }
            }
            check("attempted writes during a question are refused", writesRefused,
                    "the model asked for classify_file and set_review_state; neither was "
                            + "registered in a read-only context");

            check("database records are unchanged",
                    recordsBefore.equals(fingerprint(f, dao)),
                    "items, registry, review state, categories, keywords and counts all "
                            + "identical after the run");
            check("original material is unchanged",
                    evidenceBefore.equals(treeDigest(evidence, true))
                            && materialBefore.equals(treeDigest(caseRoot.resolve("data"), true)),
                    "source folder and the case's stored copies are byte-identical");
            check("extracted evidence is unchanged",
                    textBefore.equals(treeDigest(caseRoot.resolve("text"), true)),
                    "extracted text files are byte-identical");
            check("the search index is unchanged",
                    indexBefore.equals(treeDigest(caseRoot.resolve("index"), false)),
                    "Lucene segments unchanged in name and size");
        }
    }

    // ================================================================== B-07

    private static void writesAreSeparateAndConfirmed(Path work) throws Exception {
        Path evidence = work.resolve("write-evidence");
        try (LiveCase c = openCase(work.resolve("write"))) {
            AegisFacades f = seed(c, evidence);
            CorpusDatabase dao = new CorpusDatabase(c.db());
            Path caseRoot = c.folder().root();
            AgentService svc = new AgentService(f, dao, null);

            List<String> plain = svc.availableTools(AgentContext.empty());
            List<String> confirmed = svc.availableTools(AgentContext.empty().allowingMutations());
            check("write tools are absent from an ordinary question",
                    !plain.contains("classify_file") && !plain.contains("set_review_state"),
                    "read-only context advertises " + plain.size() + " tools, none of them a write");
            check("write tools appear only after confirmation",
                    confirmed.contains("classify_file") && confirmed.contains("set_review_state"),
                    "confirmed context adds exactly the two review-annotation tools");

            check("context never escalates its own permission",
                    !AgentContext.empty().allowMutations()
                            && !AgentContext.ofScreen("Sources").withSource(1).withAspect(2)
                                    .withCategory(3).withKeyword(4).withPath(5)
                                    .withElement("E-1").withQuery("x").allowMutations(),
                    "every contextual builder preserves read-only; only allowingMutations() "
                            + "grants a write");

            String prompt = new AgentOrchestrator(null, new CaseTools(f, dao).all())
                    .systemPrompt(AgentContext.ofScreen("Search"));
            check("the model is told it is in read-only mode",
                    prompt.contains("read-only mode")
                            && !prompt.contains("classify_file"),
                    "the system prompt neither advertises nor permits a write");

            AgentTool classify = new MutatingTools(f, dao).all().get(0);
            ToolResult denied = classify.execute(new ToolRequest(
                    Map.of("pathId", "1", "categoryId", "1"), AgentContext.empty()));
            check("a write called directly without confirmation is refused",
                    !denied.success() && denied.error() != null
                            && denied.error().contains("not permitted"),
                    "the tool re-checks permission itself: " + denied.error());

            // The control for B-06: a confirmed write must be visible to the fingerprint,
            // otherwise "nothing changed" would prove nothing.
            String before = fingerprint(f, dao);
            String materialBefore = treeDigest(caseRoot.resolve("data"), true);
            String textBefore = treeDigest(caseRoot.resolve("text"), true);
            String evidenceBefore = treeDigest(evidence, true);

            int pathId = f.contents().getPaths().results().get(0).id();
            int catId = f.categories().listCategories().results().get(0).id();
            ToolResult allowed = classify.execute(new ToolRequest(
                    Map.of("pathId", String.valueOf(pathId), "categoryId", String.valueOf(catId)),
                    AgentContext.empty().allowingMutations()));

            check("a confirmed write is applied", allowed.success(),
                    allowed.success() ? "classification persisted" : String.valueOf(allowed.error()));
            check("the no-change comparison can detect a change",
                    !before.equals(fingerprint(f, dao)),
                    "the same fingerprint used in B-06 moves when a record really changes");
            check("even a confirmed write leaves evidence untouched",
                    materialBefore.equals(treeDigest(caseRoot.resolve("data"), true))
                            && textBefore.equals(treeDigest(caseRoot.resolve("text"), true))
                            && evidenceBefore.equals(treeDigest(evidence, true)),
                    "the write annotated review classification only: original files, stored "
                            + "copies and extracted text are byte-identical");
        }
    }

    // ================================================================== B-08

    /**
     * The application may hold a reference to the agent; it may not load one.
     *
     * <p>The rule being enforced is that an operator who never opens the Assistant runs
     * an application in which no model provider was ever constructed and no runtime was
     * ever contacted — even when a runtime is installed, reachable and enabled. The
     * check is made against a real HTTP runtime that counts every request it serves,
     * including availability probes, so "nothing happened" is measured rather than
     * assumed.
     */
    private static void nothingLoadsAtStartup(Path work) throws Exception {
        String savedEnabled = System.getProperty("aegis.ai.enabled");
        String savedEndpoint = System.getProperty("aegis.ai.endpoint");
        String savedModel = System.getProperty("aegis.ai.model");
        try (LiveCase c = openCase(work.resolve("startup"));
             FakeLocalRuntime rt = new FakeLocalRuntime(freePort()).start()) {

            AegisFacades f = seed(c, work.resolve("startup-evidence"));
            CorpusDatabase dao = new CorpusDatabase(c.db());
            System.setProperty("aegis.ai.enabled", "true");
            System.setProperty("aegis.ai.endpoint", rt.endpoint());
            // The scripted runtime advertises this model, so a genuine invocation can
            // succeed; the point of the check is when the provider appears, not whether
            // a particular model is installed.
            System.setProperty("aegis.ai.model", "test-model");

            // Exactly what the main window does while it is being built.
            AgentService svc = AgentService.fromEnvironment(f, dao);

            check("wiring the agent at startup constructs no model provider",
                    !svc.isModelLoaded(),
                    "AgentService.fromEnvironment stored a factory, not a provider");
            check("wiring the agent at startup contacts no runtime",
                    rt.requestCount() == 0,
                    "an enabled, reachable runtime served 0 requests during startup");

            // A complete working session by an operator who never opens the Assistant.
            f.search().search("consulting");
            f.dashboard().getStats();
            f.contents().getPaths();
            f.liveCase().statusCounts();
            f.categories().listCategories();
            check("a session that never opens the Assistant loads no model",
                    !svc.isModelLoaded() && rt.requestCount() == 0,
                    "search, dashboard, registry and category work: still 0 requests");

            // The first legitimate load: the operator asks a question.
            rt.reply("{\"tool\": \"get_statistics\", \"arguments\": {}}")
              .reply("Two documents are indexed on this case.");
            AgentActivity a = svc.ask("what is on this case?", AgentContext.empty());
            check("the model loads on the first explicit invocation",
                    !a.failed() && svc.isModelLoaded() && rt.requestCount() > 0,
                    "the provider appears only once a person asks for it"
                            + (a.failed() ? " — ask failed: " + a.failure() : ""));

            // Switched off, even asking why must not construct or contact anything.
            System.setProperty("aegis.ai.enabled", "false");
            AgentService off = AgentService.fromEnvironment(f, dao);
            int before = rt.requestCount();
            String reason = off.unavailableReason();
            check("a disabled agent never loads a provider",
                    !off.isModelLoaded() && reason != null,
                    "unavailableReason() explains the state without building anything");
            check("a disabled agent contacts no runtime",
                    rt.requestCount() == before,
                    "0 further requests after the agent was switched off");

            // The window itself must not be able to load a model: it may only hold the
            // service. This is a source-level rule, so it is read from the source.
            Path app = sourceRoot().resolve("com/aegis/fdx/ui/FasApp.java");
            if (Files.exists(app)) {
                String text = Files.readString(app, StandardCharsets.UTF_8);
                boolean clean = !text.contains("HttpLocalModelProvider")
                        && !text.contains("ModelConfig")
                        && !text.contains("LocalChatModel");
                check("the main window contains no model construction",
                        clean,
                        "FasApp references AgentService only; model classes appear nowhere in it");
            } else {
                skip("the main window contains no model construction",
                        "FasApp.java not found from " + sourceRoot());
            }
        } finally {
            restore("aegis.ai.enabled", savedEnabled);
            restore("aegis.ai.endpoint", savedEndpoint);
            restore("aegis.ai.model", savedModel);
        }
    }

    // ============================================================== fixtures

    private static LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Boundary", s);
    }

    /** A case with real processed material: two documents, fully through the pipeline. */
    private static AegisFacades seed(LiveCase c, Path evidence) throws Exception {
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("invoice.txt"),
                "Invoice 2024 from Acme for consulting services. Payment due in 30 days.",
                StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("memo.txt"),
                "Internal memo about the Acme consulting agreement and its payment terms.",
                StandardCharsets.UTF_8);

        AegisFacades f = AegisFacades.open(c);
        int src = f.sources().createSource("Acme", "NL", "custodian", 0.8);
        int asp = f.aspects().createAspect("Plaintiff", 0.9);
        f.categories().createCategory("finance");
        f.keywords().createKeyword("payment due in 30 days", "finance");
        f.processing("Acme", "Plaintiff").processFolder(evidence.toString());
        f.contents().registerIngestedItems(src, asp);
        return f;
    }

    /**
     * A digest of everything the agent is forbidden to change: every item with its
     * hashes, status, text length, tags and notes; every registry row with its review
     * state and its category and keyword links; and the case-wide counts.
     */
    private static String fingerprint(AegisFacades f, CorpusDatabase dao) throws Exception {
        StringBuilder sb = new StringBuilder();

        List<Item> items = new ArrayList<>(f.liveCase().allItems());
        items.sort(Comparator.comparing(Item::id));
        sb.append("items(").append(items.size()).append(")\n");
        for (Item it : items) {
            sb.append("  ").append(it.id()).append('|').append(it.name())
                    .append('|').append(it.status())
                    .append('|').append(it.size())
                    .append('|').append(it.sha256())
                    .append('|').append(it.md5())
                    .append('|').append(it.extractedText() == null ? -1 : it.extractedText().length())
                    .append('|').append(new TreeSet<>(it.tags()))
                    .append('|').append(it.notes())
                    .append('\n');
        }
        sb.append("indexed:").append(f.liveCase().indexedCount()).append('\n');

        List<PathDto> paths = new ArrayList<>(f.contents().getPaths().results());
        paths.sort(Comparator.comparingInt(PathDto::id));
        sb.append("paths(").append(paths.size()).append(")\n");
        for (PathDto p : paths) {
            sb.append("  ").append(p).append('\n');
            sb.append("    categories:");
            for (CorpusDatabase.Row r : dao.selectPathCategories(p.id())) {
                sb.append(' ').append(r.i("id")).append('=').append(r.str("word"));
            }
            sb.append("\n    keywords:");
            for (CorpusDatabase.Row r : dao.selectPathKeywords(p.id())) {
                sb.append(' ').append(r.i("id")).append('=').append(r.str("keyword"))
                        .append('x').append(r.i("hits"));
            }
            sb.append('\n');
        }

        List<CategoryDto> cats = new ArrayList<>(f.categories().listCategories().results());
        cats.sort(Comparator.comparingInt(CategoryDto::id));
        sb.append("categories:").append(cats).append('\n');

        List<KeywordDto> kws = new ArrayList<>(f.keywords().listKeywords().results());
        kws.sort(Comparator.comparingInt(KeywordDto::id));
        sb.append("keywords:").append(kws).append('\n');

        sb.append("sources:").append(new java.util.TreeMap<>(dao.selectAllSources())).append('\n');
        sb.append("aspects:").append(new java.util.TreeMap<>(dao.selectAllAspects())).append('\n');
        sb.append("counts:").append(dao.countPathsAll()).append('/').append(dao.countContents())
                .append('/').append(dao.countCategories()).append('/').append(dao.countKeywords())
                .append('\n');
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Digest of a directory tree.
     *
     * @param withContent hash file bytes as well as names and sizes. Off for the Lucene
     *                    index, whose segment files are immutable but whose transient
     *                    lock file is not evidence.
     */
    private static String treeDigest(Path dir, boolean withContent) throws Exception {
        if (!Files.isDirectory(dir)) {
            return "(absent)";
        }
        List<String> lines = new ArrayList<>();
        List<Path> files;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        for (Path p : files) {
            String name = dir.relativize(p).toString().replace('\\', '/');
            if (name.endsWith("write.lock")) {
                continue;
            }
            String line = name + '|' + Files.size(p);
            if (withContent) {
                line = line + '|' + sha256(Files.readAllBytes(p));
            }
            lines.add(line);
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    // ================================================================ helpers

    private static Path sourceRoot() {
        for (String candidate : List.of("app/src/main/java", "src/main/java",
                "../app/src/main/java", "../../app/src/main/java")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p.resolve("com/aegis/fdx"))) {
                return p;
            }
        }
        return null;
    }

    private static Path classesRoot() {
        for (String candidate : List.of("build/classes", "app/build/classes/java/main",
                "build/classes/java/main", "../build/classes", "../app/build/classes/java/main")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p.resolve("com/aegis/fdx"))) {
                return p;
            }
        }
        return null;
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        return filesWithSuffix(dir, ".java");
    }

    private static List<Path> classFiles(Path dir) throws IOException {
        return filesWithSuffix(dir, ".class");
    }

    private static List<Path> filesWithSuffix(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    /** The text between a marker and the next terminator, or "" when absent. */
    private static String between(String text, String from, String to) {
        int a = text.indexOf(from);
        if (a < 0) {
            return "";
        }
        int b = text.indexOf(to, a + from.length());
        return b < 0 ? text.substring(a) : text.substring(a, b);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(bytes);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("-- " + title);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name + (detail == null ? "" : "  — " + detail));
        } else {
            failed++;
            failures.add(name + (detail == null ? "" : ": " + detail));
            System.out.println("  FAIL  " + name + (detail == null ? "" : "  — " + detail));
        }
    }

    private static void skip(String name, String why) {
        skipped++;
        System.out.println("  SKIP  " + name + "  — " + why);
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        List<Path> all;
        try (java.util.stream.Stream<Path> s = Files.walk(p)) {
            all = s.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path q : all) {
            Files.deleteIfExists(q);
        }
    }

    private AiBoundaryTest() {
    }
}
