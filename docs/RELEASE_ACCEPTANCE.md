# AEGIS-FDX — Release Acceptance Report

**Date:** 2026-09-08 · **Branch:** `arena/01a081ae-aegis-fdx` · **Base commit:** `df94608`

---

## 0. The verdict first

**The application is NOT ready for release, and the reason is single and specific: the
JavaFX application has never been compiled or executed.**

Everything behind the interface — SQLite, Lucene, the derived statistics, the
relationship model, recovery, instrumentation — is implemented, measured and tested. The
interface layer that sits on top of it is source code that no compiler has ever accepted.

That is not a small caveat and this report does not treat it as one. The acceptance
directive's Definition of Done has 38 items; **12 of them require a running JavaFX
application on Windows and all 12 are NOT RUN.**

This pass therefore did what could be done honestly: it resolved the architectural
question the directive flagged as the release risk (`CorpusDatabase`), closed a real
enforcement gap it found while doing so, made two previously grep-only invariants into
tests, and drew a hard line in the documentation between "proven" and "wired up but
never run".

---

## 1. Environment — and why §3, §4, §30 and §31 could not be performed

| | This pass | Directive requires |
|---|---|---|
| OS | Linux (e2b sandbox), kernel 6.1 | Windows 10/11 x64 |
| CPU / RAM | Xeon @2.60 GHz, **2 cores / 3 GB** | 8-core / 16 GB reference |
| Storage | overlay filesystem | NVMe |
| JDK | jdk4py OpenJDK 25 runtime (**no `javac`**) | JDK 21 with compiler |
| Compiler | ECJ 3.45 (Eclipse batch compiler) | Gradle + `javac` |
| Build tool | none — **no `gradlew` in the repository** | Gradle wrapper |
| JavaFX | **absent and unobtainable** | JavaFX 21 SDK |

Re-confirmed this pass, not assumed: `which java javac gradle` finds nothing, there is no
`gradlew`, `lib/` contains 41 jars and none is JavaFX, a filesystem-wide search for
`javafx*.jar` or `libglass*` returns nothing, `repo1.maven.org` fails TLS handshake, and
there is no `/mnt/c`.

**Consequence:** §3 (compile JavaFX), §4 (launch), §5–§7 (live dashboard), §13–§21 (live
UI acceptance), §30 (Windows), §31 (clean install) **cannot be performed here at all.**
They are reported as NOT RUN. None is reported as passing.

A stub-JavaFX shim to at least type-check the UI was evaluated and rejected: 87 distinct
`javafx.*` types are referenced including wildcard imports of `javafx.scene.control.*`
and `javafx.scene.layout.*`. A hand-written shim that compiled would prove only that the
shim matches itself, and the directive explicitly forbids stubs as final verification.

---

## 2. §22 — `CorpusDatabase`: RESOLVED

This was flagged as the release-blocking architectural risk. It is now resolved **by
execution**, in `CorpusAuthorityTest` (12 tests, all passing).

### The finding

**`CorpusDatabase` is not a second database. It is a DAO over the one authoritative
`case.db` connection.** The name is misleading; the architecture is not.

Evidence, each an executed test rather than an inspection:

| Question | Test | Result |
|---|---|---|
| Own connection? | `corpusSharesTheCaseConnectionRatherThanOpeningItsOwn` | No — an uncommitted corpus write is visible through `CaseDatabase`, and a rollback on the case connection undoes it. Same connection, same transaction. |
| Own file? | `noSecondDatabaseFileIsEverCreated` | No — after exercising sources, words and categories, the case directory contains exactly `case.db`. |
| Own schema / attached db? | `corpusTablesLiveInTheSameFileAsTheForensicSchema` | No — `item`/`queue`/`audit` and `path`/`word`/`category`/`keyword` are all in `main.sqlite_master`; `PRAGMA database_list` shows only `main` and `temp`. |
| Duplicate case state? | `pathIsASatelliteOfItemAndIsDeletedWithIt` | No — `path.element_id` is `REFERENCES item(id) ON DELETE CASCADE`; deleting the item removes the path row. `item` is authoritative for case membership. |
| Orphan rows possible? | `everyPathRowCarriesAnElementIdInPractice…` | No path row without a backing item on the production write path. |

