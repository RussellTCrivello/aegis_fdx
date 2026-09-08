# Final status — every directive, its evidence, and what is still limited

**Date:** 2026-09-08 · **Branch:** `arena/01a08149-aegis-fdx` · **Battery:** `./run-tests.sh 2`, exit 0 · **Gate:** `./final-acceptance.sh 2`, 66 passed · 0 failed · 5 environmental skips

Five statuses are used and they mean exactly what they say.

- **PASS** — the behaviour exists, is reachable from the interface, persists where it should, and was proven by a test that was executed on this machine in the run above.
- **ADAPTED** — same observable outcome and same stored data, reached by a desktop-appropriate mechanism; each instance is named in `docs/interface-function-matrix.tsv` with the difference stated.
- **LIMITED** — exists and is reachable; some part of validating or completing it needs something not present here. What is missing is stated.
- **UNSUPPORTED** — deliberately not reproduced, with the reason.
- **ENVIRONMENT-LIMITED** — implemented and covered, but the confirming step needs a display, hardware or software this build machine lacks.

Nothing is marked PASS because the code exists. Everything marked PASS ran; the run is in `docs/VERIFICATION_REPORT.md` §0 with the toolchain that produced it.

---

## 1. Completion matrix

| Area | Status | Executable evidence | Remaining limitation |
|---|---|---|---|
| Reference destinations | **PASS** | 33/33 destination classes; `UiParityTest#screenInventory`, `CoverageMatrixTest` "every destination in the interface is inventoried", `InterfaceFunctionMatrixTest#everyScreenInventoried` | — |
| Interface controls | **PASS** | 120 rows in `interface-function-matrix.tsv`, each control → handler → facade → DB op → test; `InterfaceFunctionMatrixTest#everyButtonInventoried` (every `Fas.*("…")` button label has a row); `ArchitectureInvariantsTest#everyControlEndsInAnOperation` | 27 ADAPTED rows (desktop mechanism), 4 REFERENCE-INERT, 5 UNSUPPORTED, 2 LIMITED — all explained in the TSV |
| JavaScript behaviour | **PASS / ADAPTED** | Every `static/js/pages/*.js` handler traced to a Java handler or an explained status (columns `js_handler`, `js_api_operation`); inert handlers (`bulkUpdate`, alert toggles, batch schedule) recorded as REFERENCE-INERT, not reproduced | Toasts → dialogs (G04), print/share/fullscreen browser conveniences not reproduced (F04) |
| Java UI | **PASS** (construction, routing, handlers) / **ENVIRONMENT-LIMITED** (rendering) | `UiParityTest`, `DestinationCoverageTest` — 45 of 46 interface tests ran; screen builds and Router transitions exercised without a scene | 1 test needs a graphics pipeline; no GUI click-through possible here (no display, no JavaFX natives) |
| Java backend | **PASS** | `FacadeParityTest`, `IntegrationModelTest`, `ArchitectureInvariantsTest` (13 rules incl. no Python/web dependency, no AI in the pipeline) | — |
| Sources | **PASS** | `DestinationCoverageTest#editSource/#sourceStatistics`, `FacadeParityTest`; create/edit/duplicate/delete/statistics/relationships | `toggleSourceStatus` UNSUPPORTED (flag nothing reads) |
| Aspects | **PASS** | `DestinationCoverageTest#aspectStatistics`, `RelationshipsScreen` (aspect relationships) | — |
| Categories | **PASS** | exactly-one-word invariant at UI, facade, DAO, test (`RelationshipModelTest#storageInvariants`, `FailureRecoveryTest#invariantViolations`); find/merge duplicates; CSV export; reverse detail | display-format toggle UNSUPPORTED (C04) |
| Keywords | **PASS** | ≥ 3 words invariant at four layers; one category each; whole-case `COUNT(DISTINCT path_id)`; detail with files + match type; merge duplicates; CSV export | `bulkUpdate` REFERENCE-INERT (K12) |
| Category Words | **PASS** | one-word invariant; word ↔ category symmetric both ways (A-14 fix); files per word; detail | `bulkUpdate` REFERENCE-INERT (W09) |
| Files | **PASS** | file detail evidence hub: path, name, ext, size, SHA-256, MD5, MIME, dates, source, aspect, status, metadata, content, keyword/category/word chips (`RelationshipModelTest#fileDetail`, F01–F10 rows) | delete UNSUPPORTED by design (evidence never deleted, F11); per-file PDF render not offered (F09) |
| Content | **PASS** | full content view, copy, search-in-content (`FullContentScreen`, `ContentFacade#getContentAsText`) | — |
| Relationships | **PASS** | bidirectional File↔Keyword/Category/Word, Keyword↔Category, Keyword↔Word, Category↔Word; `RelationshipIntegrity#check` walks 28 traversals both ways with agreeing counts; planted orphan edge detected; duplicates never double-count (`RelationshipModelTest` 16 tests, `FailureRecoveryTest#duplicateRelationship`); `docs/RELATIONSHIP_INTEGRITY_REPORT.md` | — |
| Search | **PASS** | search on every list and detail with per-row match type; scopes File/Keyword/Category/Word; blank refused in words, malformed syntax refused with a fix hint or answered empty (`FailureRecoveryTest#emptyAndMalformedSearch`, `QueryValidationTest` 69) | fuzzy/expansion are query syntax not checkboxes (S02, ADAPTED) |
| Persistence | **PASS** | single `case.db` (`final-acceptance` "Single case database", "Registry linked to engine"); relationships, counts, history identical after reopen (`FailureRecoveryTest#restartKeepsRelationships`, `BatchAnalysisTest#historySurvivesRestart`, `SettingsPersistenceTest`) | — |
| Processing | **PASS** | AT-01…AT-10 (56), drag-and-drop (10), analyzer derived & idempotent (`integrityConsistent` re-runs `analyzeAll` ×3, edge counts unchanged) | OCR needs Tesseract (skipped, path tested) |
| Index recovery | **PASS** | `ResilienceTest` (6): damaged index rebuilt from case.db incl. tags/notes/text, damaged copy kept; lock conflict not mistaken for damage; unreadable DB explained | — |
| AI boundary | **PASS** | `AiBoundaryTest` B-01…B-08 (48): optional, local-only, manual, read-only default, 0 model calls during ingest, nothing loaded at startup; gate checks "AI outside the processing pipeline", "AI cannot trigger processing" | — |
| AI agent | **PASS** (loop, tools, grounding, provenance, failures) / **LIMITED** (generation) | `AiAgentTest` (25): multi-step loop, `[OBSERVED]/[DERIVED]/[INFERRED]/[USER-PROVIDED]/[UNKNOWN]` on every statement with demotion of unsupported claims, bounded, cancellable, unknown/malformed tool handled; `FailureRecoveryTest#malformedModelResponse/#emptyModelContent` | real-model output needs memory for a 7B model; verified against a scripted loopback runtime speaking the real protocol |
| Lifecycle | **PASS** | `EndToEndScenarioTest` create → process → analyse → search → export → restart → ask; pollers stopped on navigation (A-7) | — |
| Failure recovery | **PASS** | 21-line inventory in `VERIFICATION_REPORT.md` §0, every line an executed test (`FailureRecoveryTest` 11, `ResilienceTest` 6, AT-01/03/05, M3 crash recovery, settings, agent failures) | — |
| Documentation | **PASS** | `COVERAGE_MATRIX.md` and `INTERFACE_FUNCTION_MATRIX.md` are rendered from TSVs the build checks; README, ARCHITECTURE, AI_AGENT, AI_BOUNDARY, DATABASE, FUNCTION_INVENTORY, ADVERSARIAL_AUDIT (19 findings), VERIFICATION_REPORT synchronised to this run | — |
| Localization preparation | **PASS** (preparation) | `docs/LOCALIZATION_PREPARATION.md`: measured string inventory (≈1,200 screen literals, 117 buttons, 78 headers, 49 facade messages, 93 composed formats), resource-bundle architecture, frozen domain vocabulary with EN/DE/NL/FR/ES glossary | **No translation performed** by instruction; language switch is LIMITED (ST2/R07) until that phase |

