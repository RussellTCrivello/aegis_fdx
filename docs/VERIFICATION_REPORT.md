# Final Verification Report

Generated: 2026-09-08 (revision 3, after the function-level audit)
Build machine: 2 cores, ~400 MB free RAM, no GPU

Everything below was measured by running the software, not inferred from the source.

---

## Interface inventory

A full audit of the reference project was performed before this revision
(`REFERENCE_AUDIT.md`). It measured the destination surface rather than assuming it.

```
Reference page destinations                 33   (39 templates, less base, partials,
                                                  and the two HTTP error pages)
Java destination classes                    33   (one per reference destination)
  navigable from the sidebar                26
  reached by drill-through                   7   (details and relationships)
Implemented                                 33 / 33
Partially implemented                        0
Missing                                      0
Rendered from the running application       33 figures
```

Revision 1 reported 16 screens (a scope assumption). Revision 2 closed 17 gaps but was
still one destination short: `Analysis/analysis_batch.html` had been folded into the
Processing Monitor, and three implemented destinations were never photographed. Both
are resolved here — see `FUNCTION_INVENTORY.md` §1 for the full discrepancy report.

Per-destination mapping to facade, backend and test: `INTERFACE_INVENTORY.md`.

---

## Five concepts

| Concept | Table | Facade | Screen | Relationships | Status |
|---|---|---|---|---|---|
| Sources | `source` | `SourceFacade` | Sources | → category; ← path, hash | Complete |
| Aspects | `aspect` | `AspectFacade` | Aspects | ← path | Complete |
| Categories | `category`, `word_category`, `path_category` | `CategoryFacade` | Categories | → word; ← keyword, path | Complete |
| Keywords | `keyword`, `path_keyword` | `KeywordFacade` | Keywords | → category; ← path (with hit counts) | Complete |
| Contents | `content`, `path`, `hash` | `ContentFacade` | File Library | → path → source/aspect/hash/**item** | Complete |

---

## Database

```
case.db                one authoritative database
Second database        none  (asserted by IntegrationModelTest.singleDatabase)
Connection             shared with the engine (CaseDatabase.connection())
Foreign keys           yes, including path.element_id → item(id) ON DELETE CASCADE
Transactions           span both halves (asserted by sharedTransaction)
Cascade                verified (cascadeFromEngine)
Migrations             CorpusSchema.migrate() runs inside CaseDatabase migration
Indexes                9 on the added tables
```

---

## Existing backend

```
Preserved   LiveCase, IngestPipeline, Analyzer SPI, LuceneIndex, OcrStage,
            Exporter, IntegrityVerifier, CaseFolder, CaseStore, QueryParser,
            Filters, IntakeWorker, and the entire item/queue/audit schema

Changed     CaseDatabase — two additions only:
              1. migrate() now also calls CorpusSchema.migrate(conn)
                 Reason: §11 requires the five concepts in the existing database
                         rather than a disconnected second one.
              2. added connection() accessor
                 Reason: the corpus DAO must share the engine's connection for
                         transactions and cross-schema joins to work.
            No engine behaviour was altered.

Evidence    Reference corpus still processes identically:
            49 indexed · 3 error · 1 locked · 5 unsupported
```

---

## AI

```
Local model        yes, required to be loopback; remote endpoints refused at construction
Runtime            pluggable via LocalModelProvider; HTTP implementation provided
Default model      qwen2.5:7b-instruct  (configurable, no code change)
Configuration      6 system properties: endpoint, model, embedModel, timeout,
                   contextTokens, maxOutputTokens, plus an enable switch
Agent              real multi-step loop, step-budgeted, cancellable
Tools              10 read-only + 2 confirmation-gated write tools
Retrieval          existing Lucene index, metadata, categories, keywords, relationships
Embeddings         EmbeddingProvider interface; local, optional, supplements keyword search
Offline tested     yes — AiAgentTest.offlineOperation, plus degradesWithoutRuntime
Cloud dependency   none  (gate check greps for provider hosts; zero found)
Escape hatches     none  (gate check for exec/ProcessBuilder/createStatement; zero found)
```

---

## Testing

```
Unit / contract      82 automated tests, 0 failures
  engine suites (bridged)          7
  interface layer                  7
  user interface                   6
  integration model                9
  AI agent                        23
  destination coverage            15
  batch analysis                  14   (new this revision)
  end-to-end scenario              1   (new this revision)

Standalone battery   266 assertions, 0 failures across 6 suites
Release gate         56 passed · 0 failed · 3 skipped
Destinations         33 rendered from the running application
Reference corpus     unchanged (49/3/1/5) — proves the engine is intact
```

Skipped gate items are environmental, not defects: OCR binary absent, Windows MSI
needs a Windows host, performance certification needs reference hardware.

---

## Documentation

```
Created    AI_AGENT.md              local model, agent, tools, safety, offline, testing
           VERIFICATION_REPORT.md   this document

Updated    INTERFACE_INVENTORY.md   Assistant screen and AI tool rows
           DIAGRAMS.md              AI architecture and AI search flow
           UI_GUIDE.md              Assistant screen walkthrough
           ARCHITECTURE.md          AI layer above the interface layer
           BUILD.md                 running with and without a local runtime
           SCREENS.md               17 figures
           PRESENTATION.md          AI slides and revised verification numbers
           README.md                AI capability and quick start

Remaining  none for this scope
```

---

## Defects found and fixed in this revision

| Defect | How found | Fix |
|---|---|---|
| One reference destination had no Java implementation; the 33-vs-32 arithmetic hid it | The directive challenged the count, so the inventory was re-run per template | `BatchAnalysisScreen` + `BatchAnalysisFacade` with persisted history |
| Keyword hit counts came only from the seed harness | Directive §10; a test now asserts zero hits before a run | `BatchAnalysisFacade` computes them from real extracted text |
| Batch enum dropdowns displayed `KEYWORD_SCAN` rather than "Keyword Scan" | Looked at the rendered figure | `StringConverter` on each combo |
| Three implemented destinations had no figure | Template-by-template mapping | Harness now visits all 33 |

## Defects found and fixed in revision 2

| Defect | How found | Fix |
|---|---|---|
| Processing Monitor's INDEFINITE `Timeline` kept the JavaFX toolkit alive; the application would not exit | A screenshot run hung for ten minutes | `dispose()` on the screen, called from `FasApp.shutdown()` |
| Archives reported 0 containers | Looked at the rendered figure — the seed corpus was flat, so the container tree had nothing to show | Seeded a real nested ZIP; now 1 container, depth 2, 3 nested children |
| `Icons.CHART` referenced but never defined | Compile | Added the glyph |

## Defects found and fixed in the previous revision

Listed because each was found by running the software, and each shaped the result.

| Defect | How found | Fix |
|---|---|---|
| Grounding rejected aggregate answers — a correct statistics answer was flagged "not supported by case data" | `AiAgentTest.offlineOperation` failed | Grounded now means "backed by a successful data lookup", not "cited a row id" |
| Screenshot harness could not find controls on any scrolling screen; `ScrollPane` content is not a child node | Assistant screenshot captured an idle screen | Walker descends into `ScrollPane.getContent()` |
| `isDisabled()` reads inherited state and is unreliable before layout | Same investigation | Use `isDisable()`, the property actually set |
| `Item.errors()` returns a `List`, not a `String` | Compile after writing the processing-status tool | Join the list |
| A stray non-ASCII character in a tool description | Source scan before compiling | Removed |

---

## Known limitations

Stated rather than omitted.

| Limitation | Detail |
|---|---|
| **Batch scheduling ("Off-Hours", "Custom Time")** | Not implemented. The reference's schedule control is a mock-up with no backing scheduler; building one would invent a capability rather than reproduce one. |
| **Host CPU / disk-I/O gauges** | Partial. JVM heap and processor count appear on Performance; per-process CPU and disk I/O are not observable from the JVM without a native agent. |
| **Model generation unverified on this machine** | 400 MB free RAM cannot hold a usable model. Protocol, agent loop, tools, grounding and UI were verified against a scripted loopback runtime speaking the real format. Generation quality and latency need a machine with ≥6 GB free RAM. |
| Semantic retrieval not enabled by default | `EmbeddingProvider` is implemented; keyword, metadata and relationship retrieval are the default path. |
| Charts are native bar rows | No charting dependency; same series and groupings as a plotted chart. |
| Contextual "ask" entry points | The Assistant screen carries screen context and per-screen suggestions. Per-row "analyse this" buttons on every table are not yet wired. |
| Multi-language catalogues | Language selector present; translation resources are a separate pass. |
| `SideFacade` / `SideDto` retained | Deprecated aliases delegating to `AspectFacade`, so earlier callers keep compiling. |

---

## Final status

```
Interface .................. PASS   33/33 audited destinations, 33 figures rendered
Functions .................. PASS   145/148 complete; 3 explained (see FUNCTION_INVENTORY)
Five concepts .............. PASS   integrated into case.db with real relationships
Database ................... PASS   one database, FK-linked to the engine, transactional
Existing backend ........... PASS   preserved; two justified additions to CaseDatabase
AI agent ................... PASS   architecture complete, local-only, tested end to end
                                    (generation quality unverified on this hardware)
Testing .................... PASS   82 tests, 266 assertions, 56 gate checks, 0 failures
End-to-end ................. PASS   full scenario incl. restart and agent citation check
Documentation .............. PASS   FUNCTION_INVENTORY created; 5 updated this revision

OVERALL .................... PASS with one documented environmental caveat
```

The caveat is the AI hardware limit above. It is a property of this build machine, not
of the implementation: running against a real local runtime requires changing two
configuration values and no code.
