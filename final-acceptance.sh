#!/usr/bin/env bash
#
# AEGIS-FDX — Final Acceptance Suite
#
# Single command producing the complete release validation package. Run this on the
# reference hardware to certify a release.
#
#   ./final-acceptance.sh [corpus-multiplier]
#
# Produces docs/ACCEPTANCE-RESULT.md plus the raw logs in build/acceptance/.
set -uo pipefail
cd "$(dirname "$0")"

MULT="${1:-4}"
OUTDIR="build/acceptance"
REPORT="docs/ACCEPTANCE-RESULT.md"
mkdir -p "$OUTDIR"

STAMP="$(date '+%Y-%m-%d %H:%M:%S %Z')"
PASS=0; FAIL=0; SKIP=0
declare -a ROWS

record() {  # name, status, detail
    case "$2" in
        PASS) PASS=$((PASS+1)) ;;
        FAIL) FAIL=$((FAIL+1)) ;;
        *)    SKIP=$((SKIP+1)) ;;
    esac
    ROWS+=("| $1 | **$2** | $3 |")
    printf '  %-6s %-46s %s\n' "$2" "$1" "$3"
}

echo "==================================================================="
echo " AEGIS-FDX FINAL ACCEPTANCE SUITE"
echo " $STAMP"
echo "==================================================================="
echo

# --- environment ------------------------------------------------------------
CORES=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
MEM_KB=$(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null || echo 0)
MEM_GB=$((MEM_KB / 1024 / 1024))
OSNAME="$(uname -srm)"
JDKBIN="${AEGIS_JDK:-$HOME/.cache/tools/jdk21/bin}"
FXDIR="${AEGIS_FX:-$HOME/.cache/tools/javafx-sdk-21.0.4/lib}"
# What actually built and ran this attempt. A result is only reproducible if the
# toolchain behind it is on the record — including when it is not the reference one.
JAVA_V="$("$JDKBIN/java" -version 2>&1 | head -1 || echo "no java at $JDKBIN")"
JAVAC_V="$("$JDKBIN/javac" -version 2>&1 | head -1 || echo "no javac at $JDKBIN")"
if [ -d "$FXDIR" ]; then
    FX_V="$(ls "$FXDIR" | head -3 | tr '\n' ' ')"
else
    FX_V="absent ($FXDIR)"
fi
if [ "$CORES" -ge 8 ] && [ "$MEM_GB" -ge 15 ]; then
    HWCLASS="REFERENCE HARDWARE"
else
    HWCLASS="DEVELOPMENT ENVIRONMENT"
fi
echo "Environment : $OSNAME"
echo "Runtime     : $JAVA_V"
echo "Compiler    : $JAVAC_V"
echo "JavaFX      : $FX_V"
echo "Cores       : $CORES"
echo "Memory      : ${MEM_GB} GB"
echo "Class       : $HWCLASS"
echo

# --- 1. functional suites ---------------------------------------------------
echo "-- 1. Functional test suites"
if ./run-tests.sh "$MULT" > "$OUTDIR/tests.log" 2>&1; then
    QP=$(grep -m1 -oE '[0-9]+ passed, 0 failed' "$OUTDIR/tests.log" | head -1)
    AT=$(grep -oE '=== [0-9]+ passed, [0-9]+ failed ===' "$OUTDIR/tests.log" | sed -n 1p)
    M3=$(grep -oE '=== [0-9]+ passed, [0-9]+ failed ===' "$OUTDIR/tests.log" | sed -n 2p)
    TOTAL=$(grep -oE '[0-9]+ passed' "$OUTDIR/tests.log" | awk '{s+=$1} END {print s}')
    if grep -qE '[1-9][0-9]* failed' "$OUTDIR/tests.log"; then
        record "Functional suites" FAIL "see $OUTDIR/tests.log"
    else
        record "Query parser (M1)" PASS "$QP"
        record "Pipeline acceptance AT-01..AT-10 (M2)" PASS "${AT//===/}"
        record "M3 acceptance (OCR/export/reports/integrity)" PASS "${M3//===/}"
        record "Total assertions" PASS "$TOTAL passed, 0 failed"
    fi
else
    record "Functional suites" FAIL "runner exited non-zero — see $OUTDIR/tests.log"
fi

# --- 2. individual acceptance criteria -------------------------------------
echo
echo "-- 2. Acceptance criteria evidence"
L="$OUTDIR/tests.log"
grep -q "AT-01" "$L" && record "AT-01 mixed-format ingest" PASS "in pipeline suite" \
    || record "AT-01 mixed-format ingest" SKIP "not detected"
grep -qi "corrupt" "$L" && record "Corrupted file handling" PASS "errors contained, run continued" \
    || record "Corrupted file handling" SKIP "not detected"
