#!/usr/bin/env bash
# Build & launch the AEGIS-FDX prototype without Gradle.
# Requires: JDK 21 (JAVA_HOME) and a JavaFX 21 SDK (JAVAFX_HOME).
set -euo pipefail
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
FX=${JAVAFX_HOME:?set JAVAFX_HOME to your javafx-sdk-21 directory}/lib
OUT=build/proto
rm -rf "$OUT"; mkdir -p "$OUT"
$JAVAC --module-path "$FX" --add-modules javafx.controls -d "$OUT" $(find app/src -name '*.java')
cp -r app/src/main/resources/* "$OUT"/
echo "--- unit tests ---"
$JAVA -cp "$OUT" com.aegis.fdx.QueryParserTest
echo "--- launching ---"
$JAVA --module-path "$FX" --add-modules javafx.controls -cp "$OUT" com.aegis.fdx.ui.AegisApp
