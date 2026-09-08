# Windows Production Validation — Execution Checklist

**Target:** AEGIS-FDX v1.0.0 · Windows 10/11 x64 (N-01)
**Status of this document:** procedure ready to execute. Sections 1, 2 and 5 **must be run on a Windows host** — they cannot be performed in the Linux development environment (see §0).

This is the checklist that certifies AEGIS-FDX as a Windows-first professional forensic workstation. Work top to bottom and record the result of every step. Any FAIL blocks release.

---

## 0. What could and could not be validated in development

| Area | Where it can run | Status |
|---|---|---|
| Windows filename/path rules | Any host — rules encoded as assertions | **Validated** — `WindowsCompatibilityTest`, 18/18 |
| Drag-and-drop wiring | Any host — handler presence gated in CI | **Validated** — handlers present, gate enforces |
| Drag-and-drop intake paths | Any host — engine-level, below the FX layer | **Validated** — `DragDropIngestTest`, 10/10 (file, folder, container, multi-select) |
| Drop gesture itself (hover, cursor, Explorer handoff) | Windows GUI only | **Deferred to §3.1** |
| MSI build (WiX) | Windows only — jpackage does not cross-compile | **Deferred to §1** |
| Clean-VM install | Windows only | **Deferred to §2** |
| Performance certification | 8-core / 16 GB / NVMe reference hardware | **Deferred to §5** |

Two defects that would have broken this validation were found and fixed during preparation:

1. **Export filename sanitiser was Windows-unsafe.** It stripped illegal characters but permitted Windows **reserved device names** (`CON`, `PRN`, `AUX`, `NUL`, `COM0-9`, `LPT0-9`) and **trailing dots/spaces**. On NTFS an attachment named `CON` or `report.` cannot be created — the export would have failed or, worse, silently collided with another element. Fixed in `Exporter.safe()`; reserved names are now prefixed (`_CON`) so they stay readable, trailing dots/spaces are stripped, and `.`/`..` are rejected.
2. **Drag-and-drop import (F-01) did not exist.** No `Dragboard`, `setOnDragOver` or `setOnDragDropped` anywhere in the codebase, despite being a named validation item. Implemented in `AegisApp.installDragAndDrop()`, with a custodian prompt so a drop cannot bypass mandatory attribution.

Both are now permanently gated by `final-acceptance.sh`, so neither can silently regress.

**Follow-up verification of the drag-and-drop intake path.** Because a drop is an
evidence-handling operation, the intake contract was tested below the FX layer
rather than assumed. All four drop modes required by §2 — single file, folder,
container, and Explorer multi-select — were confirmed to flow through the *same*
`LiveCase.startIngest` entry point as the Add-evidence dialog, with custodian
attribution present on every element and no element-ID collisions across the
sequential submissions a multi-select drop generates.

One hypothesis was investigated and **disproved**: a multi-select drop re-seeds the
element-ID sequence per submission, and `maxElementSequence()` scans only the queue
table, so it appeared that container children could cause ID reuse and silently
overwrite evidence. Testing showed container children use *namespaced* IDs
(`E-000001-E1`) rather than the global sequence, so the two schemes cannot collide.
The apparent divergence between `db.count()` and `maxElementSequence()` is by design.
No change was made. This is recorded so the question is not re-opened during
validation.

---

## 1. Build the MSI on Windows