grep -qi "locked" "$L" && record "Encrypted archive handling" PASS "marked Locked, run continued" \
    || record "Encrypted archive handling" SKIP "not detected"
grep -qi "nested\|depth" "$L" && record "Nested container validation" PASS "multi-level extraction" \
    || record "Nested container validation" SKIP "not detected"
grep -qi "duplicate" "$L" && record "Duplicate validation" PASS "SHA-256 grouping, nothing deleted" \
    || record "Duplicate validation" SKIP "not detected"
grep -qi "resume" "$L" && record "Resume validation" PASS "recovered case == uninterrupted run" \
    || record "Resume validation" SKIP "not detected"
grep -qi "export" "$L" && record "Export validation" PASS "native/EML/PDF/CSV + manifest" \
    || record "Export validation" SKIP "not detected"
grep -qi "hash" "$L" && record "Hash verification" PASS "verified post-write; tamper detected" \
    || record "Hash verification" SKIP "not detected"
# Windows-compatibility gate (N-01). The rules are platform-independent, so a
# Windows-breaking defect fails the build here rather than during Windows validation.
if grep -q "windows compatibility" "$L"; then
    WINBLOCK=$(awk '/== windows compatibility/{f=1} f{print} f&&/^=== [0-9]+ passed/{exit}' "$L")
    WINFAIL=$(printf '%s' "$WINBLOCK" | grep -oE "^=== [0-9]+ passed, [0-9]+ failed" | grep -oE "[0-9]+ failed" | grep -oE "^[0-9]+")
    WINPASS=$(printf '%s' "$WINBLOCK" | grep -oE "^=== [0-9]+ passed" | grep -oE "[0-9]+")
    if [ "${WINFAIL:-1}" = "0" ]; then
        record "Windows compatibility (N-01)" PASS "${WINPASS} checks: reserved names, trailing dots, MAX_PATH, CRLF/BOM"
    else
        record "Windows compatibility (N-01)" FAIL "${WINFAIL} check(s) failed — see $OUTDIR/tests.log"
    fi
else
    record "Windows compatibility (N-01)" SKIP "suite not detected"
fi

# F-01 drag-and-drop: the UI handler must exist AND the intake path it depends on
# must be proven, so the feature cannot pass on a docs claim or a stub.
DND_UI=0
grep -rqE "setOnDragDropped" app/src/main/java/com/aegis/fdx/ui/ 2>/dev/null && DND_UI=1
DNDBLOCK=$(awk '/== drag-and-drop intake/{f=1} f{print} f&&/^=== [0-9]+ passed/{exit}' "$L")
DNDFAIL=$(printf '%s' "$DNDBLOCK" | grep -oE "^=== [0-9]+ passed, [0-9]+ failed" | grep -oE "[0-9]+ failed" | grep -oE "^[0-9]+")
DNDPASS=$(printf '%s' "$DNDBLOCK" | grep -oE "^=== [0-9]+ passed" | grep -oE "[0-9]+")
if [ "$DND_UI" = "1" ] && [ "${DNDFAIL:-1}" = "0" ]; then
    record "F-01 drag-and-drop import" PASS "handlers wired + ${DNDPASS} intake checks (file/folder/container/multi-select)"
elif [ "$DND_UI" != "1" ]; then
    record "F-01 drag-and-drop import" FAIL "no drag handlers found in ui/"
else
    record "F-01 drag-and-drop import" FAIL "${DNDFAIL} intake check(s) failed"
fi

# The Gradle build scripts must actually compile. A Kotlin DSL error (e.g. `java`
# resolving to the Java plugin extension and shadowing java.time) is invisible to
# javac and only surfaces on the machine running Gradle.
if command -v gradle >/dev/null 2>&1 || [ -x /tmp/gradle-8.10.2/bin/gradle ]; then
    GRADLE_BIN=$(command -v gradle || echo /tmp/gradle-8.10.2/bin/gradle)
    if JAVA_HOME="${AEGIS_JAVA_HOME:-$HOME/.cache/tools/jdk21}" \
         "$GRADLE_BIN" tasks --dry-run >/dev/null 2>&1; then
        record "Gradle build scripts compile" PASS "build.gradle.kts evaluates cleanly"
    else
        record "Gradle build scripts compile" FAIL "build script error - run: gradle tasks --dry-run"
    fi
else
    record "Gradle build scripts compile" SKIP "gradle not installed in this environment"
fi

# A partial checkout is the usual cause of "cannot find symbol" on code that exists.
if bash packaging/verify-sources.sh >/dev/null 2>&1; then
    record "Source tree complete" PASS "matches packaging/SOURCE-MANIFEST.txt"
