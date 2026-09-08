#!/usr/bin/env bash
#
# Installation validation (final acceptance item E).
#
# Verifies a packaged AEGIS-FDX image is complete and actually runnable, rather
# than assuming a successful jpackage run implies a working install.
#
# Usage:
#   packaging/verify-install.sh [path-to-app-image]
#
# Defaults to dist/AEGIS-FDX. On Windows point it at the installed directory,
# e.g. "C:/Program Files/AEGIS-FDX".
set -uo pipefail
cd "$(dirname "$0")/.."

APP="${1:-dist/AEGIS-FDX}"
PASS=0
FAIL=0

check() {
    if [ "$2" = "0" ]; then
        echo "  PASS  $1"
        PASS=$((PASS + 1))
    else
        echo "  FAIL  $1"
        FAIL=$((FAIL + 1))
    fi
}

echo "== AEGIS-FDX installation verification =="
echo "   image: $APP"
echo

# ---- layout ----------------------------------------------------------------
[ -d "$APP" ]; check "application image exists" $?

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*|Windows_NT)
        LAUNCHER="$APP/AEGIS-FDX.exe"; APPDIR="$APP/app"; RT="$APP/runtime" ;;
    Darwin)
        LAUNCHER="$APP/Contents/MacOS/AEGIS-FDX"
        APPDIR="$APP/Contents/app"; RT="$APP/Contents/runtime" ;;
    *)
        LAUNCHER="$APP/bin/AEGIS-FDX"; APPDIR="$APP/lib/app"; RT="$APP/lib/runtime" ;;
esac

[ -f "$LAUNCHER" ]; check "native launcher present" $?
[ -x "$LAUNCHER" ]; check "launcher is executable" $?
[ -d "$RT" ];       check "bundled Java runtime present" $?
[ -d "$APPDIR" ];   check "application directory present" $?

# ---- runtime is self-contained --------------------------------------------
if [ -d "$RT" ]; then
    RTJAVA="$RT/bin/java"
    [ -f "$RTJAVA" ] || RTJAVA="$RT/bin/java.exe"
    [ -f "$RTJAVA" ]; check "runtime contains a java executable" $?
    if [ -f "$RTJAVA" ]; then
        RTVER="$("$RTJAVA" -version 2>&1 | head -1)"
        echo "$RTVER" | grep -qE '"(2[1-9]|[3-9][0-9])' ; check "runtime is Java 21+ ($RTVER)" $?
    fi
fi