### Its precise role, for the record

`CorpusDatabase` owns the **five integrated concepts** — Sources, Aspects, Categories,
Keywords, Contents — plus the relationship edges (`path_word`, `path_keyword`,
`path_category`). `CaseDatabase` owns the **forensic pipeline**: `item`, `queue`,
`audit`, `setting`, `item_meta`.

The `path` table is a **projection of `item`** into the reference project's schema shape,
written once per element by `ContentFacade.registerIngestedItems` and joined back to
`item` on `element_id`. It denormalises four columns (`file_name`, `file_path`,
`file_size`, `file_type`).

**Recommendation, not performed this pass:** those four columns are the only place the
two schemas could drift. They are drawn from `Item` fields that the upsert treats as
stable identity (name, source path, size, extension), and no code updates them after
registration, so drift is not currently reachable. It is still a latent duplication and
the honest options are (a) drop the four columns and join to `item`, or (b) add a
reconciliation check to `verify()`. Neither is a release blocker; both should be recorded
as technical debt. **Do not** promote `path` to authoritative, and **do not** add a write
path that updates it independently of `item`.

### The gap this investigation found — and closed

Reading `insertKeyword` showed the three-word rule enforced in Java (`requireKeywordPhrase`)
but **not in the schema**: `keyword.phrase` was `TEXT NOT NULL UNIQUE` with no constraint.
A test confirmed raw SQL could insert a one-word "keyword" — which the relationship model
cannot distinguish from a category word, exactly the rot the `Terms` class exists to
prevent.

§16 says *correct inconsistencies rather than merely documenting them*, so it is fixed.
`CorpusSchema.migrate` now installs four triggers enforcing the semantics at the file
level, on INSERT **and** UPDATE, for `keyword.phrase` (≥3 words) and `word.word` (exactly
1 word).

Triggers, not CHECK constraints, deliberately: adding a CHECK requires rebuilding the
table, which on a multi-gigabyte case means copying every row. Triggers apply to existing
cases at migration time and cost nothing measurable on a term vocabulary of thousands of
rows.

Four tests now cover it, including `schemaEnforcementAgreesWithTheJavaEnforcement`, which
compares the two layers on *why* they refused — a UNIQUE violation is not a semantics
rejection, and conflating them is how a test like that silently stops testing anything.

---

## 3. §8 — duplicate connection: RESOLVED and now enforced

The previous pass found `AegisApp` opening a second `DriverManager` connection to the
same `case.db` for one error-report query: no configured PRAGMAs, its own lock, never
closed.

Verified fixed. `grep` across `app/src/main/java` finds exactly **one**
`DriverManager.getConnection`, in `CaseDatabase:30`.

A grep does not survive a merge, so this is now
`ArchitectureInvariantsTest#onlyCaseDatabaseOpensAConnection`: it walks every main-tree
`.java` file and fails if any class other than `CaseDatabase` opens a connection.
**14/14 pass.**

---

## 4. Test results by category (§28)

Run with ECJ-compiled classes on the jdk4py runtime. **UI suites are excluded because
they cannot compile** — that exclusion is the point of the separation.

