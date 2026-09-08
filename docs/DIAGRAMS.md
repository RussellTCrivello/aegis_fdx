# Diagrams

All diagrams describe the **Java application as built**. Nothing here implies another
runtime, service or database exists inside it.

Generated: 2026-09-08

---

## 1. System architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            JAVA APPLICATION                              │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  PRESENTATION            JavaFX 21                                 │  │
│  │                                                                    │  │
│  │  FasApp (shell: sidebar · topbar · content · status bar)           │  │
│  │  15 Screen implementations · Fas components · Icons · fas.css      │  │
│  │                                                                    │  │
│  │  AegisApp (original review interface, unchanged, still launchable) │  │
│  └────────────────────────────┬───────────────────────────────────────┘  │
│                               │  typed DTOs, enums, FacadeException      │
│  ┌────────────────────────────▼───────────────────────────────────────┐  │
│  │  INTERFACE LAYER         com.aegis.fdx.facade                      │  │
│  │                                                                    │  │
│  │  AegisFacades  ─ entry point                                       │  │
│  │  SourceFacade · AspectFacade · CategoryFacade · KeywordFacade      │  │
│  │  ContentFacade · SearchFacade · PreviewFacade · DashboardFacade    │  │
│  │  FileProcessingFacade · ExportFacade · ImportFacade                │  │
│  │  NotificationFacade · SearchHistoryFacade                          │  │
│  │                                                                    │  │
│  │  SearchCriteria · SourceDraft · SortField · SortOrder · FileState  │  │
│  └───────────────┬────────────────────────────────┬───────────────────┘  │
│                  │ adapts                         │ adapts               │
│  ┌───────────────▼──────────────────┐  ┌──────────▼───────────────────┐  │
│  │  PROCESSING ENGINE  (unchanged)  │  │  INTEGRATED CONCEPTS  (new)  │  │
│  │                                  │  │                              │  │
│  │  LiveCase        IngestPipeline  │  │  Sources    Categories       │  │
│  │  Analyzer SPI    OcrStage        │  │  Aspects    Keywords         │  │
│  │  LuceneIndex     Exporter        │  │  Contents                    │  │
│  │  CaseDatabase    IntegrityVerif. │  │  CorpusDatabase / CorpusSchema│ │
│  └───────────────┬──────────────────┘  └──────────┬───────────────────┘  │
│                  │                                │                      │
│                  └────────────┬───────────────────┘                      │
│                               ▼                                          │
│              ┌────────────────────────────────────┐                      │
│              │   ONE CASE FOLDER, ONE DATABASE    │                      │
│              │                                    │                      │
│              │   <case>/db/case.db   (SQLite WAL) │                      │
│              │     engine:  item · queue · audit  │                      │
│              │     added:   source · aspect ·     │                      │
│              │              category · keyword ·  │                      │
│              │              word · hash · path ·  │                      │
│              │              content · alert       │                      │
│              │                                    │                      │
│              │   <case>/index/  Lucene            │                      │
│              │   <case>/text/   extracted text    │                      │
│              │   <case>/data/   stored payloads   │                      │
│              └────────────────────────────────────┘                      │
└──────────────────────────────────────────────────────────────────────────┘
```

Both halves of the schema are created on the **same JDBC connection**, so one
transaction covers both and a query can join across them.

---

## 2. Entity relationships

```
                    ┌──────────────┐
                    │    source    │
                    │──────────────│
                    │ id           │
                    │ name  UNIQUE │
                    │ job          │
                    │ importance   │
                    │ country/city │
                    │ access_status│
                    │ category_id ─┼────────┐
                    └──────┬───────┘        │
                           │ 1              │
                           │                │
                           │ N              │
   ┌──────────────┐  ┌─────▼────────┐       │
   │    aspect    │  │     path     │       │
   │──────────────│  │──────────────│       │
   │ id           │1 │ id           │       │
   │ name  UNIQUE ├──┤ file_name    │       │
   │ importance   │ N│ file_path    │       │
   │ date_creation│  │ file_size    │       │
   └──────────────┘  │ file_type    │       │
                     │ file_status  │       │
   ┌──────────────┐  │ source_id    │       │
   │     hash     │1 │ aspect_id    │       │
   │──────────────├──┤ hash_id      │       │
   │ id           │ N│ element_id ──┼───┐   │
   │ hash_value   │  └──┬────┬──────┘   │   │
   │ source_id    │     │1   │N         │   │
   └──────────────┘     │    │          │   │
                        │    │          │   │
              ┌─────────▼─┐  │          │   │
              │  content  │  │          │   │
              │───────────│  │          │   │
              │ id        │  │          │   │
              │ data      │  │          │   │
              │ date      │  │          │   │
              │ path_id   │  │          │   │
              └───────────┘  │          │   │
                             │          │   │
        ┌────────────────────┴──────┐   │   │
        │                           │   │   │
   ┌────▼──────────┐      ┌─────────▼─┐ │   │
   │ path_category │      │path_keyword│ │  │
   │───────────────│      │────────────│ │  │
   │ path_id       │      │ path_id    │ │  │
   │ category_id ──┼──┐   │ keyword_id │ │  │
   └───────────────┘  │   │ hits       │ │  │
                      │   └──────┬─────┘ │  │
                      │          │       │  │
                 ┌────▼──────────▼───┐   │  │
                 │     category      │◄──┼──┘
                 │───────────────────│   │
                 │ id                │   │
                 │ word_id  UNIQUE ──┼─┐ │
                 └────┬──────────────┘ │ │
                      │1               │ │
                      │                │ │
                      │N               │ │
                 ┌────▼──────┐   ┌─────▼─▼────┐
                 │  keyword  │   │    word    │
                 │───────────│   │────────────│
                 │ id        │   │ id         │
                 │ phrase UQ │   │ word UNIQUE│
                 │ category  │   └─────┬──────┘
                 └───────────┘         │N
                                       │
                              ┌────────▼────────┐
                              │  word_category  │
                              │─────────────────│
                              │ word_id         │
                              │ category_id     │
                              └─────────────────┘

   ╔═══════════════════════════════════════════════════════╗
   ║  ENGINE TABLES (unchanged, same database)             ║
   ║                                                       ║
   ║   item ◄──────── path.element_id  (FK, ON DELETE      ║
   ║   │              CASCADE)                             ║
   ║   ├─ item_tag                                         ║
   ║   ├─ item_meta                                        ║
   ║   queue                                               ║
   ║   audit                                               ║
   ║   setting                                             ║
   ╚═══════════════════════════════════════════════════════╝