# ---- dependencies ----------------------------------------------------------
if [ -d "$APPDIR" ]; then
    JARS=$(ls "$APPDIR"/*.jar 2>/dev/null | wc -l)
    [ "$JARS" -gt 20 ]; check "dependency jars bundled ($JARS)" $?

    ls "$APPDIR"/aegis-fdx-*.jar >/dev/null 2>&1
    check "application jar present" $?

    ls "$APPDIR"/lucene-core-*.jar >/dev/null 2>&1; check "Lucene bundled" $?
    ls "$APPDIR"/pdfbox-*.jar      >/dev/null 2>&1; check "PDFBox bundled" $?
    ls "$APPDIR"/sqlite-jdbc-*.jar >/dev/null 2>&1; check "SQLite bundled" $?
    ls "$APPDIR"/javafx.controls.jar >/dev/null 2>&1; check "JavaFX bundled" $?

    # JavaFX needs its native libraries, not just the jars. Shipping only the
    # jars yields an image that fails at startup.
    NATIVES=$(ls "$APPDIR"/*.so "$APPDIR"/*.dll "$APPDIR"/*.dylib 2>/dev/null | wc -l)
    [ "$NATIVES" -gt 0 ]; check "JavaFX native libraries bundled ($NATIVES)" $?

    # Licence compliance: junrar must not ship in v1.
    ! ls "$APPDIR"/junrar-*.jar >/dev/null 2>&1
    check "no licence-blocked components bundled (junrar absent)" $?

    CFG="$APPDIR/AEGIS-FDX.cfg"
    if [ -f "$CFG" ]; then
        grep -q "com.aegis.fdx.Launcher" "$CFG"
        check "launcher main class configured" $?
        grep -q -- "-Xmx" "$CFG"; check "heap size configured (N-04)" $?
        grep -q -- "UseZGC" "$CFG"; check "ZGC configured" $?
    fi
fi

# ---- headless engine smoke test -------------------------------------------
# Exercises the real engine from the packaged jars: builds a case, ingests a
# file, indexes it and searches. Proves the install works, not merely that the
# files are present.
if [ -d "$APPDIR" ] && [ -f "${RTJAVA:-}" ]; then
    echo
    echo "-- engine smoke test"
    SMOKE="$(mktemp -d)"
    CP="$(ls "$APPDIR"/*.jar | tr '\n' ':')"

    mkdir -p "$SMOKE/evidence"
    printf 'Settlement agreement. Invoice INV-99001. Wire transfer 12,500 EUR.' \
        > "$SMOKE/evidence/memo.txt"

    cat > "$SMOKE/Smoke.java" <<'JAVA'
import com.aegis.fdx.engine.*;
import com.aegis.fdx.index.*;
import com.aegis.fdx.store.*;
import java.nio.file.*;
public class Smoke {
    public static void main(String[] a) throws Exception {
        Path work = Path.of(a[0]);
        CaseFolder cf = CaseFolder.createOrOpen(work.resolve("case"), "SMOKE");
        CaseSettings st = new CaseSettings();
        st.ocrEnabled(false);
        try (CaseDatabase db = new CaseDatabase(cf.database());
             LuceneIndex ix = new LuceneIndex(cf.index(), 32)) {
            var r = new IngestPipeline(cf, db, ix, st, e -> { })
                        .ingest(work.resolve("evidence"), "Verifier");
            ix.commit();
            int hits = ix.search(
                LuceneQueryBuilder.build("INV-99001", ix.analyzer()), 10, null).size();
            int phrase = ix.search(
                LuceneQueryBuilder.build("\"wire transfer\"", ix.analyzer()), 10, null).size();
            System.out.println("SMOKE processed=" + r.processed()
                + " indexed=" + ix.count() + " termHits=" + hits + " phraseHits=" + phrase);
            if (r.processed() < 1 || ix.count() < 1 || hits < 1 || phrase < 1) {
                System.out.println("SMOKE FAILED");
                System.exit(1);
            }
            System.out.println("SMOKE OK");
        }
    }
}
JAVA

    JAVAC="$(dirname "$RTJAVA")/javac"
    if [ -x "$JAVAC" ]; then
        "$JAVAC" -nowarn -cp "$CP" -d "$SMOKE" "$SMOKE/Smoke.java" 2>/dev/null
    else
        # A jlink runtime has no compiler; use the build JDK for the harness only.
        "${AEGIS_JDK:-$HOME/.cache/tools/jdk21/bin}/javac" \
            -nowarn -cp "$CP" -d "$SMOKE" "$SMOKE/Smoke.java" 2>/dev/null
    fi
    check "smoke harness compiles against bundled jars" $?

    OUT="$("$RTJAVA" -cp "$SMOKE:$CP" Smoke "$SMOKE" 2>&1 | grep -E '^SMOKE')"
    echo "     $OUT"
    echo "$OUT" | grep -q "SMOKE OK"
    check "packaged engine ingests, indexes and searches real evidence" $?

    rm -rf "$SMOKE"
fi

# ---- OCR (optional) --------------------------------------------------------
echo
if command -v tesseract >/dev/null 2>&1; then
    echo "  INFO  OCR available: $(tesseract --version 2>&1 | head -1)"
elif [ -x "$HOME/.cache/tools/ocr/root/usr/bin/tesseract" ]; then
    echo "  INFO  OCR available (bundled dev copy)"
else
    echo "  INFO  OCR not installed — OCR is optional and degrades cleanly"
fi

echo
echo "=== $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ] || exit 1
