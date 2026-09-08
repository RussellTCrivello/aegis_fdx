#!/usr/bin/env bash
# Runs the complete AEGIS-FDX test battery. One command, no Gradle required.
set -euo pipefail
cd "$(dirname "$0")"

JDK="${AEGIS_JDK:-$HOME/.cache/tools/jdk21/bin}"
FX="${AEGIS_FX:-$HOME/.cache/tools/javafx-sdk-21.0.4/lib}"
OUT="${AEGIS_OUT:-build/classes}"
WORK="${AEGIS_WORK:-build/test-work}"
CP="$(ls lib/*.jar | tr '\n' ':')"

# --- OCR (M3-A). Optional: the suite reports honestly when Tesseract is absent.
OCR_ROOT="${AEGIS_OCR_ROOT:-$HOME/.cache/tools/ocr/root}"
OCR_OPTS=""
if [ -x "$OCR_ROOT/usr/bin/tesseract" ]; then
    OCR_OPTS="-Daegis.tesseract=$OCR_ROOT/usr/bin/tesseract"
    OCR_OPTS="$OCR_OPTS -Daegis.tessdata=$OCR_ROOT/usr/share/tesseract-ocr/5/tessdata"
    echo "OCR: bundled Tesseract at $OCR_ROOT"
elif command -v tesseract >/dev/null 2>&1; then
    echo "OCR: system Tesseract ($(tesseract --version 2>&1 | head -1))"
else
    echo "OCR: not installed — OCR assertions will report as skipped"
fi

echo "== compiling main =="
mkdir -p "$OUT"
"$JDK/javac" -nowarn --module-path "$FX" --add-modules javafx.controls \
    -cp "$CP" -d "$OUT" $(find app/src/main/java -name '*.java')
cp -r app/src/main/resources/* "$OUT/" 2>/dev/null || true

echo "== compiling tests =="
"$JDK/javac" -nowarn --module-path "$FX" --add-modules javafx.controls \
    -cp "$OUT:$CP" -d "$OUT" $(find app/src/test -name '*.java')

rm -rf "$WORK"; mkdir -p "$WORK"

echo; echo "== query parser unit tests =="
"$JDK/java" -cp "$OUT:$CP" com.aegis.fdx.QueryParserTest

echo; echo "== generating test dataset (D-04) =="
"$JDK/java" -cp "$OUT:$CP" com.aegis.fdx.TestDataset "$WORK/testdata"

echo; echo "== pipeline acceptance battery (AT-01..AT-10) =="
"$JDK/java" -Xmx900m $OCR_OPTS -cp "$OUT:$CP" com.aegis.fdx.PipelineAcceptanceTest "$WORK/at"

echo; echo "== milestone 3 acceptance (OCR / export / reports / integrity) =="
"$JDK/java" -Xmx900m $OCR_OPTS -cp "$OUT:$CP" com.aegis.fdx.M3AcceptanceTest "$WORK/m3"

echo; echo "== query validation (unknown fields / dates / regex) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.QueryValidationTest

echo; echo "== AI boundary (B-01..B-08: optional, manual, read-only, absent at startup) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.AiBoundaryTest "$WORK/aiboundary"

echo; echo "== case settings persistence (choices survive a restart) =="
"$JDK/java" -cp "$OUT:$CP" com.aegis.fdx.SettingsPersistenceTest

echo; echo "== host metrics (measured CPU, memory and disk, or declared missing) =="
"$JDK/java" -cp "$OUT:$CP" com.aegis.fdx.HostMetricsTest

echo; echo "== drag-and-drop intake (F-01) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.DragDropIngestTest "$WORK/dnd"

echo; echo "== windows compatibility (N-01) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.WindowsCompatibilityTest "$WORK/win"

echo; echo "== JUnit suites (facade, agent, batch, model, scenario) =="
# These used to run only under Gradle, which needs the network to resolve
# dependencies; the JUnit Platform launcher in lib/ runs them with the same jars
# that compiled the project, so the offline battery covers the whole test base.
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.JUnitRunner \
    com.aegis.fdx.FacadeParityTest \
    com.aegis.fdx.AiAgentTest \
    com.aegis.fdx.BatchAnalysisTest \
    com.aegis.fdx.IntegrationModelTest \
    com.aegis.fdx.EndToEndScenarioTest \
    com.aegis.fdx.SettingsPersistenceTest \
    com.aegis.fdx.HostMetricsTest

echo; echo "== architecture invariants (structural rules the build must not break) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.ArchitectureInvariantsTest "$WORK/arch"

echo; echo "== failure and recovery (damaged index, locked case, unreadable database) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.ResilienceTest "$WORK/resilience"

echo; echo "== coverage inventory (every destination classified, every claim resolvable) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.CoverageMatrixTest

echo; echo "== interface suites (need a JavaFX runtime with native libraries) =="
# The runner reports checks that cannot run for want of a graphics device as
# "not runnable on this machine" and still fails the battery for anything else.
"$JDK/java" -Xmx900m --module-path "$FX" --add-modules javafx.controls,javafx.graphics \
    -cp "$OUT:$CP" com.aegis.fdx.JUnitRunner \
    com.aegis.fdx.UiParityTest com.aegis.fdx.DestinationCoverageTest com.aegis.fdx.SuiteBridgeTest

echo; echo "== benchmark (N-02 / F-18 / N-03) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.Benchmark "$WORK/bench" "${1:-4}"

echo; echo "ALL SUITES COMPLETED"