| Category | Suite(s) | Result |
|---|---|---|
| Architecture | `ArchitectureInvariantsTest` | **14/14 VERIFIED** |
| Database — corpus authority (§22) | `CorpusAuthorityTest` | **12/12 VERIFIED** |
| Database — transactions & recovery | `BatchRecoveryTest` | **5/5 VERIFIED** |
| Dashboard — derived statistics | `DashboardStatsTest` | **12/12 VERIFIED** |
| Search — facets | `SearchFacetsTest` | **8/8 VERIFIED** |
| Relationships — counts | `RelationshipCountsTest` | **6/6 VERIFIED** |
| Relationships — model | `RelationshipModelTest` | **16/16 VERIFIED** |
| Instrumentation | `OperationTimingsTest` | **9/9 VERIFIED** |
| Interface matrix (static resolution) | `InterfaceFunctionMatrixTest` | **7/7 VERIFIED** |
| **Full headless suite** | all of the above + pre-existing | **172/172 pass, 0 failed (7.8 s)** |
| **JavaFX / UI execution** | `UiParityTest`, `DestinationCoverageTest`, `DragDropIngestTest`, `InterfaceTest`, `CoverageMatrixTest` | **NOT RUN — will not compile without JavaFX** |
| **Integration (full app)** | `run-tests.sh`, `final-acceptance.sh` | **NOT RUN — require Gradle + JavaFX** |

172 → up from 160, +12 from `CorpusAuthorityTest`. No regressions from the new triggers.

---

## 5. Definition of Done (§38) — honest status

| # | Item | Status |
|---|---|---|
| 1 | Real JavaFX compilation succeeds | **NOT RUN** |
| 2 | Real JavaFX application launches | **NOT RUN** |
| 3 | Comprehensive Dashboard executes | **NOT RUN** |
| 4 | Dashboard is non-blocking | **LIMITED** — implemented via `Background`, never executed |
| 5 | Dashboard displays real values | **LIMITED** — every value traced to a real query; never rendered |
| 6 | Dashboard refresh works | **NOT RUN** |
| 7 | Dashboard works during ingest | **LIMITED** — proven headlessly (p99 4.04 ms under ingest), not in the UI |
| 8 | Application shuts down cleanly | **NOT RUN** |
| 9 | Duplicate database connection resolved | **VERIFIED** — one connection; now test-enforced |
| 10 | `CorpusDatabase` role resolved | **VERIFIED** — DAO on the shared connection; 12 tests |
| 11 | SQLite architecture verified | **VERIFIED** |
| 12 | Lucene architecture verified | **VERIFIED** |
| 13 | Derived statistics verified | **VERIFIED** — 12 tests |
| 14 | Statistics rebuild verified | **VERIFIED** — deterministic; `verify()` true at 5M |
| 15 | Relationship model verified | **VERIFIED** — 22 tests, both directions |
| 16 | Keyword = 3+ words | **VERIFIED** — Java *and* schema triggers |
| 17 | Category = one word | **VERIFIED** — Java *and* schema triggers |
| 18 | Category word = one word | **VERIFIED** — Java *and* schema triggers |
| 19 | Search Everywhere verified | **LIMITED** — operations tested headlessly; UI never run |
| 20 | File Detail verified | **LIMITED** — same |
| 21 | Term Detail verified | **LIMITED** — same |
| 22 | Bidirectional navigation verified | **LIMITED** — data traversals proven; navigation never clicked |
| 23 | Interface matrix complete | **VERIFIED** — 122 rows, all classified |
| 24 | No unexplained dead controls | **VERIFIED** — 4 REFERENCE-INERT + 5 UNSUPPORTED, each explained |
| 25 | Reference behaviour cross-checked | **VERIFIED** (static) |
| 26 | Crash recovery verified | **VERIFIED** — headless interruption tests |
| 27 | Evidence integrity verified | **VERIFIED** |
| 28 | Performance instrumentation verified | **VERIFIED** — 9 tests |
| 29 | Full test suite passes | **VERIFIED for headless (172/172)**; UI suites NOT RUN |
| 30 | UI tests separated from headless | **VERIFIED** — §4 above |
| 31 | Windows acceptance | **NOT RUN** |
| 32 | Clean-install acceptance (AT-10) | **NOT RUN** |
| 33 | Performance benchmark documented | **VERIFIED** — `DATABASE_PERFORMANCE_REPORT.md` |
| 34 | SQLite replacement decision | **VERIFIED** — §7 below |
| 35 | Native-code decision | **VERIFIED** — §8 below |
| 36 | Coverage inventory updated | **VERIFIED** — 99 rows |
| 37 | Final status updated | **VERIFIED** |
| 38 | *(release verdict)* | **BLOCKED** on items 1–8 |

