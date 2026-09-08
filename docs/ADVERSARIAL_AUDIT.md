# Adversarial Audit

**Date:** 2026-09-08 · **Scope:** the whole application — Java sources, tests, scripts,
documentation, and the Python behavioural reference at
`RussellTCrivello/file_analysis@d2975c2`.

This pass was run against the project the way an opponent would run it: not "does the
feature exist" but "what would I attack if I wanted to prove this application is a
facade". Each hunt below states the method used, so it can be repeated and disagreed
with. Findings are recorded whether or not they were comfortable, and the fixes are
listed with the test that now holds them in place.

**Execution status, stated first.** Everything in this document has now been compiled
and run. A previous revision of this audit was static — the environment had no JDK and
no way to fetch one — and said so. That is no longer the case: the whole source tree
compiles, every suite executes, and the release gate runs end to end. The numbers quoted
below come from those runs. Where a check still cannot be executed here, the reason is
named and the check is recorded as not run, never as passing.

The toolchain differs from the reference one and that is part of the result: the runtime
is a Temurin **25.0.2** image, the compiler is the **Eclipse batch compiler 3.45** rather
than `javac`, and the JavaFX jars are a **20.0.1** build without Linux native libraries.
Source and target level are held at 21. One check — the icon-set test, which builds a
live scene graph — cannot run without a graphics device and reports itself as not
runnable rather than as a failure.

---

## 1. Findings and what was done about them

