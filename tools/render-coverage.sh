#!/usr/bin/env bash
# Renders docs/COVERAGE_MATRIX.md from docs/coverage.tsv.
#
# The data file is the source of truth and is enforced by CoverageMatrixTest; this
# script turns it into something a person can read. Edit coverage.tsv, run this,
# commit both.
set -euo pipefail
cd "$(dirname "$0")/.."

TSV="docs/coverage.tsv"
OUT="docs/COVERAGE_MATRIX.md"
[ -f "$TSV" ] || { echo "missing $TSV" >&2; exit 1; }

count() { awk -F'\t' -v c="$1" '!/^#/ && $1 != "id" && $4 == c {n++} END {print n+0}' "$TSV"; }
total=$(awk -F'\t' '!/^#/ && $1 != "id" && NF >= 8 {n++} END {print n+0}' "$TSV")

{
cat <<HEADER
# Coverage matrix — what exists, what is limited, and what is deliberately absent

Generated from \`docs/coverage.tsv\` by \`tools/render-coverage.sh\`. The data file is
checked by \`CoverageMatrixTest\`, which fails the build if a row is unclassified, if it
names Java or a test that does not exist, if a limitation is left unexplained, or if a
destination in the interface is missing from the inventory. If this document and the
code ever disagree, the build says so.

**Generated:** $(date -u +%Y-%m-%dT%H:%M:%SZ)

## How to read it

| Classification | Meaning |
|---|---|
| **VERIFIED** | Implemented, reachable from the interface, persisted where it should be, and proven by a test that runs in this environment. |
| **LIMITED** | Implemented and reachable, but full validation needs hardware or software that is not present here. The note says what is missing and how to validate it. |
| **ADAPTED** | The reference behaviour is delivered in the form the Java architecture calls for — a dialog instead of a form route, a canvas chart instead of a web chart — not in the reference's form. |
| **UNSUPPORTED** | Deliberately not reproduced. The note says why; "the reference has a control" is not on its own a reason to build one. |
| **ABSENT** | No Java counterpart. The note says why and what adding it would involve. |

"Cannot be validated on this machine" is recorded as **LIMITED**, never as absent, and
never as verified.

## Summary

| Classification | Rows |
|---|---:|
| VERIFIED | $(count VERIFIED) |
| LIMITED | $(count LIMITED) |
| ADAPTED | $(count ADAPTED) |
| UNSUPPORTED | $(count UNSUPPORTED) |
| ABSENT | $(count ABSENT) |
| **Total** | **$total** |

HEADER

awk -F'\t' '
  /^#/ || $1 == "id" || NF < 8 { next }
  {
    area = $2
    if (area != last) {
      printf "\n## %s\n\n", area
      print "| # | Reference / capability | Classification | Java | Operation | Test | Notes |"
      print "|---|---|---|---|---|---|---|"
      last = area
    }
    printf "| %s | %s | **%s** | `%s` | `%s` | `%s` | %s |\n", $1, $3, $4, $5, $6, $7, $8
  }
' "$TSV"

cat <<'FOOTER'

## The three items that needed a decision

**Batch scheduling (R01).** The reference's Schedule and Off-Hours controls do not
schedule anything: the page posts to the immediate-processing endpoint and the chosen
time is never stored or acted upon. Reproducing the controls would have reproduced the
appearance of a feature. The destination offers explicit start, cancel and re-run
instead, all of which do what they say.

**Host CPU, memory and disk meters (R03).** The reference's meters are static markup.
They are feasible in Java, so they were built: `HostMetrics` reads the operating system
bean and the filesystem. Where a counter genuinely cannot be read — some containers do
not expose system CPU — the reading is reported as unavailable in words, never as a
plausible-looking zero.

**Real local model generation (A06).** The client, the tool loop, the grounding rules
and the audit trail are exercised against a scripted runtime that speaks the real
protocol, so the architecture is proven. Generating text with an actual 7B model needs
hardware this environment does not have; that is a hardware limit, not a missing
feature, and it is recorded as LIMITED with the procedure for validating it.
FOOTER
} > "$OUT"

echo "wrote $OUT ($total rows)"