```

`path.element_id → item.id` is the join between the added concepts and everything the
engine already knew. It is a real foreign key, not a loose string.

---

## 3. Navigation

```
  File Analysis System
  │
  ├─ OVERVIEW
  │   ├─ Dashboard ............ counters, breakdowns, processing status
  │   ├─ Analysis ............. classification, path analysis, duplicates
  │   ├─ Charts ............... 6 live distributions, click to drill through
  │   ├─ Comprehensive ........ every dimension under one combined filter
  │   ├─ Path Analysis ........ directory tree with rolled-up totals
  │   ├─ Search ............... query + filters → results → preview
  │   └─ Advanced Search ...... field-by-field query builder
  │
  ├─ ENTITIES
  │   ├─ Sources .............. list ──► Source Detail ──► Relationships
  │   ├─ Aspects .............. list ──► Aspect Detail ──► Relationships
  │   ├─ Email Words .......... extracted email elements
  │   ├─ Keywords ............. list ──► Keyword Detail
  │   ├─ Words ................ list ──► Word Detail
  │   └─ Categories ........... list ⇄ linked words
  │
  ├─ FILES
  │   ├─ Upload Files ......... source+aspect → drop → process → results
  │   ├─ File Library ......... list ──► File Detail ──► Full Content
  │   ├─ Archives ............. container nesting from the engine
  │   └─ Import / Export ...... CSV·XLSX·JSON·backup / CSV·settings
  │
  ├─ ASSISTANT
  │   └─ Assistant ............ local agent over controlled tools
  │
  └─ SYSTEM
      ├─ Saved Searches ....... saved list ⇄ history
      ├─ Notifications ........ stats + list
      ├─ Processing ........... live queue, workers, outcomes
      ├─ Errors ............... failures by status and cause
      ├─ Performance .......... storage, index, timed query
      ├─ Setup ................ case status, integrity verification
      └─ Settings ............. identity, theme, processing options

  Drill-through is handled by Router; each detail destination implements Detail
  and receives its record id before onShow(). A back stack supports returning.
