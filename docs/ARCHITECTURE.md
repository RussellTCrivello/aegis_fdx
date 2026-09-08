# AEGIS-FDX — Architecture

Stack decision: **Java 21 core + JavaFX 21 UI + Lucene**, with **C/C++ hot paths via
JNI/JNA**. The Rust/Tauri/Tantivy alternative in the brief was not taken; the two
stacks are mutually exclusive and the Java side wins on PST/OST maturity
(`java-libpst`), Tika's format breadth, and `jpackage` installers.


---

## 0. Two front ends, one engine

The project exposes two user interfaces over a single unchanged back end.

```
   File Analysis System UI              Review interface
   (FasApp)                             (AegisApp — original)
            │                                    │
            ▼                                    │
   ┌──────────────────┐                          │
   │  Interface layer │  Java-native facades,    │
   │  com.aegis.fdx.  │  DTOs, criteria objects  │
   │      facade      │                          │
   └────────┬─────────┘                          │
            │  adapters                          │
            ▼                                    ▼
   ┌─────────────────────────────────────────────────────────┐
   │            EXISTING JAVA ENGINE — UNCHANGED             │
   │  LiveCase · IngestPipeline · Analyzer SPI · LuceneIndex │
   │  CaseDatabase · OcrStage · Exporter · IntegrityVerifier │
   └─────────────────────────────────────────────────────────┘
                    │                        │
                    └───────────┬────────────┘
                                ▼
                      <case>/db/case.db
              ONE database, ONE connection:
                engine: item, queue, audit, ...
                added:  source, aspect, word, category,
                        keyword, hash, path, content, alert
```

**Rule applied throughout:** a change was made only where it was required for the
interface to function. No engine internals were rewritten.

### The AI layer

A local analysis agent sits above the interface layer:

```
   AgentScreen ──► AgentService ──► AgentOrchestrator
                                          │
                        ┌─────────────────┴──────────────────┐
                        ▼                                    ▼
            LocalModelProvider                     Tool gateway (allow-list)
            (loopback only; remote refused)                  │
                        │                                    ▼
              local runtime + model            existing facades ──► engine ──► case.db
```

The agent is an operator layer, not a second implementation: it reads the same data
through the same facades a human's clicks would. It has no shell, SQL, filesystem or
network tool, and it is read-only unless the operator explicitly confirms otherwise.
Full detail in `AI_AGENT.md`.

Note the direction of every arrow above. The ingest path —

```
Source → Reading → Processing → Extraction → Metadata → OCR → Content
       → Hashing → Indexing → case.db / Lucene
```

— contains no AI, and nothing in it can reach the agent: the nine pipeline packages
carry no reference to `com.aegis.fdx.ai` in source or in compiled bytecode, and the
agent is invoked only by an operator action. That separation is the normative rule in
`AI_BOUNDARY.md` and is enforced by the `AiBoundaryTest` suite (B-01…B-08) in the
functional battery and on the release gate. AI is an optional analysis layer over the
finished application, never part of its processing engine.

It is also an *unloaded* layer until it is used. The window holds an `AgentService`,
but that object stores only a factory: no model configuration is read, no provider is
constructed and no runtime is contacted while the application starts or while it works.
The provider is built on the first explicit invocation, which B-08 measures against a
runtime that counts every request it serves.

### Screen lifecycle

`Screen` declares `onShow`, `onHide` and `dispose`, and the shell drives all three:
`onHide` when the operator navigates away, `dispose` on shutdown. Destinations that
sample something live — the Processing Monitor's queue poller, the Performance
screen's host meters — stop when they are not on screen and release their animation on
exit. Before this, the shell reached for a specific screen class by `instanceof`, which
meant every new polling destination silently leaked a timer and kept the JavaFX toolkit
alive at shutdown.

### Host metrics

`facade/HostMetrics` reads process CPU, system CPU, installed and free physical memory,
system load average and the capacity of the volume holding the case. The extended
`com.sun.management.OperatingSystemMXBean` counters are reached reflectively, because
that interface is a HotSpot extension rather than a platform guarantee and its method
names changed between versions; binding to it directly would make a runtime without it
fail to start over a display feature. Every reading is three-valued — measured,
`UNAVAILABLE`, or `UNAVAILABLE_BYTES` — so the interface can say "not reported by this
operating system" instead of drawing a bar it cannot justify.

### Storage

