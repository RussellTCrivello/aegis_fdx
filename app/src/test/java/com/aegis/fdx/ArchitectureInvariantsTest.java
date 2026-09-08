package com.aegis.fdx;

import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.store.CorpusDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules the architecture is not allowed to break.
 *
 * <p>The other suites check that features behave. This one checks that the shape of the
 * system stays the shape that was agreed, because those are the failures that no feature
 * test notices: a second datastore quietly appearing beside the case database, a screen
 * that stops calling the backend, a model provider creeping into application startup, a
 * capability with no way to reach it. Each check is written so that it fails when the
 * architecture drifts, not when a feature changes.
 *
 * <p>Some checks read the source and the compiled classes rather than calling methods.
 * That is deliberate: "no class in the processing core references the agent package" is a
 * statement about the build output, and the only honest way to assert it is to look at
 * the build output.
 *
 * <p>It runs both under JUnit and from {@code main}, so the offline battery can enforce
 * the same rules on a machine with no build tool.
 */
public final class ArchitectureInvariantsTest {

    /** Everything a case folder is allowed to contain. Anything else is a new datastore. */
    private static final Set<String> CASE_LAYOUT = Set.of(
            "data", "index", "text", "db", "logs", "exports", "case.json",
            CaseSettings.FILE_NAME);

    /** Packages that make up the processing core: ingest, storage, search, evidence. */
    private static final List<String> CORE_PACKAGES = List.of(
            "engine", "store", "index", "ocr", "analyzers", "export", "facade", "model", "spi");

    private static Path work;

    // ==================================================== storage authority

    @Test
    @DisplayName("The case database is the only datastore")
    void caseDatabaseIsTheOnlyDatastore() throws Exception {
        Path root = caseRoot("authority");
        try (LiveCase c = open(root)) {
            seed(c, root.getParent().resolve("evidence-authority"));

            List<String> databases = new ArrayList<>();
            for (Path p : filesUnder(root)) {
                String name = p.getFileName().toString().toLowerCase();
                if (name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3")
                        || name.endsWith(".mv.db") || name.endsWith(".h2.db")
                        || name.endsWith(".mdb") || name.endsWith(".accdb")
                        || name.endsWith(".realm") || name.endsWith(".mdf")) {
                    databases.add(root.relativize(p).toString().replace('\\', '/'));
                }
            }
            assertEquals(List.of("db/case.db"), databases,
                    "exactly one database file may exist in a case: " + databases);

            Set<String> top = new TreeSet<>();
            try (Stream<Path> s = Files.list(root)) {
                s.forEach(p -> top.add(p.getFileName().toString()));
            }
            for (String entry : top) {
                assertTrue(CASE_LAYOUT.contains(entry),
                        "unexpected entry in the case folder — a new store? " + entry);
            }
        }
    }