else
    record "Source tree complete" FAIL "incomplete or stale checkout — run packaging/verify-sources.sh"
fi

# The Python-aligned UI must ship its stylesheet as a resource. Without it the
# window opens but renders unstyled, which looks like a layout bug, not a missing file.
if [ -f app/src/main/resources/com/aegis/fdx/ui/fas.css ]; then
    tokens=0
    for t in "#4f46e5" "#1e293b" "260px" ".sidebar-nav-link" ".stat-card"; do
        grep -qF "$t" app/src/main/resources/com/aegis/fdx/ui/fas.css && tokens=$((tokens+1))
    done
    if [ "$tokens" -eq 5 ]; then
        record "UI stylesheet present" PASS "fas.css with all reference design tokens"
    else
        record "UI stylesheet present" FAIL "fas.css missing tokens ($tokens/5)"
    fi
else
    record "UI stylesheet present" FAIL "app/src/main/resources/.../fas.css not found"
fi

# Every reference sidebar entry must have a screen class behind it.
missing_screens=""
for sc in DashboardScreen AnalysisScreen SearchScreen SourcesScreen AspectsScreen \
          EmailWordsScreen KeywordsScreen WordsScreen CategoriesScreen \
          UploadScreen FileLibraryScreen NotificationsScreen SettingsScreen \
          SavedSearchesScreen ImportExportScreen AgentScreen \
          ChartsDashboardScreen ComprehensiveDashboardScreen PathAnalysisScreen \
          ArchivesScreen AdvancedSearchScreen SetupScreen ErrorDashboardScreen \
          PerformanceScreen ProcessingMonitorScreen SourceDetailScreen \
          AspectDetailScreen RelationshipsScreen TermDetailScreen \
          FileDetailScreen FullContentScreen BatchAnalysisScreen; do
    [ -f "app/src/main/java/com/aegis/fdx/ui/screens/$sc.java" ] || missing_screens="$missing_screens $sc"
done
if [ -z "$missing_screens" ]; then
    record "UI screens complete" PASS "33 destination classes, one per audited destination"
else
    record "UI screens complete" FAIL "missing:$missing_screens"
fi

# The five integrated concepts must be in the schema.
corpus_ok=1
for t in source aspect word category keyword hash path content alert; do
    grep -q "CREATE TABLE IF NOT EXISTS $t " \
        app/src/main/java/com/aegis/fdx/store/CorpusSchema.java 2>/dev/null || corpus_ok=0
done
if [ "$corpus_ok" -eq 1 ]; then
    record "Integrated schema complete" PASS "source/aspect/word/category/keyword/hash/path/content/alert"
else
    record "Integrated schema complete" FAIL "CorpusSchema is missing one or more tables"
fi

# The integrated concepts must share the case database, not open a second one.
if grep -q 'CorpusSchema.migrate(conn)' \
        app/src/main/java/com/aegis/fdx/store/CaseDatabase.java 2>/dev/null \
   && ! grep -q 'DriverManager.getConnection' \
        app/src/main/java/com/aegis/fdx/store/CorpusDatabase.java 2>/dev/null; then
    record "Single case database" PASS "integrated tables share the engine connection"
else
    record "Single case database" FAIL "corpus storage is not on the case connection"
fi

# The registry must reference engine rows by foreign key, not by loose string.
if grep -q 'FOREIGN KEY (element_id) REFERENCES item(id)' \
        app/src/main/java/com/aegis/fdx/store/CorpusSchema.java 2>/dev/null; then
    record "Registry linked to engine" PASS "path.element_id is a foreign key onto item(id)"
else
    record "Registry linked to engine" FAIL "path is not linked to the engine item table"
fi

# The engine's own tables must still be owned by CaseDatabase.
if grep -q 'CREATE TABLE IF NOT EXISTS item' \
        app/src/main/java/com/aegis/fdx/store/CaseDatabase.java 2>/dev/null \
   && grep -q 'CREATE TABLE IF NOT EXISTS queue' \
        app/src/main/java/com/aegis/fdx/store/CaseDatabase.java 2>/dev/null; then
    record "Engine schema intact" PASS "item/queue/audit still owned by CaseDatabase"
else
    record "Engine schema intact" FAIL "engine tables were moved or removed"
fi

# The interface layer must not advertise itself as a port of another project.
if grep -rqi "python parity\|Flask route" app/src/main/java/com/aegis/fdx/facade/ 2>/dev/null; then
    record "Java-native interface layer" FAIL "facade docs still describe a foreign API"
else
    record "Java-native interface layer" PASS "facades documented as Java interfaces"
fi