```

## 4. Ingest data flow

```
 User drops a folder on Upload Files
             │
             ▼
 UploadScreen ── validates source + aspect are chosen (both mandatory)
             │
             ▼
 FileProcessingFacade.processFolder(path)          [interface layer]
             │
             ▼
 LiveCase.startIngest(path, custodian)             [EXISTING ENGINE]
             │
             ├─► walk source tree (read-only)
             ├─► Analyzer SPI: sniff → extract text + metadata
             ├─► expand containers (archives, mailboxes) to depth
             ├─► MD5 + SHA-256 per element
             ├─► OCR stage (if enabled)
             ├─► LuceneIndex.addDocument
             └─► CaseDatabase: item + queue rows
             │
             ▼
 ProcessingResultDto per file  ── every file, including failures
             │
             ▼
 ContentFacade.registerIngestedItems(sourceId, aspectId)
             │
             ├─► hash    ← element SHA-256
             ├─► path    ← name, size, type, dates, element_id FK,
             │             attributed to source + aspect
             └─► content ← extracted text from <case>/text/
             │
             ▼
 File Library, Dashboard and Analysis now describe the same files
 the engine processed — one dataset, two views.
```

---

## 5. Search sequence

```
 User          SearchScreen      SearchFacade        LiveCase        Lucene
  │                 │                 │                 │              │
  │─ type query ───►│                 │                 │              │
  │─ set filters ──►│                 │                 │              │
  │─ click Search ─►│                 │                 │              │
  │                 │─ build ────────►│                 │              │
  │                 │  SearchCriteria │                 │              │
  │                 │                 │─ resolve source │              │
  │                 │                 │  and aspect ids │              │
  │                 │                 │  to filters     │              │
  │                 │                 │─ searchNow ────►│              │
  │                 │                 │                 │─ parse ─────►│
  │                 │                 │                 │  query       │
  │                 │                 │                 │◄─ TopDocs ───│
  │                 │                 │◄─ SearchPage ───│              │
  │                 │                 │                 │              │
  │                 │                 │─ sort + page    │              │
  │                 │                 │  (full pool if  │              │
  │                 │                 │   offset > 0)   │              │
  │                 │◄─ Page<Result> ─│                 │              │
  │◄─ table + count │                 │                 │              │
  │                 │─ record history ►                 │              │
  │                 │                 │                 │              │
  │─ select row ───►│                 │                 │              │
  │                 │─ getPreview ───►│                 │              │
  │◄─ text + hashes │                 │                 │              │
```

An unparseable query raises `FacadeException(VALIDATION)` and the screen shows the
reason inline — it never silently returns an empty table.

---

## 6. Processing state machine

```
                  ┌─────────┐
      enqueued ──►│ PENDING │
                  └────┬────┘
                       │ worker picks up
                  ┌────▼───────┐
                  │ PROCESSING │
                  └────┬───────┘
        ┌──────────────┼──────────────┬────────────────┐
        ▼              ▼              ▼                ▼
   ┌─────────┐   ┌──────────┐   ┌─────────┐   ┌──────────────┐
   │ INDEXED │   │  ERROR   │   │ LOCKED  │   │ UNSUPPORTED  │
   └─────────┘   └──────────┘   └─────────┘   └──────────────┘
   searchable    reason kept    needs a       recognised but
                 and reported   password      not extractable

   Every terminal state still produces:
     · an item row          · a hash
     · a queue record       · a registry path (once registered)

   Nothing is dropped. A crash resumes from the queue.
