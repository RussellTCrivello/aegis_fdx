# Acceptance Test Instructions — AT-01 … AT-10 (Windows)

**Audience:** the validator executing final release certification on Windows 10/11 x64.
**Prerequisite:** AEGIS-FDX installed from the release MSI (`WINDOWS_VALIDATION.md` §1–§2 complete).

Each test has an **automated** part and a **GUI confirmation** part. The automated part
proves the engine; the GUI part proves the operator-facing behaviour, which is the half
that could not be validated on Linux. Both must pass.

---

## 0. Setup

```bat
cd C:\aegis-fdx
set AEGIS_JDK=C:\Program Files\Eclipse Adoptium\jdk-21\bin
powershell -ExecutionPolicy Bypass -File run-tests.ps1
```

This generates the 44-file reference dataset, runs all five suites, and prints a footer
per suite. **Use the generated suite output as the source of truth** — do not hand-total
assertions.

Expected on a healthy build: **6 suites, 266 assertions, 0 failures** (256 if Tesseract is absent).

> **No `bash` on the host?** Every step below uses `run-tests.ps1`, which needs only a
> JDK 21 and a JavaFX 21 SDK. The `.sh` scripts are for Git Bash / WSL / Linux.

Record the console output to a file for the handoff record:

```bat
powershell -ExecutionPolicy Bypass -File run-tests.ps1 *> windows-test-run.log
```

Then compare the ingest outcome against `EXPECTED_RESULTS.md` §1 and §4. Element counts,
statuses and SHA-256 values must match **exactly**.

---

## AT-01 — Mixed-format ingest

**Automated:** covered in the pipeline suite.
**GUI:** create a case, add the dataset folder, let it finish.

| Check | Expected |
|---|---|
| Elements produced | 58 from 44 files (containers expand) |
| Application stability | never crashes; no failure aborts the run (N-05) |
| Corrupted files | 3 × ERROR, each with a readable reason |
| Majority indexed | 49 INDEXED |
| Status breakdown | matches `EXPECTED_RESULTS.md` §1 |

## AT-02 — Nested containers

**GUI:** search `level4` and open the hit.

| Check | Expected |
|---|---|
| Chain | `nested_evidence.zip` → `level2.zip` → `level3.eml` → `level4.pdf` |
| Depth | reaches 3 |
| Parent chain | each element shows its parent and container path |
| Searchability | the depth-3 PDF's text is searchable |

Confirm the configured maximum depth of 20 is honoured in case settings.

## AT-03 — Encrypted archive

| Check | Expected |
|---|---|
| `encrypted.zip` | container itself INDEXED |
| `secret.txt` | **LOCKED** |
| Password list | attempted before locking |
| Run continuation | ingest completes normally |

## AT-04 — Corrupted files

| Check | Expected |
|---|---|
| `corrupt.docx` | ERROR — `Truncated ZIP file` |
| `corrupt.pdf` | ERROR — `Missing root object specification in trailer` |
| `truncated.zip` | ERROR — `Archive is not a ZIP archive` |
| Run completes | yes — errors are contained, never fatal |
| Error text | visible to the operator in the UI, not just the log |

## AT-05 — Duplicate detection

**Important:** dedup ships **Off**. A default run correctly reports **0** duplicates.

1. Run with default settings → `duplicates=0`, 58 elements.
2. Set dedup scope to **Global**, re-ingest into a fresh case → **7** flagged, still **58** elements.

| Check | Expected |
|---|---|
| Grouping | by SHA-256 |
| Deletion | **nothing is ever deleted** (F-05) |
| Duplicates list | populated in the review UI |

## AT-06 — Ingest throughput

Deferred to reference hardware — see `BENCHMARK_INSTRUCTIONS.md`. Target **≥ 8000 items/h** with OCR off.

## AT-07 — Search performance

Target **p95 < 2 s**. Measured by the benchmark; also confirm interactively that the
queries in `EXPECTED_RESULTS.md` §5 return the stated counts and feel immediate.

## AT-08 — Crash recovery

1. Start ingest on a large corpus.
2. Kill the process mid-run (Task Manager → End task).
3. Relaunch, reopen the case, resume.

| Check | Expected |
|---|---|
| Resumed result | equals an uninterrupted run |
| Duplicates | none introduced |
| Element IDs | no collisions |
| Finished states | DONE/ERROR/LOCKED/UNSUPPORTED are not reprocessed |

This is the highest-value Windows test — file handles and locking behave differently
from Linux, so a crash mid-write is a genuinely different scenario here.

## AT-09 — Export

Export all, native format, flat layout.

| Check | Expected |
|---|---|
| Rows | 58 — every element, including failed ones |
| Columns | exactly 31, fixed order |
| Encoding | UTF-8 **with BOM**, CRLF |
| Outputs | `native/`, `text/`, `loadfile.csv`, `MANIFEST-SHA256.txt` |
| Excel | opens with correct encoding and no mangled characters |
| Hidden | tag one item Hidden → re-export yields 57 rows |

Then repeat with the **hierarchy** layout and confirm `WINDOWS_VALIDATION.md` §3.4.3–3.4.6
(reserved device names, trailing dots, MAX_PATH, non-ASCII).

## AT-10 — Hash verification

| Check | Expected |
|---|---|
| Post-write verification | every exported file re-hashed |
| Mismatch handling | reported as `hashMismatched`, **never shipped silently** |
| Tamper test | modify an exported file, re-verify → detected |
| Manifest | `MANIFEST-SHA256.txt` matches every file |
| Source integrity | source evidence unmodified (F-06, compare `fsModified()`) |

---

## Recording results

For each test record: **machine specification, Windows version, dataset description,
execution duration, pass/fail**. Fill in the sign-off table in `WINDOWS_VALIDATION.md` §6.

A test that fails should be captured with the console log, a screenshot of the UI state,
and the relevant portion of the case audit log.