**Host requirements:** Windows 10/11 x64, [WiX Toolset 3.14](https://wixtoolset.org/) on `PATH`, JDK 21 (Temurin), JavaFX 21 SDK.

```bat
git clone <repo> aegis-fdx
cd aegis-fdx
set AEGIS_JDK=C:\Program Files\Eclipse Adoptium\jdk-21\bin
set AEGIS_FX=C:\javafx-sdk-21.0.4\lib
gradlew :app:jpackage
```

| # | Check | Expected | Result |
|---|---|---|---|
| 1.1 | Build completes, exit 0 | no jpackage errors | ☐ |
| 1.2 | `dist\AEGIS-FDX-1.0.0.msi` exists | 100–140 MB | ☐ |
| 1.3 | UpgradeCode is unchanged | `6f3a1c92-4d7b-4f2e-9a15-8c0b7e2d4a63` | ☐ |
| 1.4 | Signed (if a cert is available) | `signtool verify /pa` passes | ☐ |

> **Do not change the UpgradeCode.** Altering it breaks in-place upgrades for every deployed workstation.

---

## 2. Clean-VM install test

Use a **fresh** Windows 10 and a **fresh** Windows 11 VM with **no JDK installed** — this proves the bundled runtime is self-contained.

| # | Check | Expected | Result |
|---|---|---|---|
| 2.1 | Interactive install | completes without prompting for Java | ☐ |
| 2.2 | Silent install `msiexec /i AEGIS-FDX-1.0.0.msi /qn` | exit 0 | ☐ |
| 2.3 | Start-menu and desktop shortcuts | present, correct icon | ☐ |
| 2.4 | Launch from shortcut | UI appears, no console window | ☐ |
| 2.5 | Install under a **non-admin** user | per-user install works | ☐ |
| 2.6 | Install path with a space (`C:\Program Files\AEGIS-FDX`) | launches correctly | ☐ |
| 2.7 | `verify-install.sh "C:\Program Files\AEGIS-FDX"` | 20/20 checks pass | ☐ |
| 2.8 | Upgrade over an existing install | replaces, keeps cases intact | ☐ |
| 2.9 | Uninstall via Apps & Features | removes program files, **leaves case folders** | ☐ |
| 2.10 | Offline behaviour (VM network disabled) | fully functional (N-06) | ☐ |

---

## 3. Functional validation

Run each workflow in the GUI on Windows. Use `app/src/test/resources/dataset/` as the corpus.

### 3.1 Ingest

| # | Check | Expected | Result |
|---|---|---|---|
| 3.1.1 | Add folder via dialog | all items queued | ☐ |
| 3.1.2 | **Drag-and-drop a folder onto the window** | blue border on hover; custodian prompt; ingest starts | ☐ |
| 3.1.3 | **Drag-and-drop multiple files at once** | every dropped path ingested, not just the first | ☐ |
| 3.1.4 | Drag-and-drop cancelled at the custodian prompt | nothing ingested | ☐ |
| 3.1.4a | **Drag-and-drop a single loose file** | ingested as one element | ☐ |
| 3.1.4b | **Drag-and-drop a container (`.zip`) directly** | expands to member elements | ☐ |
| 3.1.4c | Dropped items appear in the same queue as dialog-added ones | one pipeline, identical statuses | ☐ |
| 3.1.5 | Ingest from a **UNC path** (`\\server\share\evidence`) | succeeds | ☐ |
| 3.1.6 | Ingest from a **mapped drive** (`Z:\`) | succeeds | ☐ |
| 3.1.7 | Ingest a read-only / locked source | source never modified (F-06) | ☐ |
| 3.1.8 | Path with spaces and non-ASCII (`C:\Beweis Müller\`) | succeeds, names preserved | ☐ |
| 3.1.9 | Pause / resume / cancel | responsive, state correct (N-04) | ☐ |
| 3.1.10 | Kill the app mid-ingest, relaunch, resume | no duplicates, no lost items | ☐ |
| 3.1.11 | Encrypted archive | marked **Locked**, run continues | ☐ |
| 3.1.12 | Corrupted file | marked **Error** with reason, run continues (N-05) | ☐ |

### 3.2 Search

| # | Check | Expected | Result |
|---|---|---|---|
| 3.2.1 | Keyword, phrase, wildcard, fuzzy, proximity | correct hits | ☐ |
| 3.2.2 | Boolean with parentheses | correct precedence | ☐ |
| 3.2.3 | Field queries (`from:`, `subject:`, `type:`, `tag:`) | correct hits | ☐ |
| 3.2.4 | `date:[2023-01-01 TO 2023-12-31]` | correct range | ☐ |
| 3.2.5 | Regex `/INV-[0-9]{5}/` | spans token boundaries | ☐ |
| 3.2.6 | Search **during** ingest | progressive results (F-13) | ☐ |
| 3.2.7 | Highlighting, sorting, search-within | correct | ☐ |
| 3.2.8 | Saved searches persist across restart | ☐ |
| 3.2.9 | Hidden-tagged items absent from results | ☐ |

### 3.3 Review

| # | Check | Expected | Result |
|---|---|---|---|
| 3.3.1 | Tag via keyboard shortcut | applies instantly | ☐ |
| 3.3.2 | Multi-select tagging | all selected tagged | ☐ |
| 3.3.3 | Notes saved and searchable | ☐ |
| 3.3.4 | Preview: PDF, image, TXT/HTML/CSV, email, Office | renders | ☐ |
| 3.3.5 | Metadata panel always populated | ☐ |
| 3.3.6 | Email threading and duplicates list | correct grouping | ☐ |
| 3.3.7 | Audit log append-only, records every action | ☐ |

### 3.4 Export — the highest-risk area on Windows

| # | Check | Expected | Result |
|---|---|---|---|
| 3.4.1 | Export native / EML / PDF | all produced | ☐ |
| 3.4.2 | All four layouts (flat, by type, by custodian, hierarchy) | correct trees | ☐ |
| 3.4.3 | **Element named `CON`, `NUL`, `PRN`** | exports as `_CON` etc., no failure | ☐ |
| 3.4.4 | **Element with a trailing dot or space** | sanitised, no collision | ☐ |
| 3.4.5 | Deep hierarchy near **MAX_PATH (260)** | either succeeds or reports clearly — never truncates silently | ☐ |
| 3.4.6 | Non-ASCII filenames | preserved on NTFS | ☐ |
| 3.4.7 | `loadfile.csv` opens in Excel with correct encoding | BOM + CRLF respected | ☐ |
| 3.4.8 | 31 columns in the fixed order | matches `Exporter.COLUMNS` | ☐ |
| 3.4.9 | `MANIFEST-SHA256.txt` verifies | every hash matches | ☐ |
| 3.4.10 | Hidden items excluded unless explicitly included | ☐ |
| 3.4.11 | Export to a UNC destination | succeeds | ☐ |

> **Long-path note.** If §3.4.5 fails, enable Win32 long paths on the workstation:
> `HKLM\SYSTEM\CurrentControlSet\Control\FileSystem\LongPathsEnabled = 1` (reboot required).
> Prefer shorter destination roots (`D:\EXP\`) for hierarchy exports regardless.

---

## 4. Acceptance tests AT-01 … AT-10

Run `run-tests.sh` on the Windows host, then confirm each item in the GUI.

| ID | Test | Result |
|---|---|---|
| AT-01 | Mixed-format ingest, all types extracted | ☐ |
| AT-02 | Nested containers to depth 20, parent links intact | ☐ |
| AT-03 | Encrypted archive → Locked; password list attempted | ☐ |
| AT-04 | Corrupted files → Error, run completes | ☐ |
| AT-05 | SHA-256 duplicates grouped, nothing deleted | ☐ |
| AT-06 | Throughput ≥ 8000 items/h, OCR off (**see §5**) | ☐ |
| AT-07 | Search p95 < 2 s | ☐ |
| AT-08 | Crash mid-ingest → resume equals uninterrupted run | ☐ |
| AT-09 | Export produces native + text + load file + manifest | ☐ |
| AT-10 | Post-write hash verification; tampering detected | ☐ |

---

## 5. Performance certification

**Reference hardware (required):** 8 physical cores · 16 GB RAM · NVMe SSD · Windows 11 x64.

```bat
powershell -ExecutionPolicy Bypass -File run-tests.ps1 -Multiplier 2000
```

The benchmark self-classifies the host and writes `benchmark-result.csv`. Certification is valid **only** when it reports `Class = REFERENCE`; a `DEVELOPMENT` result is an indicator, never a certification.

| Metric | Target | Measured | Result |
|---|---|---|---|
| Ingest throughput (OCR off) | ≥ 8000 items/h | | ☐ |
| Search p95 | < 2000 ms | | ☐ |
| UI max latency | < 500 ms | | ☐ |
| Peak heap | < 4096 MB | | ☐ |
| Scale | 5M items / 1 TB | | ☐ |

Also confirm on Windows:

- Heap setting honoured (`-Xmx` in `AEGIS-FDX.cfg`), default 4 GB (N-03).
- Files > 100 MB stream rather than load fully.
- Worker count = cores − 1.
- Windows Defender real-time scanning noticeably affects throughput — record whether the evidence and case folders were excluded, as this materially changes the numbers.

---

## 6. Sign-off

| Item | Value |
|---|---|
| Build / MSI SHA-256 | |
| Windows 10 build tested | |
| Windows 11 build tested | |
| Reference hardware used | |
| Total PASS / FAIL | |
| Validated by / date | |

**Release criterion:** every check in §1–§4 PASS, §5 measured on REFERENCE-class hardware with all targets met, and `final-acceptance.sh` reporting 0 failures on the Windows host.

---

## Known limitations carried into validation

These are accepted and documented; they are not defects to be raised here.

- **RAR archives** are marked `Unsupported` (no licence-compatible extractor). Contents are hashed and indexed as a single item; remediation metadata is recorded.
- **PDF export** uses Standard-14 WinAnsi fonts, so characters above U+00FF are rendered as `?`. Native and text exports preserve the original bytes.
- **Long paths** beyond MAX_PATH require the registry setting above on the workstation.
- OCR requires Tesseract installed separately (Apache-2.0, external).