**Tally: 22 VERIFIED · 7 LIMITED · 9 NOT RUN.**

---

## 6. Final acceptance matrix (§36)

| Area | Requirement | Implementation | Test | Result | Evidence |
|---|---|---|---|---|---|
| Database | One authoritative store | `CaseDatabase` + `CorpusDatabase` DAO on one connection | `CorpusAuthorityTest` (12) | **VERIFIED** | §2 |
| Database | One connection, tuned PRAGMAs | `CaseDatabase.configure` | `BatchRecoveryTest#pragmasAreInForce`, `ArchitectureInvariantsTest#onlyCaseDatabaseOpensAConnection` | **VERIFIED** | §3 |
| Database | Batched transactions | batch 5,000 | `BatchRecoveryTest` (5) | **VERIFIED** | 6,451→17,128 rows/s |
| Database | No unintended scans | `ix_queue_source` + 22 indexes | `PlanAudit`, `BatchRecoveryTest#resumeLookupUsesIndex` | **VERIFIED** | `docs/bench/plans.tsv` |
| Lucene | Full-text engine, NRT, facets | `LuceneIndex`, `SearchFacets` | `SearchFacetsTest` (8) | **VERIFIED** | worst P99 53 ms |
| Dashboard | Real values, derived stats | `DashboardStats` | `DashboardStatsTest` (12) | **VERIFIED (data)** | 15,208 ms → 0.14 ms @5M |
| Dashboard | Non-blocking, renders | `Background`, `ComprehensiveDashboardScreen` | — | **NOT RUN** | no JavaFX |
| Search | Query types, latency | `LuceneQueryBuilder` | `SearchFacetsTest`, bench | **VERIFIED (headless)** | `search-200k.tsv` |
| Search | UI acceptance | Search screens | — | **NOT RUN** | no JavaFX |
| Relationships | Case-wide bidirectional counts | `CorpusDatabase` | `RelationshipCountsTest` (6), `RelationshipModelTest` (16) | **VERIFIED** | `COUNT(DISTINCT path_id)` |
| Term semantics | 3+/1/1 words | `Terms` + schema triggers | `CorpusAuthorityTest` (4) | **VERIFIED** | §2 |
| UI | Every control mapped | 122-row matrix | `InterfaceFunctionMatrixTest` (7) | **VERIFIED (static only)** | §9 caveat |
| UI | Launch, click-through | JavaFX app | — | **NOT RUN** | no JavaFX |
| Recovery | Consistent after interruption | commit boundaries | `BatchRecoveryTest` | **VERIFIED** | `verify()` true |
| Evidence integrity | Originals immutable | read-only pipeline | `ArchitectureInvariantsTest` | **VERIFIED** | SHA-256 stable |
| AI boundary | Outside the pipeline | `AgentService` | `AiBoundaryTest`, `ArchitectureInvariantsTest` | **VERIFIED** | no core reference |
| Performance | P50/P95/P99 recorded | benches + `OperationTimings` | `OperationTimingsTest` (9) | **VERIFIED (sandbox)** | 2-core floor |
| Packaging | Installer, jpackage | `packaging/` | — | **NOT RUN** | no JDK/Gradle |
| Windows | Clean build, launch, install | — | — | **NOT RUN** | no Windows host |

---

## 7. §34 — Database decision: **KEEP SQLITE**

Unchanged and now better supported. SQLite was never the bottleneck: the two real defects
were a dashboard that aggregated from scratch on every refresh (15.2 s at 5M → 0.14 ms
with derived statistics, 105,741×) and a missing index that made resume quadratic (114 s
→ 6.9 s at 500K). Both were application-level and both were fixed inside the existing
architecture.