```

Review state is tracked separately on the registry (`Unread` / `Read`), because what
the pipeline did and what a reviewer did are different questions.

---

## 7. Layer responsibilities

```
┌──────────────┬────────────────────────────────┬──────────────────────────┐
│ Layer        │ Owns                           │ Must not                 │
├──────────────┼────────────────────────────────┼──────────────────────────┤
│ Screens      │ Layout, interaction, wording   │ Touch SQL or Lucene      │
│ Facades      │ Validation, DTOs, translation  │ Contain business rules   │
│              │ of engine types                │ that belong to the engine│
│ Engine       │ Reading, extraction, hashing,  │ Know about screens       │
│              │ OCR, indexing, case lifecycle  │                          │
│ Store        │ Schema, queries, transactions  │ Format for display       │
└──────────────┴────────────────────────────────┴──────────────────────────┘
```

The interface layer never blocks the FX thread on I/O; processing runs on a background
thread and results are published back with `Platform.runLater`.

---

## 8. AI agent architecture

```
  Operator
     │
     ▼
  AgentScreen (JavaFX)          contextual suggestions per screen
     │   runs off the FX thread, publishes with Platform.runLater
     ▼
  AgentService                  availability, history, tool registration
     │
     ▼
  AgentOrchestrator             the loop, bounded by a step budget
     │
     ├──────────────► LocalModelProvider  (interface — replaceable)
     │                     │
     │                     ▼
     │                HttpLocalModelProvider
     │                     │  loopback HTTP only; remote refused
     │                     ▼
     │                local inference runtime + model file
     │
     └──────────────► Tool gateway (allow-list)
                           │
                           ▼
                      AgentTool implementations
                           │
                           ▼
                      Existing Java facades
                           │
                           ▼
                      Engine + case.db
```

The model has no route into the application except a registered tool. There is no
shell, SQL, filesystem or network tool.

---

## 9. AI search flow

```
 Operator      AgentScreen   Orchestrator    SearchTool    SearchFacade    Lucene
    │               │              │              │             │            │
    │─ question ───►│              │              │             │            │
    │               │─ ask ───────►│              │             │            │
    │               │              │─ prompt ────────────────► local model   │
    │               │              │◄─ {"tool":"search_items"} ──────────────│
    │               │              │─ validate    │             │            │
    │               │              │─ execute ───►│             │            │
    │               │              │              │─ criteria ─►│            │
    │               │              │              │             │─ query ───►│
    │               │              │              │             │◄─ TopDocs ─│
    │               │              │◄─ result + evidence ids ───│            │
    │               │              │─ observation ───────────► local model   │
    │               │              │◄─ prose answer ────────────────────────│
    │               │◄─ activity ──│              │             │            │
    │◄─ answer +    │              │              │             │            │
    │   record chips│              │              │             │            │
    │   + trace     │              │              │             │            │
```

If no tool returned data, the answer is annotated as unsupported rather than presented
as fact.

---

## 10. AI permission model

```
                    ┌──────────────────────────┐
   question ───────►│  AgentContext            │
                    │  allowMutations = false  │ ◄── default
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼─────────────┐
                    │  tool registration       │
                    │  read-only tools only    │
                    └────────────┬─────────────┘
                                 │
                         model asks for a
                         write tool → refused,
                         reported as an observation

   operator ticks "Allow the assistant to change data"
                                 │
                    ┌────────────▼─────────────┐
                    │  allowMutations = true   │
                    │  + classify_file         │
                    │  + set_review_state      │
                    └──────────────────────────┘
                    (review annotations only —
                     never original material)
```
