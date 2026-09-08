# Milestone 3 — Production Capabilities

OCR, export, reporting, and forensic integrity controls are implemented and
verified against real evidence.

## Verification — one command

```bash
./run-tests.sh
```

| Suite | Assertions | Result |
|---|---:|---|
| Query parser (M1) | 33 | 0 failed |
| Pipeline acceptance AT-01…AT-10 (M2) | 56 | 0 failed |
| **M3 acceptance (OCR / export / reports / integrity)** | **90** | **0 failed** |
| Benchmark N-02 / F-18 / N-03 | — | PASS |
| **Total** | **179** | **0 failed** |

---

## A. OCR — complete

Real Tesseract **5.5.0** (`eng` + `deu`), driven out-of-process.

| Requirement | Status |
|---|---|
| Tesseract integration | Done — `ocr/OcrEngine.java` |
| Configurable languages | Done — per case, validated against installed langs |
| Enable/disable per case | Done — `CaseSettings.ocrEnabled` |
| Image text recognition | Done |
| Scanned PDF processing | Done — pages rendered at 300 DPI, capped at 50 |
| Asynchronous, non-blocking | Done — sweep runs after intake on its own pool |

**Why out-of-process, not Tess4J/JNA:** OCR on a malformed image can segfault.
In-process that kills the JVM and destroys the entire run, violating N-05.
Out-of-process it is one exit code against one element, and timeouts are actually
enforceable. Cost is ~10–30 ms spawn against a 0.5–3 s OCR pass.

**Proof it genuinely works.** The dataset now contains two fixtures whose text
exists *only as pixels* — no text layer anywhere:

| Query | OCR off | OCR on |
|---|---:|---:|
| `"OCR-CANARY-IMAGE"` | **0** | **1** |
| `"OCR-CANARY-SCANNED"` | **0** | **1** |
| `/INV-\d{5}/` | 2 | **4** |

Recognition was character-perfect, including `INV-88213`.

**Waste avoided:** digital PDFs are *not* OCR'd. A PDF is only queued when its
embedded text layer is under 100 characters — OCR'ing a digital PDF costs seconds
and yields worse text than PDFBox already extracted. Asserted in the suite.

---

## B. Export and reporting — complete

Formats **Native / EML / PDF / Text**; layouts **Flat / By type / By custodian /
Hierarchy**; scopes **Selected / All / By tag**.

Every export produces:
- `loadfile.csv` — UTF-8 **with BOM** (Excel opens it correctly), RFC-4180 quoted,
  **31 fixed columns** including MD5, SHA-256, tags, notes, and the `ParentID` /
  `AttachedFrom` / `DuplicateOf` relationship triple.
- `MANIFEST-SHA256.txt` — hash of every written file.
- Extracted-text sidecars.

**Hashes are re-verified after writing.** A native copy whose SHA-256 no longer
matches the ingest baseline is reported as a failure, not silently produced — a
corrupted production is worse than none.

**F-24 enforced:** Hidden elements are excluded from export *and* absent from the
load file. Asserted directly.

Reports (`processing`, `duplicates`, `search`) render to **Markdown + CSV** into the
case's own `/exports` folder. The processing report's exceptions section lists every
errored, locked, and unsupported element with its reason — from the live run:

```
| E-000007 | corrupt.docx | Error  | IOException: Truncated ZIP file |
| ...      | secret.txt   | Locked | no supplied password matched (3 tried) |
```

---

## C. Review functionality — complete

Preview (PDF/image/text/email), metadata panel, tags with shortcuts, notes,
email threading, duplicates, and audit log are wired to live data. Tags and notes
round-trip through the index and are searchable; the audit log is append-only and
records exports and searches.

---

## D. Evidence integrity — complete

`IntegrityVerifier` re-hashes every original against its ingest baseline, confirms
the source tree was never written to (F-06), and confirms the extracted text
backing the index survives (F-14 rebuild). It is **read-only and never repairs** —
silently "fixing" evidence would destroy the provenance it exists to prove.

The suite includes a **negative control**: it tampers with a source file and asserts
the tamper *is* detected, then restores it. Without that, "all verified" proves
nothing.

Crash recovery is tested by cancelling mid-ingest, reopening the case, and re-running.
The recovered case is asserted **byte-for-byte equivalent to an uninterrupted run**
(57 elements vs 57, zero duplicates).

---

## Five real bugs found and fixed

All surfaced by testing against real evidence; none were cosmetic.

1. **OCR reported itself available when the binary was broken.** The version probe
   matched the substring "tesseract" — which also appears in
   `tesseract: error while loading shared libraries`. Every document would have
   silently returned empty text while the UI claimed OCR was on. Now requires exit
   code 0 *and* a version-shaped first line.

2. **Forensic fields were never persisted.** `ocrApplied`, `needsOcr`, `duplicate`,
   `duplicateOf`, `attachedFrom` and `geoLocation` existed only in memory and were
   lost on every index round-trip — breaking export relationships and OCR resume.

3. **Attachment counts round-tripped as a boolean.** An email with 12 attachments
   came back reporting 1.

4. **Every renamed export was reported as failed.** The EML/PDF/Text writers adjust
   the filename, but the caller hashed the *pre-rename* path. Writers now return the
   path actually written.

5. **Crash resume created duplicate index entries.** Resume keyed on a generated
   sequence id, which is re-issued on restart, so every file looked new. Now keyed
   on the **source path**, which is stable across restarts.

A sixth was found in the *test suite* rather than the product: the F-06 read-only
check compared against `modified()`, which analyzers legitimately overwrite with the
document's own internal date (a .pptx authored in 2011 reports 2011). A dedicated
`fsModified` intake baseline was added rather than weakening the assertion.

---

## E/F. Remaining for final acceptance

Honest status — these are **not** done:

| Item | Status |
|---|---|
| Performance certification on reference hardware | **Blocked on hardware.** `./run-tests.sh 2000` runs unchanged there. |
| ≥5M items / ≥1TB validation | **Not possible here** (2 cores, ~1 GB heap). |
| Windows installers (jpackage) | Config written, **not built** — needs a Windows host. |
| Installation validation | Depends on the above. |
| User / admin manuals | Outstanding. |
| Advanced features (ONNX, GPU, carving, mobile, live monitoring, scripting SPI) | In scope, **not started**. |

Benchmark on this sandbox (2 cores — treat as a lower bound, small documents make
throughput optimistic):

| Metric | Result | Target | |
|---|---|---|---|
| Ingest throughput | ~650k items/h | ≥ 8,000 | PASS |
| Search p95 | 6.0 ms | < 2,000 ms | PASS |
| UI event handling max | 6.2 ms | < 500 ms | PASS |
| Peak heap | 46 MB | 4 GB budget | PASS |