    @Test
    @DisplayName("Exactly one place in the application opens a database connection")
    void onlyCaseDatabaseOpensAConnection() throws Exception {
        // A second connection to case.db is not a hypothetical: one was found in
        // AegisApp, opened for a single error-report query. It bypassed every tuned
        // PRAGMA (WAL, busy timeout, cache, foreign keys), took its own lock, and was
        // never closed. The fix was to use the case's own connection; this test is what
        // stops the next one being written, because a grep does not survive a merge.
        Path main = Path.of("app/src/main/java");
        if (!Files.exists(main)) {
            return; // not runnable from this working directory
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(main)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                boolean opens = src.contains("DriverManager.getConnection")
                        || src.contains("new SQLiteDataSource")
                        || src.contains("SQLiteConfig().createConnection");
                if (opens && !p.endsWith(Path.of("store", "CaseDatabase.java"))) {
                    offenders.add(main.relativize(p).toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(List.of(), offenders,
                "these classes open their own database connection instead of using "
                        + "CaseDatabase.connection(), so they bypass the configured "
                        + "PRAGMAs and take an independent lock: " + offenders);
    }

    @Test
    @DisplayName("The database is the record of authority; the index is derived from it")
    void theDatabaseOutlivesTheIndex() throws Exception {
        Path root = caseRoot("derived");
        int items;
        int paths;
        try (LiveCase c = open(root)) {
            AegisFacades f = seed(c, root.getParent().resolve("evidence-derived"));
            items = c.db().count();
            paths = f.contents().getPaths().results().size();
            assertTrue(items > 0, "the fixture must actually process something");
            assertEquals(items, c.indexedCount(), "everything stored is also indexed");
        }

        // Lose the entire search index, as a disk failure or a botched copy would.
        deleteTree(root.resolve("index"));

        try (LiveCase c = open(root)) {
            AegisFacades f = AegisFacades.open(c);
            assertEquals(items, c.db().count(),
                    "the case database still holds every element after the index is destroyed");
            assertEquals(paths, f.contents().getPaths().results().size(),
                    "the registry is read from the database, not the index");
            assertEquals(0, c.indexedCount(),
                    "the index is genuinely gone — this check would be meaningless otherwise");
        }
    }

    // ============================================== no second implementation

    @Test
    @DisplayName("Nothing depends on a Python runtime or a web API")
    void noWebApiOrPythonRuntimeDependency() throws Exception {
        List<String> offences = new ArrayList<>();
        for (Path p : mainSources()) {
            String rel = relativeSource(p);
            String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
            for (String forbidden : List.of("python", ".py\"", "flask", "django", "uvicorn",
                    "gunicorn", "jinja", "werkzeug", "sqlalchemy", "127.0.0.1:5000",
                    "localhost:5000", "/api/v1/", "requests.get(", "os.system")) {
                if (code.toLowerCase().contains(forbidden)) {
                    offences.add(rel + " → " + forbidden);
                }
            }
            // Only the optional local model may speak HTTP, and only to a loopback runtime.
            boolean model = rel.startsWith("com/aegis/fdx/ai/model/");
            for (String network : List.of("java.net.http", "HttpURLConnection", "new Socket(",
                    "openConnection(")) {
                if (!model && code.contains(network)) {
                    offences.add(rel + " → " + network);
                }
            }
        }
        assertEquals(List.of(), offences,
                "the Java application must not depend on the reference implementation's "
                        + "runtime, and only the AI model client may use the network");
    }

    @Test
    @DisplayName("The only external process is optional OCR")
    void theOnlyExternalProcessIsOptionalOcr() throws Exception {
        List<String> launchers = new ArrayList<>();
        for (Path p : mainSources()) {
            String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
            if (code.contains("new ProcessBuilder") || code.contains("getRuntime().exec")) {
                launchers.add(relativeSource(p));
            }
        }
        assertEquals(List.of("com/aegis/fdx/ocr/OcrEngine.java"), launchers,
                "nothing but the optional OCR engine may start an external program: " + launchers);

        String ocr = Files.readString(sourceRoot().resolve("com/aegis/fdx/ocr/OcrEngine.java"),
                StandardCharsets.UTF_8);
        assertTrue(ocr.contains("catch (IOException") || ocr.contains("catch (Exception"),
                "a missing OCR binary must be handled, not thrown at the operator");
    }

    // ======================================================== the AI boundary

    @Test
    @DisplayName("No class in the processing core references the agent")
    void theProcessingCoreDoesNotReferenceTheAgent() throws Exception {
        Path classes = classesRoot();
        List<String> offences = new ArrayList<>();
        if (classes != null) {
            byte[] needle = "com/aegis/fdx/ai/".getBytes(StandardCharsets.US_ASCII);
            for (String pkg : CORE_PACKAGES) {
                Path dir = classes.resolve("com/aegis/fdx").resolve(pkg);
                for (Path p : filesUnder(dir)) {
                    if (!p.getFileName().toString().endsWith(".class")) {
                        continue;
                    }
                    if (indexOf(Files.readAllBytes(p), needle) >= 0) {
                        offences.add(classes.relativize(p).toString().replace('\\', '/'));
                    }
                }
            }
            assertTrue(offences.isEmpty(),
                    "compiled processing classes must carry no reference to the AI package: "
                            + offences);
        }

        // Source-level statement of the same rule, so the check still means something
        // when the compiled tree is not on hand.
        for (String pkg : CORE_PACKAGES) {
            for (Path p : filesUnder(sourceRoot().resolve("com/aegis/fdx").resolve(pkg))) {
                if (!p.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                assertFalse(code.contains("com.aegis.fdx.ai"),
                        relativeSource(p) + " imports the AI package");
            }
        }
    }

    @Test
    @DisplayName("Application startup constructs nothing AI-related")
    void nothingAiLoadsWhileTheApplicationStarts() throws Exception {
        for (String window : List.of("com/aegis/fdx/ui/FasApp.java", "com/aegis/fdx/ui/AegisApp.java")) {
            Path p = sourceRoot().resolve(window);
            if (!Files.exists(p)) {
                continue;
            }
            String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
            for (String construction : List.of("new HttpLocalModelProvider", "new AgentOrchestrator",
                    "LocalChatModel", "ModelConfig.")) {
                assertFalse(code.contains(construction),
                        window + " builds " + construction + " while the window is opening");
            }
        }

        // And the behaviour behind the rule: wiring the service is free, even when the
        // agent is switched on and a runtime would answer.
        String savedEnabled = System.getProperty("aegis.ai.enabled");
        String savedEndpoint = System.getProperty("aegis.ai.endpoint");
        Path root = caseRoot("startup");
        try (LiveCase c = open(root)) {
            System.setProperty("aegis.ai.enabled", "true");
            System.setProperty("aegis.ai.endpoint", "http://127.0.0.1:" + freePort());
            AegisFacades f = AegisFacades.open(c);
            long t0 = System.nanoTime();
            AgentService agent = AgentService.fromEnvironment(f, new CorpusDatabase(c.db()));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertNotNull(agent, "the service is wired at startup");
            assertFalse(agent.isModelLoaded(), "no provider may exist before anybody asks");
            assertTrue(ms < 1_000, "startup must not wait on a model runtime (took " + ms + " ms)");
        } finally {
            restore("aegis.ai.enabled", savedEnabled);
            restore("aegis.ai.endpoint", savedEndpoint);
        }
    }

    @Test
    @DisplayName("A full processing run never reaches the agent")
    void aFullProcessingRunNeverReachesTheAgent() throws Exception {
        String savedEnabled = System.getProperty("aegis.ai.enabled");
        String savedEndpoint = System.getProperty("aegis.ai.endpoint");
        Path root = caseRoot("pipeline-vs-agent");
        try (LiveCase c = open(root)) {
            // The agent is switched on and configured — and nothing is listening. If any
            // part of ingest, extraction, indexing or registration needed it, the run
            // would fail or stall here.
            System.setProperty("aegis.ai.enabled", "true");
            System.setProperty("aegis.ai.endpoint", "http://127.0.0.1:" + freePort());

            AegisFacades f = AegisFacades.open(c);
            AgentService agent = AgentService.fromEnvironment(f, new CorpusDatabase(c.db()));

            seedInto(f, root.getParent().resolve("evidence-pipeline"));

            assertTrue(c.db().count() > 0, "the pipeline completed with the agent unreachable");
            assertEquals(c.db().count(), c.indexedCount(), "and indexed everything it stored");
            assertTrue(agent.history().isEmpty(),
                    "processing recorded no agent run: " + agent.history().size());
            assertFalse(agent.isModelLoaded(),
                    "processing never even caused a model provider to be built");
        } finally {
            restore("aegis.ai.enabled", savedEnabled);
            restore("aegis.ai.endpoint", savedEndpoint);
        }
    }

    // ============================================================== settings

    @Test
    @DisplayName("A choice made in the interface survives a restart of the case")
    void settingsChosenInTheInterfaceSurviveARestart() throws Exception {
        Path root = caseRoot("settings");
        try (LiveCase c = open(root)) {
            c.settings().workers(2);
            c.settings().maxArchiveDepth(9);
            c.settings().saveTo(c.folder().root().resolve(CaseSettings.FILE_NAME));
        }

        CaseSettings reopened = new CaseSettings();
        try (LiveCase c = new LiveCase(root, "Invariants", reopened)) {
            assertTrue(reopened.loadFrom(c.folder().root().resolve(CaseSettings.FILE_NAME)),
                    "the settings file is inside the case folder");
            assertEquals(2, reopened.workers());
            assertEquals(9, reopened.maxArchiveDepth());
        }

        // The application must perform that same round trip: load on open, save on change.
        String app = Files.readString(sourceRoot().resolve("com/aegis/fdx/ui/FasApp.java"),
                StandardCharsets.UTF_8);
        assertTrue(app.contains("settings.loadFrom("),
                "the main window must reapply the stored settings when the case opens");
        String screen = Files.readString(
                sourceRoot().resolve("com/aegis/fdx/ui/screens/SettingsScreen.java"),
                StandardCharsets.UTF_8);
        assertTrue(screen.contains(".saveTo("),
                "the Settings destination must write the operator's choice to the case");
    }

    // ==================================================== interface ↔ backend

    @Test
    @DisplayName("Every control ends in an operation")
    void everyControlEndsInAnOperation() throws Exception {
        List<String> offences = new ArrayList<>();
        for (Path p : uiSources()) {
            String rel = relativeSource(p);
            String text = Files.readString(p, StandardCharsets.UTF_8);
            String code = stripComments(text);

            // Word-boundary matching, so that JavaFX's own vocabulary — a TableView's
            // setPlaceholder, which is how an empty state is declared — is not mistaken
            // for an unfinished screen.
            for (String marker : List.of("TODO", "FIXME", "XXX", "not implemented",
                    "unimplemented", "coming soon", "dummy data", "sample data", "fake data",
                    "for demo", "demo only", "stub out", "hard-coded value")) {
                if (java.util.regex.Pattern
                        .compile("\\b" + java.util.regex.Pattern.quote(marker) + "\\b",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(code).find()) {
                    offences.add(rel + " → unfinished marker: " + marker);
                }
            }

            int at = 0;
            while ((at = code.indexOf("setOnAction(", at)) >= 0) {
                String handler = balanced(code, at + "setOnAction".length());
                at += "setOnAction(".length();
                String body = handler.replaceAll("\\s+", " ").trim();
                String inner = body;
                int arrow = inner.indexOf("->");
                if (arrow >= 0) {
                    inner = inner.substring(arrow + 2).trim();
                }
                inner = inner.replaceAll("^\\{|\\}$", "").trim();
                if (inner.isEmpty()) {
                    offences.add(rel + " → a control with an empty handler");
                } else if (!inner.contains("(")) {
                    offences.add(rel + " → a control that calls nothing: " + body);
                }
            }
        }
        assertEquals(List.of(), offences, "controls must do real work: " + offences);
    }

    @Test
    @DisplayName("Every screen is reachable from the navigation")
    void everyScreenIsReachableFromTheNavigation() throws Exception {
        String app = Files.readString(sourceRoot().resolve("com/aegis/fdx/ui/FasApp.java"),
                StandardCharsets.UTF_8);
        List<String> unreachable = new ArrayList<>();
        for (Path p : filesUnder(sourceRoot().resolve("com/aegis/fdx/ui/screens"))) {
            String name = p.getFileName().toString();
            if (!name.endsWith(".java")) {
                continue;
            }
            String code = Files.readString(p, StandardCharsets.UTF_8);
            boolean destination = code.contains("implements Screen") || code.contains("implements Detail");
            if (!destination) {
                continue;
            }
            // An import is not a route: the destination has to be built and registered.
            String simple = name.substring(0, name.length() - ".java".length());
            if (!app.contains("new " + simple + "(")) {
                unreachable.add(simple);
            }
        }
        assertEquals(List.of(), unreachable,
                "a destination nobody can navigate to is not a feature: " + unreachable);
    }

    @Test
    @DisplayName("Every backend capability has a way in")
    void everyBackendCapabilityHasAWayIn() throws Exception {
        String facadesSource = Files.readString(
                sourceRoot().resolve("com/aegis/fdx/facade/AegisFacades.java"), StandardCharsets.UTF_8);
        StringBuilder ui = new StringBuilder();
        for (Path p : uiSources()) {
            ui.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
        }

        Set<String> unreachable = new LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("public\\s+(\\w+)\\s+(\\w+)\\(")
                .matcher(facadesSource);
        while (m.find()) {
            String type = m.group(1);
            String accessor = m.group(2);
            if (!type.endsWith("Facade")) {
                continue;
            }
            // Deprecated aliases exist for source compatibility and are covered by the
            // model suite; they are not separate capabilities.
            Path facadeFile = sourceRoot().resolve("com/aegis/fdx/facade/" + type + ".java");
            if (Files.exists(facadeFile)
                    && Files.readString(facadeFile, StandardCharsets.UTF_8).contains("@Deprecated(")) {
                continue;
            }
            if (!ui.toString().contains("." + accessor + "(")) {
                unreachable.add(type + " (facades." + accessor + "())");
            }
        }
        assertEquals(Set.of(), unreachable,
                "backend capability with no route through the interface: " + unreachable);
    }

    // ==================================================== lifecycle & search

    @Test
    @DisplayName("A closed case releases its workers and its files")
    void aClosedCaseReleasesItsWorkersAndFiles() throws Exception {
        Path root = caseRoot("lifecycle");
        int stored;
        LiveCase c = open(root);
        seed(c, root.getParent().resolve("evidence-lifecycle"));
        stored = c.db().count();
        c.close();
        c.close(); // closing twice is the normal consequence of a shutdown hook plus a menu

        long deadline = System.currentTimeMillis() + 3_000;
        List<String> alive = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            alive.clear();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && t.getName().startsWith("aegis-")) {
                    alive.add(t.getName());
                }
            }
            if (alive.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(List.of(), alive, "worker threads outlived the case: " + alive);

        // Nothing is left locked: the same folder opens again and reads the same case.
        try (LiveCase again = open(root)) {
            assertEquals(stored, again.db().count(), "the case reopens intact after a clean close");
            assertEquals(stored, again.indexedCount(), "and its index reopens with it");
        }
    }

    @Test
    @DisplayName("Search reads the authoritative index, not a copy of it")
    void searchReadsTheAuthoritativeIndex() throws Exception {
        Path root = caseRoot("search");
        try (LiveCase c = open(root)) {
            AegisFacades f = seed(c, root.getParent().resolve("evidence-search"));

            assertEquals(c.db().count(), c.indexedCount(),
                    "stored and indexed counts agree");

            List<SearchResultDto> hits = f.search().search("acme").results();
            assertFalse(hits.isEmpty(), "a term from the processed material is findable");
            for (SearchResultDto hit : hits) {
                assertNotNull(c.byId(hit.id()),
                        "every result names an element that exists in the case: " + hit.id());
            }

            // A change made through the application is visible to the next search:
            // the index is the live one, not a snapshot taken at startup.
            var items = c.allItems();
            items.sort(Comparator.comparing(com.aegis.fdx.model.Item::id));
            c.applyTag(List.of(items.get(0)), "Responsive", "invariants");
            List<SearchResultDto> tagged = f.search().search("tag:Responsive").results();
            assertEquals(1, tagged.size(), "the tag applied a moment ago is searchable");
            assertEquals(items.get(0).id(), tagged.get(0).id());

            List<PathDto> registry = f.contents().getPaths().results();
            assertEquals(c.db().count(), registry.size(),
                    "the registry and the store describe the same material");
        }
    }

    // ============================================================== fixtures

    private static Path caseRoot(String name) throws IOException {
        Path root = workRoot().resolve(name).resolve("case");
        Files.createDirectories(root.getParent());
        return root;
    }

    private static LiveCase open(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root, "Invariants", s);
    }

    private static AegisFacades seed(LiveCase c, Path evidence) throws Exception {
        AegisFacades f = AegisFacades.open(c);
        seedInto(f, evidence);
        return f;
    }

    private static void seedInto(AegisFacades f, Path evidence) throws Exception {
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("invoice.txt"),
                "Invoice 2024 from Acme for consulting services. Payment due in 30 days.",
                StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("memo.txt"),
                "Internal memo about the Acme consulting agreement and its payment terms.",
                StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("note.txt"),
                "Handwritten note: call Acme about the outstanding balance.",
                StandardCharsets.UTF_8);

        int src = f.sources().createSource("Acme", "NL", "custodian", 0.8);
        int asp = f.aspects().createAspect("Plaintiff", 0.9);
        f.categories().createCategory("finance");
        f.keywords().createKeyword("payment due in 30 days", "finance");
        f.processing("Acme", "Plaintiff").processFolder(evidence.toString());
        f.contents().registerIngestedItems(src, asp);
    }

    // =============================================================== helpers

    private static Path workRoot() throws IOException {
        if (work == null) {
            String given = System.getProperty("aegis.test.work");
            Path base = given != null ? Path.of(given)
                    : Files.createTempDirectory("aegis-invariants-");
            Files.createDirectories(base);
            work = base;
        }
        return work;
    }

    private static Path sourceRoot() {
        for (String candidate : List.of("app/src/main/java", "src/main/java",
                "../app/src/main/java", "../../app/src/main/java")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p.resolve("com/aegis/fdx"))) {
                return p;
            }
        }
        throw new IllegalStateException("the main sources must be on hand to check the architecture");
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

    private static List<Path> mainSources() throws IOException {
        return filesUnder(sourceRoot()).stream()
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .toList();
    }

    private static List<Path> uiSources() throws IOException {
        return filesUnder(sourceRoot().resolve("com/aegis/fdx/ui")).stream()
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .toList();
    }

    private static String relativeSource(Path p) {
        return sourceRoot().relativize(p).toString().replace('\\', '/');
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Removes comments so that prose about the reference project is not mistaken for code. */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inLine = false;
        boolean inBlock = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLine) {
                if (ch == '\n') {
                    inLine = false;
                    out.append(ch);
                }
            } else if (inBlock) {
                if (ch == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
            } else if (inString) {
                out.append(ch);
                if (ch == '\\') {
                    if (i + 1 < source.length()) {
                        out.append(next);
                    }
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (inChar) {
                out.append(ch);
                if (ch == '\\') {
                    if (i + 1 < source.length()) {
                        out.append(next);
                    }
                    i++;
                } else if (ch == '\'') {
                    inChar = false;
                }
            } else if (ch == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (ch == '/' && next == '*') {
                inBlock = true;
                i++;
            } else {
                out.append(ch);
                if (ch == '"') {
                    inString = true;
                } else if (ch == '\'') {
                    inChar = true;
                }
            }
        }
        return out.toString();
    }

    /** The text of a parenthesised argument list starting at the '(' after {@code from}. */
    private static String balanced(String text, int from) {
        int open = text.indexOf('(', from);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(open + 1, i);
                }
            }
        }
        return text.substring(open + 1);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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

    // ================================================================== main

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.setProperty("aegis.test.work", args[0]);
        }
        ArchitectureInvariantsTest t = new ArchitectureInvariantsTest();
        String[] names = {
            "the case database is the only datastore",
            "the database is the record of authority; the index is derived",
            "nothing depends on a Python runtime or a web API",
            "the only external process is optional OCR",
            "no class in the processing core references the agent",
            "application startup constructs nothing AI-related",
            "a full processing run never reaches the agent",
            "a choice made in the interface survives a restart",
            "every control ends in an operation",
            "every screen is reachable from the navigation",
            "every backend capability has a way in",
            "a closed case releases its workers and its files",
            "search reads the authoritative index",
        };
        Check[] body = {
            t::caseDatabaseIsTheOnlyDatastore,
            t::theDatabaseOutlivesTheIndex,
            t::noWebApiOrPythonRuntimeDependency,
            t::theOnlyExternalProcessIsOptionalOcr,
            t::theProcessingCoreDoesNotReferenceTheAgent,
            t::nothingAiLoadsWhileTheApplicationStarts,
            t::aFullProcessingRunNeverReachesTheAgent,
            t::settingsChosenInTheInterfaceSurviveARestart,
            t::everyControlEndsInAnOperation,
            t::everyScreenIsReachableFromTheNavigation,
            t::everyBackendCapabilityHasAWayIn,
            t::aClosedCaseReleasesItsWorkersAndFiles,
            t::searchReadsTheAuthoritativeIndex,
        };
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < body.length; i++) {
            try {
                body[i].run();
                System.out.println("  ok    " + names[i]);
                passed++;
            } catch (Throwable e) {
                System.out.println("  FAIL  " + names[i] + " — " + e);
                failed++;
            }
        }
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface Check {
        void run() throws Exception;
    }
}