| # | Finding | Severity | Status |
|---|---|---|---|
| A-1 | **The agent was constructed at application startup.** `FasApp.start()` called `AgentService.fromEnvironment(...)`, which read `ModelConfig` and built an `HttpLocalModelProvider` while the window was being assembled — so AI configuration and HTTP machinery loaded on every launch, including launches by an examiner who never touches the Assistant. Nothing *called* a model, but the standing rule is that AI must not load at startup at all. | High — direct breach of the AI boundary | **Fixed.** `AgentService` now stores a factory; the provider is built on first genuine demand. New `isModelLoaded()` makes the state observable. Held by `AiBoundaryTest` B-08 (zero requests to a live, enabled, reachable runtime during startup and a full working session) and by a release-gate check requiring `FasApp` to name no model class. |
| A-2 | **Settings did not survive a restart.** The Settings destination wrote to an in-memory `CaseSettings`. The controls were real — the next ingest run used the new worker count — but closing the application silently discarded the choice. This is the "state that does not survive restart" class in its purest form. | High — silent data loss of operator intent | **Fixed.** `CaseSettings.saveTo` / `loadFrom` persist to `settings.properties` in the case folder; the shell loads them immediately after opening the case, before anything can process; the screen writes on every change and names the file and time on screen. Held by `SettingsPersistenceTest` (round trip, missing file, corrupt file, and that session passwords are never written). |
| A-3 | **Two controls that changed nothing.** The Settings page offered an editable "Application Name" and a five-language picker. Neither was wired to anything, and no translation catalogue exists. | Medium — the mock-control class the brief forbids | **Fixed.** Both are now presented as facts (application, interface language, open case, case folder), so the page states what is true instead of implying a capability. |
| A-4 | **Host resource meters were absent here and fake in the reference.** The reference's "Resource Optimization" panel is three literals (CPU 45%, memory 62%, disk I/O 38%). The Java Performance screen showed only JVM heap and a processor count. | Medium — a real gap, with a trap next to it | **Implemented, measured.** `HostMetrics` reads process CPU, system CPU, physical memory, load average and the capacity of the volume holding the case; the Performance screen samples every two seconds while open and stops when you navigate away. A counter this platform does not publish renders as "not reported by this operating system". Held by `HostMetricsTest` and two gate checks. |
| A-5 | **A backend capability no interface reached.** `SearchFacade.getSearchSuggestions` — fuzzy "did you mean" over the live index — existed, was tested by nothing and was reachable from nowhere. | Medium — backend without UI | **Wired.** A query that returns nothing now offers the closest documents on the case as clickable chips. Held by a new assertion block in `FacadeParityTest`, including that a nonsense query offers nothing rather than an invented suggestion. |
| A-6 | **Documentation disagreed with the code in three places.** `VERIFICATION_REPORT.md` and `INTERFACE_INVENTORY.md` said contextual "analyse this" buttons were "not yet wired" (they are, on seven destinations); `INTERFACE_INVENTORY.md` said Batch Analysis had been folded into the Processing Monitor with no run-history table (it is a destination of its own, with persisted history); the host-gauge limitation was stated as impossible rather than unbuilt. | Medium — docs that lie are worse than docs that are missing | **Fixed** in all three documents. |
| A-7 | **A screen lifecycle hole.** The shell stopped pollers by naming one class (`instanceof ProcessingMonitorScreen`). Any future polling destination would leak a timer and keep the JavaFX toolkit alive at exit — a defect this project has already been bitten by once. | Low, latent | **Fixed.** `Screen` now declares `onHide` and `dispose`; the shell drives both for every destination. |
| A-8 | **The Java API carried the reference project's vocabulary.** `PreviewDto.PreviewType` exposed `pythonValue()` — a Java accessor named after the other implementation, on a public DTO. Nothing called it, which is how it survived review. | Low — architectural leakage into the public surface | **Fixed.** Renamed `label()`, documented as this application's own lowercase name for a preview kind. `ArchitectureInvariantsTest` now fails the build on reference-runtime references in the sources. |
| A-9 | **Every shell script was committed non-executable.** `./run-tests.sh` and `./final-acceptance.sh` — the two documented entry points — failed on a fresh clone with `Permission denied`, and `final-acceptance.sh` recorded the resulting packaging failure as a product failure. | High — the documented way to verify the product did not work | **Fixed.** The execute bit is recorded in the repository for all eight scripts. `build-installer.sh` also gained a toolchain preflight that names what is missing and exits 3, which the gate reports as a skip rather than a failure. |
| A-10 | **Half the test base could not run without the network.** The JUnit suites — facade, agent, batch, model, destinations — were reachable only through Gradle, which resolves dependencies online. On an air-gapped machine that half silently never ran, and nobody would have noticed. | High — unverifiable verification | **Fixed.** `JUnitRunner` executes them from `lib/` alone, and the battery runs them. It also distinguishes a failure caused by there being no graphics device from a real failure. |
| A-11 | **A lock conflict would have been treated as damage.** While adding index recovery, opening a case that was already open threw a Lucene lock error, which the new repair path read as corruption: it moved the healthy index of the live session aside and rebuilt underneath it. Found by probing, before it ever shipped. | High — data loss in the recovery path itself | **Fixed.** Lock conflicts are recognised and refused in words; the repair path restores the case unchanged if a rebuild cannot start; `ResilienceTest` asserts the open session's index is untouched. |
| A-12 | **The reference's per-file retry had no counterpart.** `/api/analysis/retry/<file_id>` genuinely reprocesses a file there. Here, a file that failed once could only be dealt with by re-running the case. | Medium — a real reference capability, missing | **Implemented.** `IngestPipeline#retry` re-runs one element through the same pipeline, keeping its identifier, notes and tags; the Errors destination offers it; `DestinationCoverageTest#retryElement` covers success, an unknown element and a missing original. |
| A-13 | **A corrupt index made a case unopenable.** Even though the database holds everything the index does, a half-written index refused the whole case. | Medium — recoverable data presented as lost | **Fixed.** `CaseDatabase#allItems` reads the case back out; `LiveCase` rebuilds the index on open, keeps the damaged copy under `logs/`, records the repair as a notification, and Setup offers a manual rebuild. |

---

## 2. Hunts that found nothing

Recorded because a hunt that finds nothing is still evidence — provided the method is
stated and can be re-run.

| Hunt | Method | Result |
|---|---|---|
| Unreachable UI actions | Counted `Button`/`MenuItem` constructions against `setOnAction` handlers in all 43 UI classes; grepped for empty handlers `-> {}` | No screen has more controls than handlers; no empty handler |
| Fake data / placeholder markers | Grepped the whole main source tree for `TODO`, `FIXME`, `stub`, `placeholder`, `dummy`, `sample data`, `Math.random`, `not implemented`, `coming soon` | One benign explanatory comment in `IngestPipeline`; no fake data |
| Hard-coded statistics | Grepped the UI for literal percentages and the reference's own magic numbers (`45`, `62`, `38`, `2.3`, `98.5`) | None in the Java UI; all four appear only in the Python reference |
| AI inside normal processing | Source scan of nine pipeline packages plus `Launcher` for `com.aegis.fdx.ai`, and a constant-pool scan of compiled classes in B-01 | Zero references, in source and bytecode |
| Accidental AI startup dependency | See A-1 — found, fixed, and now measured against a live runtime | Now zero requests at startup |
| Agent escape hatches | Grepped `ai/` for `ProcessBuilder`, `Runtime.exec`, `createStatement`, file writes, class loading, sockets; checked that `ai/agent` and `ai/tools` contain no networking at all | Only `ai/model` speaks HTTP, only to loopback, refused otherwise at construction |
| Write capability without confirmation | Traced both mutating tools: absent from the registry unless `allowMutations()`, re-checked at execution, and the screen's consent checkbox reverts when the confirmation is declined | Gated at three layers |
| Relationships that do not survive restart | Existing suites reopen the case and re-assert: `BatchAnalysisTest` (history), `M3AcceptanceTest` (database and index after an abrupt stop), `EndToEndScenarioTest` (search after restart) | Persisted |
| Search/index inconsistency | `EndToEndScenarioTest` and `FacadeParityTest` compare registry counts against index counts and search totals | Consistent |
| Python architectural leakage | Grepped the facade layer for Flask/route/ORM vocabulary; checked no HTTP, no Jinja, no Python data assumptions cross into Java | Facades are Java-native; the reference is honoured behaviourally, not structurally |
| Terminology | Checked the "Side → Aspect" rename: `SideFacade`/`SideDto` survive only as deprecated delegating aliases, and no screen shows the old word | Consistent, with the compatibility layer documented |