Ingest sustains 6,109 rows/s with statistics enabled = **21,990,924 items/hour against an
8,000/hour requirement**. Worst search P99 is 53 ms against a 2,000 ms target. Dashboard
p99 under concurrent ingest is 4.04 ms against 500 ms.

**No migration. No DuckDB.** DuckDB remains justifiable only as a disposable analytical
layer if a future analytical workload is measured and shown to need it. Nothing measured
here needs it.

## 8. §35 — Native-code decision: **KEEP JAVA CORE**

No native candidate has been identified, and the honest reason is that **extraction, OCR
and parsing have never been profiled** — the benchmarks measured database and index
paths. "No candidate identified" is not "no candidate exists". Profile the extraction
pipeline before this question is considered answered. No JNI/JNA work is justified on
current evidence.

---

## 9. §33 — the classification that matters most

The interface matrix now states this in its own header, because a reader who sees "83
VERIFIED" will otherwise conclude the interface works:

> **VERIFIED in the interface matrix means the operation behind a control is proven by a
> headless test and the control is wired to it in source. It does not mean anyone has
> clicked it.**

The defects static resolution cannot see are precisely: a handler attached to the wrong
control, a value formatted into the wrong column, a dialog that never opens, a listener
registered twice, an executor that blocks shutdown. Finding those requires launching the
application.

---

## 10. §37 — Engineering verdict

**What is complete.** The data layer, end to end: schema, transactions, indexes, derived
statistics, relationship model, term semantics (now at two levels), Lucene indexing and
search, facets, crash recovery, evidence integrity, runtime instrumentation, and a
122-row interface map with no unclassified controls.

**What is verified.** 172 headless tests, 0 failures, across nine named categories.
`CorpusDatabase` resolved as non-authoritative by execution. One database connection,
test-enforced. Term semantics enforced in Java *and* in the schema.

**What is limited.** Every claim that touches the screen. The dashboard's data is proven;
its rendering is not. Bidirectional navigation is proven as data traversal; as
navigation it is unexercised.

**What remains.** Compile the UI against a real JavaFX 21 SDK; launch; exercise the
Comprehensive Dashboard, Search Everywhere, File Detail and Term Detail; confirm clean
shutdown with no lingering executor; run the Windows and clean-install passes; run the
10M benchmark on reference hardware.

**What performance was measured.** Ingest 17,128 rows/s batched, 6,109 with statistics.
Dashboard 0.14 ms at 5M items. Search worst P99 53 ms. Dashboard P99 4.04 ms under
concurrent ingest. All on **2 cores / 3 GB / overlay FS** — roughly a quarter of the
reference machine. These are floors; the ratios transfer, the absolutes do not.

**What is the current bottleneck.** Not the database. On this hardware, ingest is bounded
by extraction and hashing, and the statistics triggers add a measured 64%. Nothing in the
read path is close to its budget.

**Is SQLite still appropriate.** Yes, decisively.

**Is native code justified.** No, and not yet properly asked — extraction is unprofiled.

**Is the Comprehensive Dashboard production-ready.** Its *data* is. The *screen* is
unproven. It cannot be called production-ready until it has been rendered once.

**Is the JavaFX application production-ready.** **No.** It has never been compiled.

**What exact work remains before release.**

1. Obtain JavaFX 21 SDK; add the Gradle wrapper (absent from this repository).
2. Compile the full application including `ui/**` and `Launcher.java`; fix what surfaces.
3. Launch; verify startup, case open, dashboard, navigation, clean shutdown, no orphan
   threads, no UI-held database lock.
4. Comprehensive Dashboard live: every tile against a known case; refresh; filters;
   charts; behaviour during ingest; cancellation on navigate-away.
5. Run the five excluded UI suites; run `run-tests.sh` and `final-acceptance.sh`.
6. Windows acceptance and clean-install (AT-10).
7. 10M benchmark on reference hardware.
8. Then, and only then, localisation.

Items 1–5 are the release gate. Nothing in this report should be read as evidence that
the interface works.