## 2. What is ADAPTED, UNSUPPORTED or REFERENCE-INERT, in one place

The per-control list with reasons is the "Rows that are not VERIFIED" section of `docs/INTERFACE_FUNCTION_MATRIX.md` (38 rows). The decisions that shape them:

| Decision | Classification | Why |
|---|---|---|
| Inline list filters → Search destination | ADAPTED | Same index, same results, one place; lists stay paged and fast |
| Modals / toasts → dialogs and inline labels | ADAPTED | Desktop idiom; identical operations and messages |
| Column sort in the table | ADAPTED | Reference re-queries with `ORDER BY`; same visible order without a round trip |
| Bulk "update category" buttons, saved-search alerts, batch schedule/resource controls | REFERENCE-INERT | Reference handlers post nothing or store a flag nothing reads; reproducing them would fabricate a feature |
| Delete file | UNSUPPORTED | Evidence is never deleted; the hash chain and audit trail depend on it |
| Source status toggle, display-format switches, HTTP error pages, template partials | UNSUPPORTED | No information behind them |
| Language switch | LIMITED (deferred) | Translation is the next phase; preparation is done |
| Email add-to-contacts | LIMITED | No contacts store exists in this application |

## 3. What is LIMITED or ENVIRONMENT-LIMITED, and how to finish it

| Item | What is missing | How to complete |
|---|---|---|
| GUI click-through; `UiParityTest` icon-set check | A display and JavaFX native libraries | Run `./run-tests.sh` on a desktop with JDK 21 + JavaFX SDK; the icon test reports "not runnable" here, never a pass |
| Real local-model generation | Memory for a 7B model, a local runtime | Install a runtime, pull a model, start with `-Daegis.ai.enabled=true`; the protocol path is already tested |
| OCR output | Tesseract | Install and re-run; the absent-engine path is tested |
| Gradle build, `jpackage` image, Windows MSI | Network, full JDK, Windows host | `packaging/build-installer.sh` on a full JDK; gate records skips until then |
| Performance certification | 8-core / 16 GB / NVMe | `./run-tests.sh 2000` on reference hardware; figures here (348,581 items/h, 6.43 ms p95) are indicators |
| Translated interface | The next phase | Follow §5 of `LOCALIZATION_PREPARATION.md` |

## 4. The numbers (this run)

```
Battery                 1,101 named assertions      0 failures
JUnit                   124 tests                   123 passed · 0 failed · 1 not runnable (display)
Release gate            66 checks                   0 failures · 5 environmental skips
Coverage inventory      91 rows                     76 verified · 8 adapted · 3 limited · 3 unsupported · 1 absent (deferred i18n)
Interface-function      120 rows                    82 verified · 27 adapted · 2 limited · 5 unsupported · 4 reference-inert
Relationship integrity  28 traversals both ways     consistent; planted damage detected
Failure inventory       21 failure classes          each backed by an executed test
Toolchain               OpenJDK 21.0.4 · ECJ 3.46 · JavaFX 23.0.1 classes (no natives) · no Gradle · no display
```

Reproduce with `./run-tests.sh` and `./final-acceptance.sh`; the gate writes `docs/ACCEPTANCE-RESULT.md` with the runtime, compiler and JavaFX build behind the result.