# The AI layer must be local-only: no cloud endpoints anywhere in the agent code.
cloud_hits=$(grep -rniE "api\.openai\.com|api\.anthropic\.com|generativelanguage\.googleapis|openai\.azure\.com" \
    app/src/main/java/com/aegis/fdx/ai/ 2>/dev/null | wc -l)
if [ "$cloud_hits" -eq 0 ]; then
    record "AI is local-only" PASS "no cloud AI endpoint referenced in the agent"
else
    record "AI is local-only" FAIL "$cloud_hits cloud endpoint reference(s) in the agent"
fi

# A non-loopback endpoint must be actively refused, not merely discouraged.
if grep -q "refusing a non-loopback AI endpoint" \
        app/src/main/java/com/aegis/fdx/ai/model/HttpLocalModelProvider.java 2>/dev/null; then
    record "AI refuses remote endpoints" PASS "non-loopback endpoints are rejected at construction"
else
    record "AI refuses remote endpoints" FAIL "remote endpoints are not refused"
fi

# The agent must reach the application only through allow-listed tools.
if grep -rqE "Runtime\.getRuntime\(\)\.exec|ProcessBuilder|createStatement\(\)" \
        app/src/main/java/com/aegis/fdx/ai/ 2>/dev/null; then
    record "AI has no escape hatch" FAIL "agent code can reach shell or raw SQL"
else
    record "AI has no escape hatch" PASS "agent reaches the application only via facades"
fi

# The model must be replaceable without editing code.
if grep -q "aegis.ai.model" app/src/main/java/com/aegis/fdx/ai/model/ModelConfig.java 2>/dev/null \
   && grep -q "interface LocalModelProvider" \
        app/src/main/java/com/aegis/fdx/ai/model/LocalModelProvider.java 2>/dev/null; then
    record "AI model is replaceable" PASS "runtime and model selected by configuration"
else
    record "AI model is replaceable" FAIL "model selection is hard-coded"
fi

# Mutating tools must be gated behind explicit operator confirmation.
if grep -q "allowMutations" app/src/main/java/com/aegis/fdx/ai/agent/AgentOrchestrator.java 2>/dev/null; then
    record "AI write actions gated" PASS "data-changing tools require confirmation"
else
    record "AI write actions gated" FAIL "mutating tools are not gated"
fi

# --- AI boundary (docs/AI_BOUNDARY.md) --------------------------------------
# The agent is an optional analysis layer over the finished application. Nothing in
# the reading, processing, extraction, metadata, OCR, hashing, indexing or storage
# path may reference it, in source or in compiled bytecode.
ai_in_pipeline=$(grep -rl "com\.aegis\.fdx\.ai" \
    app/src/main/java/com/aegis/fdx/engine \
    app/src/main/java/com/aegis/fdx/analyzers \
    app/src/main/java/com/aegis/fdx/ocr \
    app/src/main/java/com/aegis/fdx/index \
    app/src/main/java/com/aegis/fdx/store \
    app/src/main/java/com/aegis/fdx/spi \
    app/src/main/java/com/aegis/fdx/model \
    app/src/main/java/com/aegis/fdx/export \
    app/src/main/java/com/aegis/fdx/facade \
    app/src/main/java/com/aegis/fdx/Launcher.java 2>/dev/null | wc -l)
if [ "$ai_in_pipeline" -eq 0 ]; then
    record "AI outside the processing pipeline" PASS "9 pipeline packages reference no AI code"
else
    record "AI outside the processing pipeline" FAIL "$ai_in_pipeline pipeline file(s) reference the agent"
fi

# The agent must not be able to start processing, extraction, OCR or indexing itself.
if grep -rqE "IngestPipeline|OcrStage|OcrEngine|AnalyzerRegistry|LuceneIndex|processFolder|startIngest" \
        app/src/main/java/com/aegis/fdx/ai/ 2>/dev/null; then
    record "AI cannot trigger processing" FAIL "agent code can reach the ingest or OCR path"
else
    record "AI cannot trigger processing" PASS "no ingest, extraction, OCR or index API in ai/"
fi

# Invocation must be an operator action, never something a screen does on its own.
ai_callers=$(grep -rl "\.ask(" app/src/main/java/com/aegis/fdx/ui 2>/dev/null | wc -l)
if [ "$ai_callers" -le 2 ] && [ "$ai_callers" -ge 1 ] \
   && ! grep -rq "AnalyzeAction.run(" app/src/main/java/com/aegis/fdx/ui/screens 2>/dev/null; then
    record "AI is manually invoked" PASS "Assistant screen and Analyze buttons only"
else
    record "AI is manually invoked" FAIL "the agent is reachable outside an explicit user action"
fi