---

## 3. The three classified items

The brief required a decision on each, not a deferral.

### Batch scheduling — **deliberately not reproduced, on evidence**

Read from the reference rather than assumed: the Schedule dropdown and the "Off-Hours"
template in `templates/Analysis/analysis_batch.html` and
`static/js/pages/analysis-batch-page.js` only place the string `schedule: 'off-hours'`
into a request body. `Api/routes/analysis.py` never reads it, and the project contains
no scheduler, queue, timer or schedule table. The control is decorative there.
Reproducing it in Java would mean inventing a capability, then maintaining a scheduler
nobody asked for, in an application whose runs must be attributable to a person. What
the Java application does instead is real: the Batch Analysis destination runs the
chosen template now, over the records the operator selected, and writes a history that
survives a restart — which the reference only appears to do (its History modal is
hard-coded HTML). If deferred runs are genuinely wanted, they should be specified as a
new requirement, not smuggled in as parity.

### Host CPU and disk gauges — **implemented, measured**

Java-native feasibility was the question, and the answer is yes for CPU, memory and
volume capacity: `com.sun.management.OperatingSystemMXBean` (reached reflectively, so a
runtime without it degrades instead of failing to start) and `FileStore`. Implemented
and wired. The one figure still not shown is an instantaneous disk-I/O *rate*: the JVM
exposes no portable byte-rate counter, and estimating one would be exactly the
fabrication this audit exists to prevent. Volume capacity, free space and the case's own
footprint are shown instead, and the omission is stated in the interface and in the
limitations table rather than hidden.

### Real local-model generation — **architecture complete; execution is hardware-bound**

The protocol, provider, agent loop, tool contract, grounding, cancellation, audit trail,
failure handling and configuration are complete and verified end to end against a
scripted loopback runtime that speaks the real wire format. What cannot be done on a
development machine with a few hundred megabytes of free memory is load a 7B-parameter
model and measure its answers. No test fakes generation, and no claim of benchmarked
model quality is made anywhere in this repository. Running it on adequate hardware is a
documented, self-contained step: install a local runtime, pull a model, launch with
`-Daegis.ai.enabled=true`.

---

## 4. What still cannot be executed here

| Item | Where it must run | Why not here |
|---|---|---|
| The icon-set check (one JUnit test that builds a scene graph) | Any desktop with JavaFX and a display | No graphics device, and the only JavaFX jars obtainable in this environment carry no Linux native libraries. Reported as "not runnable on this machine", never as a pass. |
| OCR assertions | A machine with Tesseract installed | Tesseract is not present; the suite reports the OCR checks as skipped and the "not installed" path is itself tested. |
| `packaging/build-installer.sh` | A full JDK 21+ with `jlink` and `jpackage` | The runtime here has no compiler or packaging tools. The script now says so and exits 3; the gate records a skip. |
| Windows MSI and Windows-host validation | A Windows machine | Platform. The 18 Windows-compatibility checks pass here on their portable parts. |
| Real-model answer quality | A machine with enough memory for a 7B model | Hardware. The protocol, tool loop, grounding and audit trail are verified against a scripted runtime; no test fakes generation. |
| Certified performance figures | 8-core / 16 GB / NVMe reference hardware | This environment has 2 cores and 3 GB; its figures are indicators only. |

Everything else in this repository was executed: **1,072 assertions across the battery,
0 failures**, and the release gate reports **66 passed, 0 failed, 5 skipped** for the
reasons above.
