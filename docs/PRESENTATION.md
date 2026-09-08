# File Analysis System — Project Presentation

Version 1.3 · 2026-09-08

---

## Slide 1 — What this is

A **Java desktop application** for reading, processing, extracting, indexing,
searching and organising files of any type.

- Java 21 · JavaFX 21 · Lucene 9.11 · SQLite
- Fully offline: no network calls, no cloud services
- Self-contained cases: one folder holds everything about one matter

This release adds a new interface and five integrated concepts on top of the existing
processing engine. **The engine itself is unchanged.**

---

## Slide 2 — What changed in this release

| Area | Change |
|---|---|
| **Interface** | Full destination surface from a reference audit: 25 navigable + 7 drill-through |
| **Sources** | Who or what material came from — new, integrated |
| **Aspects** | The party or grouping material belongs to — new, integrated |
| **Categories** | Named groupings of vocabulary — new, integrated |
| **Keywords** | Phrases of interest within a category — new, integrated |
| **Contents** | Extracted text held against a file registry — new, integrated |
| **Assistant** | New local AI agent: multi-step investigation over case data, fully offline |
| **Engine** | Untouched. Reading, extraction, hashing, OCR, indexing and search all unchanged |

---

## Slide 3 — The interface

```
┌────────────────┬─────────────────────────────────────────────────┐
│ FILE ANALYSIS  │  Dashboard                            [Refresh] │
│ Document Intel │  Home / Dashboard                               │
├────────────────┼─────────────────────────────────────────────────┤
│ OVERVIEW       │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐    │
│  Dashboard  ◄──┤  │   7    │ │   7    │ │   4    │ │   11   │    │
│  Analysis      │  │ Files  │ │Processed│ │ Types │ │ Words  │    │
│  Search        │  └────────┘ └────────┘ └────────┘ └────────┘    │
│                │                                                 │
│ ENTITIES       │  ┌──────────────┐ ┌──────────┐ ┌─────────────┐  │
│  Sources       │  │ File Types   │ │ Sources  │ │ Processing  │  │
│  Aspects       │  │ txt  ████ 4  │ │ Acme ██ 7│ │ Indexed   7 │  │
│  Email Words   │  │ csv  █    1  │ │          │ │ Errors    0 │  │
│  Keywords      │  │ eml  █    1  │ │          │ │ Locked    0 │  │
│  Words         │  │ html █    1  │ │          │ │ Unsupp.   0 │  │
│  Categories    │  └──────────────┘ └──────────┘ └─────────────┘  │
│                │                                                 │
│ FILES          │                                                 │
│  Upload Files  │                                                 │
│  File Library  │                                                 │
│  Import/Export │                                                 │
│                │                                                 │
│ SYSTEM         │                                                 │
│  Saved Searches│                                                 │
│  Notifications │                                                 │
│  Settings      │                                                 │
├────────────────┴─────────────────────────────────────────────────┤
│ Dashboard loaded in 7 ms          Offline · all processing local │
└──────────────────────────────────────────────────────────────────┘
```

Fifteen screens, four navigation groups, one visual language.
Full walkthrough with figures: **[UI_GUIDE.md](UI_GUIDE.md)**

---

## Slide 4 — Core workflow

```
  1. Define        2. Ingest         3. Register       4. Work
  ──────────       ──────────        ────────────      ────────
  Source     ───►  Drop files   ───► Files linked ───► Search
  Aspect           Pipeline runs     to source,        Filter
  Categories       (unchanged)       aspect, hash,     Preview
  Keywords         Every file        content           Classify
                   accounted for                       Export
```

Source and aspect are **mandatory** before processing starts — every file is
attributable from the moment it enters the system.

---

## Slide 5 — The processing engine (unchanged)

What it does, and still does exactly as before:

- **Reads** files of many types, including archives and mailboxes, recursively
- **Extracts** text and metadata through a pluggable analyzer interface
- **OCRs** images and scanned documents when enabled
- **Hashes** every element with MD5 and SHA-256
- **Indexes** into Lucene with a full query grammar
- **Records** everything in a durable queue that survives a crash

Guarantees preserved:

- Source evidence is opened read-only
- No file is silently dropped — failures are recorded with a reason
- Duplicates are marked, never deleted
- A crash resumes from the queue

Reference corpus still processes to the same result: **49 indexed · 3 error ·
1 locked · 5 unsupported**.

---

## Slide 6 — Database integration

```
                  <case>/db/case.db      ← ONE database
   ┌───────────────────────────────────────────────────┐
   │  ENGINE (existing)          INTEGRATED (new)      │
   │                                                   │
   │  item ◄──── FK ──────────── path                  │
   │  item_tag                   ├─ source             │
   │  item_meta                  ├─ aspect             │
   │  queue                      ├─ hash               │
   │  audit                      └─ content            │
   │  setting                                          │
   │                             category ─ word       │
   │                             keyword              │
   │                             path_category         │
   │                             path_keyword          │
   │                             alert                 │
   └───────────────────────────────────────────────────┘
```

Not a second database. Same file, same connection, same transaction scope.