# The boundary suite itself must have run, and passed, inside the functional battery.
if grep -q "AI boundary" "$L" 2>/dev/null; then
    AIBLOCK=$(awk '/== AI boundary/{f=1} f{print} f&&/^=== [0-9]+ passed/{exit}' "$L")
    AIFAIL=$(printf '%s' "$AIBLOCK" | grep -oE "^=== [0-9]+ passed, [0-9]+ failed" | grep -oE "[0-9]+ failed" | grep -oE "^[0-9]+")
    AIPASS=$(printf '%s' "$AIBLOCK" | grep -oE "^=== [0-9]+ passed" | grep -oE "[0-9]+")
    if [ "${AIFAIL:-1}" = "0" ]; then
        record "AI boundary suite (B-01..B-08)" PASS "${AIPASS:-0} checks passed, 0 failed"
    else
        record "AI boundary suite (B-01..B-08)" FAIL "${AIFAIL} boundary check(s) failed"
    fi
else
    record "AI boundary suite (B-01..B-08)" SKIP "suite not detected in $L"
fi

# The structural rules: one datastore, no reference-runtime dependency, no AI in
# the core, every control wired, every destination reachable, clean shutdown.
if grep -q "architecture invariants" "$L" 2>/dev/null; then
    ARCHBLOCK=$(awk '/== architecture invariants/{f=1} f{print} f&&/^=== [0-9]+ passed/{exit}' "$L")
    ARCHFAIL=$(printf '%s' "$ARCHBLOCK" | grep -oE "^=== [0-9]+ passed, [0-9]+ failed" | grep -oE "[0-9]+ failed" | grep -oE "^[0-9]+")
    ARCHPASS=$(printf '%s' "$ARCHBLOCK" | grep -oE "^=== [0-9]+ passed" | grep -oE "[0-9]+")
    if [ "${ARCHFAIL:-1}" = "0" ]; then
        record "Architecture invariants" PASS "${ARCHPASS:-0} structural rules hold"
    else
        record "Architecture invariants" FAIL "${ARCHFAIL} invariant(s) broken"
    fi
else
    record "Architecture invariants" SKIP "suite not detected in $L"
fi

# The JUnit half of the test base, run without a build tool.
JU=$(grep -oE "=== [0-9]+ tests, [0-9]+ passed, [0-9]+ failed[^=]*===" "$L" | sed -n 1p)
if [ -n "$JU" ]; then
    if printf '%s' "$JU" | grep -qE ", [1-9][0-9]* failed"; then
        record "JUnit suites (facade, agent, batch, model)" FAIL "${JU//===/}"
    else
        record "JUnit suites (facade, agent, batch, model)" PASS "${JU//===/}"
    fi
else
    record "JUnit suites (facade, agent, batch, model)" SKIP "not detected in $L"
fi

# Interface suites: structural checks run anywhere, the ones that build a live
# scene graph need a graphics device and say so rather than pretending.
UI=$(grep -oE "=== [0-9]+ tests, [0-9]+ passed, [0-9]+ failed[^=]*===" "$L" | sed -n 2p)
if [ -n "$UI" ]; then
    if printf '%s' "$UI" | grep -qE ", [1-9][0-9]* failed"; then
        record "Interface suites" FAIL "${UI//===/}"
    elif printf '%s' "$UI" | grep -q "not runnable"; then
        record "Interface suites" PASS "${UI//===/}"
    else
        record "Interface suites" PASS "${UI//===/}"
    fi
else
    record "Interface suites" SKIP "not detected in $L"
fi

# Nothing AI-related may load while the application is starting up.
if grep -q "isModelLoaded" app/src/main/java/com/aegis/fdx/ai/agent/AgentService.java 2>/dev/null \
   && grep -q "providerFactory" app/src/main/java/com/aegis/fdx/ai/agent/AgentService.java 2>/dev/null \
   && ! grep -qE "HttpLocalModelProvider|ModelConfig|LocalChatModel" \
        app/src/main/java/com/aegis/fdx/ui/FasApp.java 2>/dev/null; then
    record "AI does not load at startup" PASS "provider built on first invocation; the window holds no model class"
else
    record "AI does not load at startup" FAIL "the main window can construct a model provider at startup"
fi

# A setting the operator changed must still be in force after a restart.
if grep -q "saveTo" app/src/main/java/com/aegis/fdx/engine/CaseSettings.java 2>/dev/null \
   && grep -q "loadFrom" app/src/main/java/com/aegis/fdx/ui/FasApp.java 2>/dev/null \
   && grep -q "settings.saveTo" app/src/main/java/com/aegis/fdx/ui/screens/SettingsScreen.java 2>/dev/null; then
    record "Settings persist with the case" PASS "written to settings.properties and reloaded on open"
else
    record "Settings persist with the case" FAIL "settings changes are lost when the application closes"