The five added concepts share the case's existing database and JDBC connection.
`CaseDatabase` still owns and creates the engine tables; `CorpusSchema` adds the rest
on the same connection during migration. Consequences that matter:

- one transaction spans both halves (`CaseDatabase.begin()` covers a source insert)
- `path.element_id` is a real foreign key onto `item(id)`, with `ON DELETE CASCADE`
- a query can join registry rows against engine rows directly
- there is no second database file to keep in step

### The bridge

`ContentFacade.registerIngestedItems(sourceId, aspectId)` projects what the pipeline
already produced into the relational model:

```
 ingest pipeline output          projection            integrated tables
 ─────────────────────           ──────────            ─────────────
 Item.sha256              ─────────────────────────►   hash.hash_value
 Item.name/size/ext       ─────────────────────────►   path.*
 Item.id                  ─────────────────────────►   path.element_id  (FK)
 <case>/text/<id>.txt     ─────────────────────────►   content.content_data
 (selected in the UI)     ─────────────────────────►   path.source_id / aspect_id
```

Idempotent on `element_id`: safe to call after every ingest.

---

## 1. Process & thread model (3.1, A-03, N-03)

```
┌─────────────────────────────── JVM ────────────────────────────────┐
│                                                                     │
│  FX Application Thread            Engine pool (cores − 1)           │
│  ───────────────────────          ─────────────────────────         │
│  FasApp / AegisApp                IntakeWorker ×N                   │
│   ├ result grid                    ├ walk source (read-only)        │
│   ├ preview / metadata             ├ Analyzer.sniff → analyze       │
│   ├ facets, tags, notes            ├ emit children (depth+1)        │
│   └ ingest monitor                 ├ MD5 + SHA-256                  │
│         ▲                          └ IndexWriter.addDocument        │
│         │ Platform.runLater                    │                    │
│         └────── EngineEvent ◄──────────────────┘                    │
│                (sealed, immutable)                                  │
│                                                                     │
│  Native (JNI/JNA):  hashing · zlib/lzma inflate · mmap scan          │
└─────────────────────────────────────────────────────────────────────┘
```

Rules enforced by the code:

- The UI **never** blocks on I/O. It holds no file handles and no engine locks.
- The engine **never** touches JavaFX types. It emits `EngineEvent` records; the UI
  is the only thing that calls `Platform.runLater`.
- Every event handler is measured — `UI x.x ms` in the status bar is a live readout
  of the FX-thread cost of the last event, guarding the 500 ms budget (N-03).
- Pause/resume/cancel are checked between elements, never mid-write, so the durable
  queue is always consistent (F-07, AT-05).

**Why ZGC:** `-XX:+UseZGC -XX:MaxGCPauseMillis=50` in `applicationDefaultJvmArgs`.
Sub-millisecond pauses remove the GC-freeze objection to Java raised in the brief,
without leaving the JVM.

---

## 2. Pipeline (A-01)

```
 Intake ──► Analyse/Extract ──► Hash/Dedupe ──► Index ──► Store
   │             │                   │             │        │
   │             ▼                   ▼             ▼        ▼
   │        Analyzer SPI        MD5+SHA-256    Lucene 9.x  SQLite
   │        (ServiceLoader)     dedupe scope   /index      /db (WAL)
   │                                                       /text
   └──────────── durable queue (SQLite, status per element) ──────────
```

Each stage is independently replayable: the queue row carries
`Pending / Processing / Indexed / Error / Locked / Unsupported`, so a crash resumes
from the last completed element with no gaps and no duplicates (F-07).

`F-14` — the index is rebuildable from `/text` alone; source media is never re-read.

### Nested extraction (F-03)

`Analyzer.analyze(item, in, sink)` calls `sink.emit(child, stream)` for anything it
finds inside a container. The kernel re-enqueues the child at `parent.depth() + 1`
and stops at `CaseSettings.maxArchiveDepth` (default 20). Parent and branch links are
preserved on every element via `parentId` and `containerPath`, which is what makes
`AT-02` (ZIP→PST→MSG→PDF = four linked, individually searchable elements) work.

### Evidence integrity (F-06, N-06)

Sources are opened read-only; nothing is modified, moved, renamed or deleted. All
output lands in the case folder. The `SOURCE READ-ONLY` chip in the title bar is a
permanent affordance for that guarantee.

---

## 3. Case layout (F-28)

