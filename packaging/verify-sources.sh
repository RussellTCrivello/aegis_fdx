#!/usr/bin/env bash
# Verifies that a checkout is COMPLETE and CONSISTENT before building.
#
# Why this exists: a Windows validation run failed with
#   error: cannot find symbol: method compile(String,Analyzer)
# The code was correct — the host had a partial copy, holding the new test file
# but not the main sources it depends on. A javac "cannot find symbol" error is a
# very misleading way to discover that, so this script says it plainly instead.
#
# Usage: bash packaging/verify-sources.sh
set -uo pipefail
cd "$(dirname "$0")/.."

MANIFEST="packaging/SOURCE-MANIFEST.txt"
missing=0; changed=0; extra=0; ok=0

if [ ! -f "$MANIFEST" ]; then
    echo "ERROR: $MANIFEST not found. Regenerate with: bash packaging/make-manifest.sh" >&2
    exit 2
fi

echo "-- Verifying source tree against $MANIFEST"

# 1. Every manifested file must exist and match.
while read -r want path; do
    case "$want" in \#*|"") continue ;; esac
    if [ ! -f "$path" ]; then
        echo "  MISSING   $path"
        missing=$((missing+1))
        continue
    fi
    got=$(sha256sum "$path" | cut -d' ' -f1)
    if [ "$got" != "$want" ]; then
        echo "  CHANGED   $path"
        changed=$((changed+1))
    else
        ok=$((ok+1))
    fi
done < "$MANIFEST"

# 2. Files on disk that the manifest does not know about.
tmp_manifest_paths=$(mktemp)
grep -v '^#' "$MANIFEST" | awk 'NF{print $2}' | LC_ALL=C sort > "$tmp_manifest_paths"
tmp_disk_paths=$(mktemp)
find app/src -name '*.java' | LC_ALL=C sort > "$tmp_disk_paths"
while read -r path; do
    [ -n "$path" ] || continue
    if ! grep -Fxq "$path" "$tmp_manifest_paths"; then
        echo "  UNTRACKED $path"
        extra=$((extra+1))
    fi
done < "$tmp_disk_paths"
rm -f "$tmp_manifest_paths" "$tmp_disk_paths"

echo
echo "  $ok matched · $changed changed · $missing missing · $extra untracked"

if [ "$missing" -gt 0 ]; then
    echo
    echo "RESULT: INCOMPLETE CHECKOUT." >&2
    echo "Files the build needs are absent. This is the usual cause of a" >&2
    echo "'cannot find symbol' error on a method that plainly exists in the repo." >&2
    echo "Re-sync the whole tree — do not copy individual files." >&2
    exit 1
fi

if [ "$changed" -gt 0 ] || [ "$extra" -gt 0 ]; then
    echo
    echo "RESULT: TREE DIFFERS FROM MANIFEST."
    echo
    echo "STOP - do NOT regenerate the manifest yet."
    echo
    echo "On a machine you only COPY files to, CHANGED means those files are stale."
    echo "Regenerating the manifest would record the stale content as correct and"
    echo "destroy the only evidence of what is out of date."
    echo
    echo "Re-copy the files listed above, then re-run this script."
    echo "Only run make-manifest.sh if you edited them ON THIS MACHINE."
    exit 1
fi

echo "RESULT: source tree complete and consistent."