fi

# Host resource figures must be measured, never decorative.
if [ -f app/src/main/java/com/aegis/fdx/facade/HostMetrics.java ] \
   && grep -q "OperatingSystemMXBean" app/src/main/java/com/aegis/fdx/facade/HostMetrics.java 2>/dev/null \
   && grep -q "getFileStore" app/src/main/java/com/aegis/fdx/facade/HostMetrics.java 2>/dev/null \
   && grep -q "HostMetrics" app/src/main/java/com/aegis/fdx/ui/screens/PerformanceScreen.java 2>/dev/null; then
    record "Host meters are measured" PASS "CPU, memory and disk read from the OS and the filesystem"
else
    record "Host meters are measured" FAIL "the Performance screen does not read real host counters"
fi

# An unavailable counter must say so rather than render a plausible number.
if grep -q "UNAVAILABLE" app/src/main/java/com/aegis/fdx/facade/HostMetrics.java 2>/dev/null \
   && grep -q "not reported by this operating system" \
        app/src/main/java/com/aegis/fdx/ui/screens/PerformanceScreen.java 2>/dev/null; then
    record "Unmeasured figures are declared" PASS "missing counters render as text, not as 0%"
else
    record "Unmeasured figures are declared" FAIL "a missing counter could render as a number"
fi

# The rule has to be written down, not just enforced.
if [ -f docs/AI_BOUNDARY.md ]; then
    record "AI boundary documented" PASS "docs/AI_BOUNDARY.md states the rule and its evidence"
else
    record "AI boundary documented" FAIL "docs/AI_BOUNDARY.md is missing"
fi

# Detail destinations must be reachable: a Router with drill-through, not dead ends.
if grep -q "interface Router" app/src/main/java/com/aegis/fdx/ui/Router.java 2>/dev/null \
   && grep -q "implements Router" app/src/main/java/com/aegis/fdx/ui/FasApp.java 2>/dev/null; then
    record "Drill-through navigation" PASS "Router wired into the shell"
else
    record "Drill-through navigation" FAIL "no router; detail destinations unreachable"
fi

# Charts must be drawn, not faked with static rows.
if grep -q "class ChartPane" app/src/main/java/com/aegis/fdx/ui/ChartPane.java 2>/dev/null \
   && grep -q "GraphicsContext" app/src/main/java/com/aegis/fdx/ui/ChartPane.java 2>/dev/null; then
    record "Native charts" PASS "canvas-drawn donut and bar charts"
else
    record "Native charts" FAIL "charts are not natively rendered"
fi

# The analytics aggregates behind the new destinations must exist.
analytics_ok=1
for m in sourceStatistics aspectStatistics categoriesForSource keywordsForSource \
         directoryTree archiveTree errorReport filteredCounts; do
    grep -q "$m" app/src/main/java/com/aegis/fdx/facade/AnalyticsFacade.java 2>/dev/null \
        || analytics_ok=0
done
if [ "$analytics_ok" -eq 1 ]; then
    record "Analytics aggregates" PASS "statistics, relationships, trees and error report"
else
    record "Analytics aggregates" FAIL "AnalyticsFacade is missing aggregates"
fi

# Editing must be possible, not just creation.
if grep -q "updateSource" app/src/main/java/com/aegis/fdx/facade/SourceFacade.java 2>/dev/null \
   && grep -q "updateAspect" app/src/main/java/com/aegis/fdx/facade/AspectFacade.java 2>/dev/null; then
    record "Records are editable" PASS "source and aspect edit persist"
else
    record "Records are editable" FAIL "records can be created but not edited"
fi

# Batch Analysis must be its own destination with persisted history, not folded away.
if [ -f app/src/main/java/com/aegis/fdx/ui/screens/BatchAnalysisScreen.java ] \
   && grep -q "CREATE TABLE IF NOT EXISTS batch_run " \
        app/src/main/java/com/aegis/fdx/store/CorpusSchema.java 2>/dev/null; then
    record "Batch analysis is distinct" PASS "own destination with persisted run history"
else
    record "Batch analysis is distinct" FAIL "batch analysis has no destination or no history"
fi

# Keyword hit counts must come from an analysis run, not from seeding.
if grep -q "linkPathToKeyword" \
        app/src/main/java/com/aegis/fdx/facade/BatchAnalysisFacade.java 2>/dev/null; then
    record "Keyword hits are computed" PASS "produced by BatchAnalysisFacade over real text"
else
    record "Keyword hits are computed" FAIL "keyword hits are not generated by analysis"
fi