This is enforced, not just intended:

- A release-gate check fails the build if a second connection is opened
- A test lists the `db/` folder and asserts `case.db` is the only database
- A test begins a transaction, inserts a source, rolls back, and confirms it is gone
- A test deletes an element and confirms its registry row cascades away

---

## Slide 7 — Relationships

```
   source ──┬──► path ──┬──► content
            │           │
   aspect ──┘           ├──► hash
                        │
                        ├──► path_category ──► category ──► word
                        │
                        └──► path_keyword  ──► keyword  ──► category
                                (hit count)
```

A file is attributable to its origin, its grouping, its exact bytes, its extracted
text, the categories it falls under and the keywords found in it — and, through
`element_id`, to everything the engine recorded about it.

---

## Slide 8 — Interface to backend

Every screen action reaches real functionality.

```
   User action
        │
        ▼
   JavaFX screen
        │
        ▼
   Facade  ──── validates input, converts types, wraps errors
        │
        ├──────────────► Processing engine  (search, ingest, preview)
        │
        └──────────────► Case database      (the five concepts)
                                │
                                ▼
                            Result
                                │
                                ▼
                          Screen updates
```

No mock screens. No buttons without implementations. No hard-coded demonstration
data — the screenshots in the documentation are produced by running the real
application over a real processed corpus.


---

## Slide 8a — The local assistant

Ask a question in plain language; the agent investigates and answers with its sources.

```
  "What consulting material is on this case and where did it come from?"
           │
           ▼
  1. search_items {query=consulting}   →  7 matched, 5 returned
  2. list_sources {}                   →  2 sources
  3. get_statistics {}                 →  case totals
           │
           ▼
  "Seven items mention consulting. All were collected from Acme Consulting BV
   and attributed to the Plaintiff aspect. Every item processed cleanly."

  Records consulted:
   item E-000007  item E-000002  item E-000003  item E-000006
   item E-000001  source 1  source 2
```

That is real output from a real run against the seeded case.

---

## Slide 8b — Why it is trustworthy

| Property | How |
|---|---|
| **Local only** | Loopback endpoints only; a remote URL is refused at construction |
| **Offline** | Full agent loop tested with no external network |
| **No escape hatch** | No shell, SQL, filesystem or network tool exists |
| **Read-only default** | Write tools are not registered unless the operator confirms |
| **Evidence never invented** | Answers cite real record ids; unsupported answers are flagged |
| **Auditable** | Every tool call, argument, outcome and timing is recorded |
| **Model replaceable** | Two configuration values, no code change |

Original material is never modified. The two write-capable tools annotate review
classification and read state only.

---

## Slide 9 — Verification

| Check | Result |
|---|---|
| Automated tests | **67 passing, 0 failing** |
| — engine suites (bridged) | 7 |
| — interface layer | 7 |
| — user interface | 6 |
| — integration model | 9 |
| — AI agent | 23 |
| — destination coverage | 15 |
| Standalone battery | **266 assertions, 0 failures** |
| Release gate | **52 passed · 0 failed · 3 skipped** |
| Destinations rendered from the running app | **32** |
| Reference corpus | unchanged: 49 / 3 / 1 / 5 |

Skipped items are environmental: OCR binary absent, Windows installer needs a Windows
host, performance certification needs reference hardware.

---

## Slide 10 — Defects found and fixed during this work

Reported rather than omitted, because each one shaped the result.

| Defect | How it was found |
|---|---|
| Stylesheet missing from the classpath — UI rendered unstyled | Rendering the app and looking at the output |
| Topbar spanned the sidebar, wrong layout | Same |
| Offset paging returned overlapping rows | An assertion on the second page |
| A bulk rename corrupted a SQL column key, nulling category names | A test failure, then an audit for the same mistake elsewhere — which found one more |

The last one is the reason the interface layer now has an automated audit for
camelCase keys in SQL lookups.

---

## Slide 11 — Two interfaces, one engine

| Interface | Launch | Audience |
|---|---|---|
| File Analysis System | default, or `--fas` | Analysis and corpus work |
| Review interface | `--forensic` | Evidence review workflows |

Both operate on the same case data. Neither replaces the other; the earlier interface
was preserved intact.

---

## Slide 12 — Documentation set

| Document | Contents |
|---|---|
| `UI_GUIDE.md` | Every screen, with rendered figures |
| `INTERFACE_INVENTORY.md` | Verification record: screen → facade → backend → test |
| `DIAGRAMS.md` | Architecture, ERD, navigation, data flow, sequences |
| `ARCHITECTURE.md` | Layering and design decisions |
| `USER_MANUAL.md` | Task-oriented guide |
| `BUILD.md` | Building, running, regenerating screenshots |
| `PERFORMANCE.md` | Benchmark methodology and figures |
| `FORMATS.md` | Supported formats matrix |
| `DEPENDENCY_REPORT.md` | Third-party licences |
| `AI_AGENT.md` | Local model, agent, tools, safety, offline operation |
| `VERIFICATION_REPORT.md` | Final verification with honest limitations |