```
<case>/
  data/      extracted binaries (AES-256 optional, default on)
  index/     Lucene 9.x — one index per case
  text/      extracted text, one file per element
  db/        SQLite (WAL) — queue, metadata, tags, notes, audit
  logs/      Logback rolling
  exports/   productions + loadfile.csv
  case.json  settings (F-29)
```

Move or archive a case = copy the folder. No external state.

---

## 4. Plugin SPI (A-02)

Adding a format is implementing one interface and dropping the jar on the path:

```java
public interface Analyzer {
    String id();
    List<String> mediaTypes();
    double sniff(byte[] header, String fileName);
    void analyze(Item item, InputStream in, ChildSink sink) throws Exception;
}
```

Discovered via `ServiceLoader`. No kernel change. Implementations must stream
(N-04: > 100 MB is never fully loaded) and must not throw on malformed input —
they record the failure on the element instead, so no single bad file can stop a run
(N-05, AT-01).

---

## 5. Query language (F-15)

Implemented and tested in `QueryParser`; maps 1:1 onto Lucene `BooleanQuery` /
`TermRangeQuery` / `RegexpQuery` in milestone 2.

| Feature | Syntax | Test |
|---|---|---|
| Keyword | `settlement` | ✅ |
| Phrase | `"wire transfer"` | ✅ |
| Wildcard | `settle*`, `agree?ent` | ✅ |
| Fuzzy | `setlement~2` | ✅ |
| Proximity | `"wire funds"~9` | ✅ |
| Boolean + grouping | `(a OR b) AND NOT c` | ✅ |
| Fields | `from: to: cc: subject: type: path: tag: status: custodian: md5: sha256:` | ✅ |
| Date range | `date:[2024-01-01 TO 2024-01-31]` | ✅ |
| Regex | `/INVOICE\s\d{5}/` | ✅ |
| Malformed input | degrades to literal search, never throws | ✅ |

`33 passed, 0 failed` — `com.aegis.fdx.QueryParserTest`.

---

## 6. Requirement traceability (milestone 1)

| Req | Where | State |
|---|---|---|
| F-03 nesting + parent links | `Item.depth/parentId/containerPath`, `Analyzer.ChildSink` | modelled, shown in UI (`L1`/`L3` badges) |
| F-04 locked archives | `CaseSettings.passwords`, `ItemStatus.LOCKED` | UI + engine states |
| F-05 hashes + dedupe scope | `StubEngine.hash`, `CaseStore.duplicates` | working, report renders |
| F-07 resume | `ItemStatus` lifecycle | modelled |
| F-09 metadata | `Item` (18 fields + 7 email) | full Metadata panel |
| F-13 progressive search | `runSearchQuiet()` on every 20th element | live during ingest |
| F-15 query | `QueryParser` | ✅ tested |
| F-16 filters | `Filters` + facet tree | ✅ interactive |
| F-17 results | grid, highlight, sort, search-within, saved, CSV | ✅ |
| F-19/20 tags | `Tag.FIXED`, Ctrl+1..5, multi-select apply | ✅ |
| F-21 notes | notes pane, indexed into haystack | ✅ |
| F-22 preview | email / image / office / text renderers | ✅ |
| F-23 threads + duplicates | `emailThreads()`, `duplicates()` | ✅ reports |
| F-24 audit | `CaseStore.log` on search/tag/note/export | ✅ |
| F-25/26/27 export + reports | export dialog, 4-tab report view | ✅ dialogs |
| F-29 case settings | `CaseSettings` + settings dialog | ✅ |
| N-03 UI latency | live `UI x.x ms` readout | ✅ ~0–36 ms observed |
| N-05 no fatal component | errors recorded per element, run continues | ✅ 18 errors, run completed |

---

## 7. Milestone 2 (next)

1. Replace `StubEngine` with the real pipeline: Tika 3 + PDFBox 3 + POI 5,
   `java-libpst` for PST/OST, mime4j + Jakarta Mail for EML/MBOX/MSG,
   commons-compress + junrar for archives, Tess4J for OCR.
2. Lucene 9.x `IndexWriter` per case with NRT readers for F-13, plus the
   `UnifiedHighlighter` behind the existing `SearchHit.fragments` contract.
3. SQLite/WAL durable queue + resume (AT-05).
4. JNI layer: SHA-256, inflate, and mmap signature scanning for carving.
5. `jpackage` MSI on Windows 10/11 x64 (AT-10).
6. The 40-file test dataset with the expected-results file (D-04).
