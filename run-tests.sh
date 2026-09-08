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

echo; echo "== drag-and-drop intake (F-01) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.DragDropIngestTest "$WORK/dnd"

echo; echo "== windows compatibility (N-01) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.WindowsCompatibilityTest "$WORK/win"

echo; echo "== benchmark (N-02 / F-18 / N-03) =="
"$JDK/java" -Xmx900m -cp "$OUT:$CP" com.aegis.fdx.Benchmark "$WORK/bench" "${1:-4}"

echo; echo "ALL SUITES COMPLETED"