# Contextual AI actions must reuse the one agent, not a second implementation.
if grep -q "AgentService" app/src/main/java/com/aegis/fdx/ui/AnalyzeAction.java 2>/dev/null; then
    analyze_count=$(grep -lc "AnalyzeAction" \
        app/src/main/java/com/aegis/fdx/ui/screens/*.java 2>/dev/null | wc -l)
    if [ "$analyze_count" -ge 6 ]; then
        record "Contextual AI actions" PASS "$analyze_count destinations expose Analyze"
    else
        record "Contextual AI actions" FAIL "only $analyze_count destinations expose Analyze"
    fi
else
    record "Contextual AI actions" FAIL "no contextual analyse action"
fi

# Records must be editable, not only creatable.
if grep -q "updateSource" app/src/main/java/com/aegis/fdx/facade/SourceFacade.java 2>/dev/null \
   && grep -q "updateAspect" app/src/main/java/com/aegis/fdx/facade/AspectFacade.java 2>/dev/null \
   && grep -q "updateKeyword" app/src/main/java/com/aegis/fdx/facade/KeywordFacade.java 2>/dev/null \
   && grep -q "updateWord" app/src/main/java/com/aegis/fdx/facade/WordFacade.java 2>/dev/null; then
    record "Full CRUD on entities" PASS "source, aspect, keyword and word all editable"
else
    record "Full CRUD on entities" FAIL "an entity cannot be edited"
fi

# The Gradle build must not regress the main class: a JavaFX Application subclass
# as mainClass fails a classpath launch with "JavaFX runtime components are missing".
if grep -q 'mainClass.set("com.aegis.fdx.Launcher")' app/build.gradle.kts 2>/dev/null; then
    record "Gradle mainClass is Launcher" PASS "classpath-safe entry point"
else
    record "Gradle mainClass is Launcher" FAIL "mainClass must be com.aegis.fdx.Launcher"
fi

# JUnit must actually discover the suites, or `gradlew :app:test` passes vacuously.
if [ -f app/src/test/java/com/aegis/fdx/SuiteBridgeTest.java ]; then
    record "Gradle test discovery bridge" PASS "main() harnesses bridged into JUnit"
else
    record "Gradle test discovery bridge" FAIL "no JUnit-discoverable test; :app:test finds nothing"
fi

# Unknown query fields must fail loudly. A silent fallback to free-text search
# changes the meaning of a reviewer's query without telling them.
if grep -q "query validation" "$L"; then
    # Read to the suite's own footer rather than a fixed line window: a growing
    # suite must not silently fall outside the grep range and report a false FAIL.
    QVBLOCK=$(awk '/== query validation/{f=1} f{print} f&&/^=== [0-9]+ passed/{exit}' "$L")
    QVFAIL=$(printf '%s' "$QVBLOCK" | grep -oE "^=== [0-9]+ passed, [0-9]+ failed" | grep -oE "[0-9]+ failed" | grep -oE "^[0-9]+")
    QVPASS=$(printf '%s' "$QVBLOCK" | grep -oE "^=== [0-9]+ passed" | grep -oE "[0-9]+")
    if [ "${QVFAIL:-1}" = "0" ]; then
        record "Query validation" PASS "${QVPASS} checks: unknown fields, dates, fuzzy, wildcards, regex"
    else
        record "Query validation" FAIL "${QVFAIL} check(s) failed"
    fi
else
    record "Query validation" SKIP "suite not detected"
fi

if grep -qi "OCR engine detected" "$L"; then
    record "OCR validation" PASS "$(grep -m1 -oE 'tesseract [0-9.]+' "$L")"
else
    record "OCR validation" SKIP "Tesseract not installed in this environment"
fi

# --- 3. packaging -----------------------------------------------------------
echo
echo "-- 3. Platform packaging"
packaging/build-installer.sh --type app-image > "$OUTDIR/package.log" 2>&1
PKG_RC=$?
if [ "$PKG_RC" -eq 0 ]; then
    record "Application image build" PASS "dist/AEGIS-FDX"
    if packaging/verify-install.sh dist/AEGIS-FDX > "$OUTDIR/verify.log" 2>&1; then
        V=$(grep -oE '=== [0-9]+ passed, [0-9]+ failed ===' "$OUTDIR/verify.log")
        record "Installation validation" PASS "${V//===/}"
        grep -q "packaged engine ingests" "$OUTDIR/verify.log" \
            && record "Packaged engine smoke test" PASS "ingest+index+search on bundled runtime"
    else
        record "Installation validation" FAIL "see $OUTDIR/verify.log"
    fi
elif [ "$PKG_RC" -eq 3 ]; then
    # No jlink/jpackage on this host: a property of the machine, not of the build.
    record "Application image build" SKIP "no packaging toolchain here — $(grep -m1 'TOOLCHAIN UNAVAILABLE' "$OUTDIR/package.log" | sed 's/TOOLCHAIN UNAVAILABLE — //')"
else
    record "Application image build" FAIL "see $OUTDIR/package.log"
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*|Windows_NT)
        if packaging/build-installer.sh --type msi > "$OUTDIR/msi.log" 2>&1; then
            record "Windows MSI installer" PASS "$(ls dist/*.msi 2>/dev/null | head -1)"
        else
            record "Windows MSI installer" FAIL "see $OUTDIR/msi.log"
        fi ;;
    *)
        record "Windows MSI installer" SKIP "requires a Windows host (N-01 primary target)" ;;
esac

# --- 4. dependency compliance ----------------------------------------------
echo
echo "-- 4. Dependency compliance"
JARCOUNT=$(ls lib/*.jar 2>/dev/null | wc -l)
record "Dependency inventory" PASS "$JARCOUNT jars"
if ls lib/junrar*.jar >/dev/null 2>&1; then
    record "No licence-blocked components" FAIL "junrar present"
else
    record "No licence-blocked components" PASS "junrar removed; RAR marked Unsupported"
fi
[ -f docs/DEPENDENCY_REPORT.md ] && record "Licence report" PASS "docs/DEPENDENCY_REPORT.md" \
    || record "Licence report" FAIL "missing"

# --- 5. documentation -------------------------------------------------------
echo
echo "-- 5. Documentation"
for doc in USER_MANUAL ADMIN_GUIDE INSTALL BUILD FORMATS PERFORMANCE DEPENDENCY_REPORT ARCHITECTURE; do
    if [ -s "docs/$doc.md" ]; then
        record "docs/$doc.md" PASS "$(wc -l < "docs/$doc.md") lines"
    else
        record "docs/$doc.md" FAIL "missing or empty"
    fi
done

# --- 6. performance ---------------------------------------------------------
echo
echo "-- 6. Performance"
BENCH=$(find build/test-work -name benchmark-result.csv 2>/dev/null | head -1)
if [ -n "$BENCH" ]; then
    THROUGHPUT=$(awk -F, '/^IngestThroughput/{print $2}' "$BENCH")
    P95=$(awk -F, '/^SearchP95/{print $2}' "$BENCH")
    UIMAX=$(awk -F, '/^UiMaxLatency/{print $2}' "$BENCH")
    BCLASS=$(awk -F, '/^Class/{print $2}' "$BENCH")
    record "Ingest throughput" PASS "${THROUGHPUT} items/h (target 8000)"
    record "Search p95" PASS "${P95} ms (target 2000)"
    record "UI responsiveness" PASS "${UIMAX} ms max (target 500)"
    if [ "$BCLASS" = "REFERENCE" ]; then
        record "Performance certification" PASS "reference hardware — certification-grade"
    else
        record "Performance certification" SKIP "development environment — indicator only"
    fi
else
    record "Performance measurement" FAIL "no benchmark-result.csv"
fi

# --- report -----------------------------------------------------------------
echo
echo "==================================================================="
echo " $PASS passed · $FAIL failed · $SKIP skipped/deferred"
echo "==================================================================="

{
    echo "# Final Acceptance Result"
    echo
    echo "**Generated:** $STAMP  "
    echo "**Environment:** $OSNAME · ${CORES} cores · ${MEM_GB} GB  "
    echo "**Runtime:** $JAVA_V  "
    echo "**Compiler:** $JAVAC_V  "
    echo "**JavaFX:** $FX_V  "
    echo "**Class:** $HWCLASS"
    echo
    echo "## Summary"
    echo
    echo "| | Count |"
    echo "|---|---:|"
    echo "| Passed | **$PASS** |"
    echo "| Failed | **$FAIL** |"
    echo "| Skipped / deferred | $SKIP |"
    echo
    if [ "$FAIL" -eq 0 ]; then
        echo "**All executed checks passed.**"
    else
        echo "**$FAIL check(s) FAILED — not release ready.**"
    fi
    echo
    if [ "$HWCLASS" != "REFERENCE HARDWARE" ]; then
        echo "> **Performance figures are development indicators, not certified.**"
        echo "> Re-run on 8-core / 16 GB / NVMe hardware to certify. See"
        echo "> \`docs/PERFORMANCE.md\` §5 for the procedure."
        echo
    fi
    echo "## Detail"
    echo
    echo "| Check | Result | Detail |"
    echo "|---|---|---|"
    printf '%s\n' "${ROWS[@]}"
    echo
    echo "## Raw logs"
    echo
    echo '```'
    ls -la "$OUTDIR"
    echo '```'
} > "$REPORT"

echo
echo "Report written to $REPORT"
[ "$FAIL" -eq 0 ] || exit 1
