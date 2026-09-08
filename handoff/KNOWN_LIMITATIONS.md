# Known Limitations — AEGIS-FDX v1.0.0

Accepted, documented behaviours. **These are not defects and should not be raised as
findings during Windows validation.** Anything not on this list that deviates from
`EXPECTED_RESULTS.md` *is* a finding.

---

## 0. Environment-bound validation

These are limits of the machine a build runs on, not of the product. Each one is
reported as a skip with its reason, never as a pass.

| Limit | Effect | How to clear it |
|---|---|---|
| No graphics device | One interface check (`UiParityTest#iconSet`) builds a live scene graph and cannot run; the runner reports it as "not runnable on this machine" | Run the battery on a desktop with JDK 21 and the JavaFX SDK |
| Tesseract absent | OCR assertions report as skipped; the "OCR not installed" path is itself tested | Install Tesseract, or set `aegis.tesseract` |
| No `jlink` / `jpackage` | `packaging/build-installer.sh` exits 3 with the missing tools named; the gate records a skip | Run packaging on a full JDK 21+ |
| Not a Windows host | The MSI is not built; 18 portable Windows-compatibility checks still run | Run `run-tests.ps1` and the installer on Windows |
| Development-class hardware | Performance figures are indicators, not certified | Re-run on 8-core / 16 GB / NVMe |
| Not enough memory for a 7B model | Model *answer quality* is unvalidated; the protocol, tool loop, grounding and audit trail are verified against a scripted runtime | Install a local runtime, pull a model, launch with `-Daegis.ai.enabled=true` |

---

## 1. Format support

### RAR archives are not extracted
`archive.rar` is marked **UNSUPPORTED**. No RAR extractor exists under an
Apache-2.0/MIT/BSD/EPL licence, and the licence policy is absolute (junrar was removed
for this reason).

**Behaviour:** the archive is still hashed, catalogued and indexed as a single element,
with remediation metadata recorded. It is never silently dropped.
**Workaround:** extract externally and ingest the contents as a separate custodian batch.
**Future:** a RAR provider can be added through the `Analyzer` SPI without core changes.

### Audio and video are catalogued, not transcribed
`.mp3`, `.wav`, `.mp4` are **UNSUPPORTED** for text extraction — hashed, catalogued,
metadata recorded, no content search. Speech-to-text is out of scope for v1.0.0.

### Unrecognised types
Files with no matching analyzer (`unknown.xyz`) are **UNSUPPORTED**: hashed and
catalogued so nothing is lost from the evidence count, but not text-searchable.

## 2. Export

### PDF export renders non-Latin characters as `?`
PDF export uses Standard-14 fonts, which are WinAnsi-encoded. Characters above U+00FF
(Arabic, Japanese, Russian, CJK) render as `?` in **PDF exports only**.

**Unaffected:** native export (original bytes, byte-identical) and text export (UTF-8).
Search and review are unaffected — indexing is fully Unicode.
**Guidance:** for non-Latin evidence, export native or text.

### Windows reserved names are prefixed
An element named `CON`, `NUL`, `COM1` etc. exports as `_CON`, `_NUL`, `_COM1`. Windows
cannot create these names at all. Trailing dots and spaces are stripped for the same
reason. The original name is preserved verbatim in the `FileName` column of the load
file; only the on-disk filename is adjusted.

### Long paths
Deep hierarchy layouts combined with long custodian and container names can approach the
260-character MAX_PATH limit.

**Mitigation:** filenames are bounded to leave headroom.
**If exceeded:** enable long paths —
`HKLM\SYSTEM\CurrentControlSet\Control\FileSystem\LongPathsEnabled = 1` (reboot required)
— or use a short destination root such as `D:\EXP\`.

## 3. Processing defaults

### Deduplication is Off by default
A default run reports **0 duplicates** even when byte-identical files are present. This
is the specified default (scope: Off / Per Custodian / Global). Duplicates are **flagged,
never deleted**, at any scope.

This is the single most likely source of a false bug report — see
`EXPECTED_RESULTS.md` §1.

### OCR requires Tesseract, installed separately
OCR is off by default and requires Tesseract on `PATH` (Apache-2.0, external — not
bundled, to keep the dependency report clean). The application drives the Tesseract
**executable** rather than linking it in-process, deliberately: a native crash cannot
take down the JVM (N-05), and calls can be timed out.

**Without Tesseract:** image-only PDFs and scanned images index with no text; the OCR
suite reports honestly that the engine is absent rather than silently passing.

### Extraction depth
Default maximum nesting depth is 20, configurable per case. Beyond it, extraction stops
and the element is recorded with its container path intact.

## 4. Platform

### Windows is the primary target
Windows 10/11 x64 is the certified platform (N-01). macOS and Linux builds are produced
by the same `jpackage` script and are functional, but are not part of v1.0.0 certification.

### Installers must be built on their target OS
`jpackage` does not cross-compile. The MSI requires a Windows host with WiX 3.14; the DMG
requires macOS. This is a toolchain constraint, not a project one.

### GUI automation is not part of the test suite
The FX drop gesture — Explorer handoff, hover feedback, cursor state — cannot be
exercised headlessly and is validated manually (`WINDOWS_VALIDATION.md` §3.1). The
ingestion path behind it **is** covered automatically by `DragDropIngestTest` (10 checks:
file, folder, container, multi-select, custodian attribution, ID uniqueness).

## 5. Scale

### The 5M-item / 1TB target is a design target
N-02 scale is architecturally supported (durable queue, streaming, per-case Lucene index)
but has not been measured at full scale in development. Certify on reference hardware per
`BENCHMARK_INSTRUCTIONS.md`.

### Antivirus materially affects throughput
Windows Defender real-time scanning inspects every file the ingester touches. Exclude
evidence and case folders on production workstations, and record whether exclusions were
applied when reporting benchmark figures.

## 6. Advanced features

ONNX classification, live/AD monitoring ingest, Python/Lua scripting, GPU offload, raw-disk
carving, mobile backups and EDB parsing are in scope for the project and present
architecturally through the analyzer SPI, but are **not part of the v1.0.0 Windows
certification scope** defined in `WINDOWS_VALIDATION.md`. Validate the core forensic
workflow first.
