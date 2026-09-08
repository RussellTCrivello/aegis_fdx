# Windows Validation Handoff Package — AEGIS-FDX v1.0.0

Everything required to execute native Windows validation and final release
certification. Work through it in the order below.

**Development-side status:** 5 suites · **207 assertions** · 0 failures ·
release gate **32 passed / 0 failed / 2 deferred**. The two deferrals are the
Windows MSI build and performance certification — both are what this package exists
to resolve.

---

## Contents

| # | Item | Location |
|---|---|---|
| 1 | Release installer candidate | **built on Windows** — see below |
| 2 | Windows validation checklist | [`../docs/WINDOWS_VALIDATION.md`](../docs/WINDOWS_VALIDATION.md) |
| 3 | Test dataset | `reference-dataset.tar.gz` (44 files) + generator |
| 4 | Expected results | [`EXPECTED_RESULTS.md`](EXPECTED_RESULTS.md) |
| 5 | Acceptance test instructions | [`ACCEPTANCE_INSTRUCTIONS.md`](ACCEPTANCE_INSTRUCTIONS.md) |
| 6 | Performance benchmark instructions | [`BENCHMARK_INSTRUCTIONS.md`](BENCHMARK_INSTRUCTIONS.md) |
| 7 | Known limitations | [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) |

---

## 1. Installer candidate — must be built on Windows

The MSI **cannot be produced in the Linux development environment**: `jpackage` does not
cross-compile and WiX requires a Windows host. The build is a single command on a
correctly configured Windows machine.

```bat
git clone <repo> aegis-fdx
cd aegis-fdx
set AEGIS_JDK=C:\Program Files\Eclipse Adoptium\jdk-21\bin
set AEGIS_FX=C:\javafx-sdk-21.0.4\lib

gradlew :app:jpackage
REM or, if you have Git Bash / WSL available:
REM   bash packaging/build-installer.sh --type msi
REM   bash packaging/build-installer.sh --type msi --per-user
```

**Requirements:** Windows 10/11 x64 · WiX Toolset 3.14 on `PATH` · JDK 21 (Temurin) ·
JavaFX 21.0.4 SDK.

Two builds are needed because the checklist tests both installation modes. The default
MSI is **per-machine** and requires administrator rights; `--per-user` installs under
`%LOCALAPPDATA%` and satisfies checklist §2.5 (non-admin install on a locked-down
workstation).

Verify each built image before testing it:

```bat
powershell -ExecutionPolicy Bypass -File packaging\verify-sources.ps1
REM installer image check still needs bash (Git Bash or WSL):
REM   bash packaging/verify-install.sh "C:\Program Files\AEGIS-FDX"
```

Expected: **20 passed, 0 failed**, including a live ingest/index/search smoke test on the
bundled runtime.

> Two packaging changes were made while preparing this handoff, both verified by
> re-running the builder (20/0):
> - `--per-user` was added. Without it there was no way to pass the non-admin install
>   check — jpackage defaults to per-machine.
> - A missing `packaging/LICENSE.txt` now **fails the build** instead of being silently
>   skipped, which would have shipped an MSI with no licence page. The file is included.

## 2. Validation checklist

[`docs/WINDOWS_VALIDATION.md`](../docs/WINDOWS_VALIDATION.md) — §1 installer, §2 clean-VM
install, §3 functional workflow, §4 AT-01…AT-10, §5 performance, §6 sign-off. §0 records
what was already proven automatically so you do not re-test it by hand.

## 3. Test dataset

`reference-dataset.tar.gz` contains the 44-file reference corpus. It is also **generated
deterministically**, which is the preferred route:

```bat
powershell -ExecutionPolicy Bypass -File run-tests.ps1
```

The corpus deliberately includes malformed, encrypted, unsupported, deeply nested,
duplicate and non-Latin files. Failures in it are **intended** — see
`EXPECTED_RESULTS.md` §2.

## 4. Expected results

`EXPECTED_RESULTS.md` was produced by an **actual pipeline run**, not hand-authored: 58
elements from 44 files, per-element status and SHA-256, plus search and export baselines.
Windows output must match exactly.

> **Most likely false bug report:** deduplication ships **Off**, so a default run reports
> `duplicates=0` despite six byte-identical files. With scope **Global** it reports 7, and
> the element count stays 58 — nothing is ever deleted. Both are correct.

## 5–7. Instructions and limitations

`ACCEPTANCE_INSTRUCTIONS.md` gives each acceptance test an automated part and a GUI
confirmation part. `BENCHMARK_INSTRUCTIONS.md` covers reference hardware, Defender
exclusions, and the REFERENCE-vs-DEVELOPMENT classification guard that prevents an
uncertified figure being mistaken for a certified one. `KNOWN_LIMITATIONS.md` lists
accepted behaviours that must **not** be raised as findings.

---

## Suggested order

1. Build both MSIs (§1), run `verify-install.sh` on each (needs Git Bash or WSL).
2. Clean-VM install tests on Windows 10 and 11 — checklist §2.
3. `powershell -ExecutionPolicy Bypass -File run-tests.ps1 > windows-test-run.log` — expect 266 assertions, 0 failures (256 without Tesseract).
4. Compare against `EXPECTED_RESULTS.md` §1 and §4.
5. GUI workflow — checklist §3, especially §3.1.2–3.1.4c (drag-and-drop gestures, the
   one area with no automated coverage).
6. Network storage — checklist §3, UNC and mapped drives.
7. AT-01…AT-10 per `ACCEPTANCE_INSTRUCTIONS.md`.
8. Performance certification on reference hardware per `BENCHMARK_INSTRUCTIONS.md`.
9. Complete the §6 sign-off table.

## Release criteria

1. Windows installer succeeds (both modes).
2. Application launches from a clean installation.
3. Evidence workflows complete successfully.
4. AT-01…AT-10 pass.
5. Performance certified on `REFERENCE`-class hardware.
6. Documentation package complete.

## Reporting

Every result needs: machine specification, Windows version and build, dataset
description, execution duration, pass/fail. Capture failures with the console log, a
screenshot of the UI state, and the relevant case audit log entries.

Use generated suite output as the single source of truth for assertion counts — never
hand-aggregated totals.
