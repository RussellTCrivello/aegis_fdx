#!/usr/bin/env bash
# Regenerates the source manifest used to detect partial/stale copies.
set -euo pipefail
cd "$(dirname "$0")/.."
{
  echo "# AEGIS-FDX source manifest - regenerate with: packaging/make-manifest.sh (or .ps1)"
  echo "# Verifies a checkout is complete and self-consistent before building."
  echo "# Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  # Cover build files and scripts too, not just Java sources. A stale
  # app/build.gradle.kts produced a jpackage failure that the source-only
  # manifest could not see.
  {
    find app/src -name '*.java'
    ls -1 build.gradle.kts settings.gradle.kts app/build.gradle.kts 2>/dev/null
    ls -1 run-tests.sh run-tests.ps1 final-acceptance.sh 2>/dev/null
    ls -1 packaging/*.sh packaging/*.ps1 2>/dev/null | grep -v 'SOURCE-MANIFEST'
  } | LC_ALL=C sort -u | while read -r f; do
    [ -f "$f" ] || continue
    printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"
  done
} > packaging/SOURCE-MANIFEST.txt
echo "wrote packaging/SOURCE-MANIFEST.txt ($(grep -c '^[0-9a-f]' packaging/SOURCE-MANIFEST.txt) files)"
