#!/usr/bin/env bash
# Milestone 2 dependency resolver (no Gradle daemon in this sandbox).
# Pulls the exact production jar set from Maven Central into lib/.
set -uo pipefail
LIB=${1:-/home/user/aegis-fdx/lib}
mkdir -p "$LIB"
M2=https://repo1.maven.org/maven2

# group:artifact:version
DEPS=(
  # --- Lucene 9.x : indexing & search (Apache-2.0) ---
  org.apache.lucene:lucene-core:9.11.1
  org.apache.lucene:lucene-analysis-common:9.11.1
  org.apache.lucene:lucene-queryparser:9.11.1
  org.apache.lucene:lucene-highlighter:9.11.1
  org.apache.lucene:lucene-memory:9.11.1
  org.apache.lucene:lucene-queries:9.11.1
  org.apache.lucene:lucene-sandbox:9.11.1

  # --- Tika core : MIME detection only (Apache-2.0) ---
  org.apache.tika:tika-core:3.0.0

  # --- PDF (Apache-2.0) ---
  org.apache.pdfbox:pdfbox:3.0.3
  org.apache.pdfbox:pdfbox-io:3.0.3
  org.apache.pdfbox:fontbox:3.0.3

  # --- Office (Apache-2.0) ---
  org.apache.poi:poi:5.3.0
  org.apache.poi:poi-ooxml:5.3.0
  org.apache.poi:poi-ooxml-lite:5.3.0
  org.apache.poi:poi-scratchpad:5.3.0
  org.apache.xmlbeans:xmlbeans:5.2.1
  org.apache.commons:commons-collections4:4.4
  org.apache.commons:commons-math3:3.6.1
  com.zaxxer:SparseBitSet:1.3
  com.github.virtuald:curvesapi:1.08

  # --- Mail (Apache-2.0 / EPL) ---
  org.apache.james:apache-mime4j-core:0.8.11
  org.apache.james:apache-mime4j-dom:0.8.11
  com.pff:java-libpst:0.9.3

  # --- Archives (Apache-2.0 / BSD-style) ---
  org.apache.commons:commons-compress:1.27.1
  org.tukaani:xz:1.10
  # junrar deliberately EXCLUDED from v1: the UnRar licence restriction falls
  # outside the mandated Apache/MIT/BSD/EPL set. RAR is marked Unsupported.
  # See docs/DEPENDENCY_REPORT.md.

  # --- Storage / logging / util ---
  org.xerial:sqlite-jdbc:3.46.1.0
  org.slf4j:slf4j-api:2.0.16
  ch.qos.logback:logback-classic:1.5.8
  ch.qos.logback:logback-core:1.5.8
  commons-io:commons-io:2.16.1
  commons-codec:commons-codec:1.17.1
  org.apache.commons:commons-lang3:3.17.0
  commons-logging:commons-logging:1.3.4
  org.apache.logging.log4j:log4j-api:2.24.1
  org.apache.logging.log4j:log4j-core:2.24.1
)

fail=0
for d in "${DEPS[@]}"; do
  IFS=: read -r g a v <<<"$d"
  gp=${g//.//}
  out="$LIB/$a-$v.jar"
  [ -s "$out" ] && continue
  url="$M2/$gp/$a/$v/$a-$v.jar"
  if curl -sfL -o "$out" "$url"; then
    printf 'ok   %s\n' "$a-$v.jar"
  else
    printf 'FAIL %s  <- %s\n' "$a-$v.jar" "$url"; rm -f "$out"; fail=1
  fi
done
echo "---"
ls "$LIB" | wc -l | xargs printf '%s jars\n'
du -sh "$LIB"
exit $fail
