# Reference Audit — Complete Destination Inventory

Audit date: 2026-09-08
Reference: `RussellTCrivello/file_analysis` @ `d2975c2`
Method: enumerated every `render_template(...)` target, every page route in
`Api/routes/` and `apps/web/app.py`, and every template under `templates/`.

**This audit supersedes the earlier 16-screen scope, which was an assumption rather
than a measurement.**

> **Reading this later:** the DONE/GAP statuses below record the state *at the time of
> the audit*, which is what makes the gap analysis meaningful. All 33 destinations have
> since been implemented. For the current per-capability position — verified, limited,
> adapted, deliberately unsupported or absent, each naming its Java and its test — see
> **`docs/COVERAGE_MATRIX.md`**, which is generated from data the build checks.

---

## 1. Headline finding

```
Reference templates                        39
  of which page destinations               33   (excludes base.html, 3 components,
                                                 404/500 error pages)
Java screens implemented before this audit 16
Genuine gap                                17 destinations
```

The previously reported "16 screens" covered the primary list views. It omitted almost
every **detail view**, **relationship view** and **analytics variant** — which is where
most of the reference application's depth lives.

---

## 2. Full destination inventory

Legend: **DONE** = implemented and verified · **GAP** = missing · **N/A** = no desktop
equivalent (justified below).

### Overview / analysis

| # | Reference destination | Route | Java screen | Status |
|---|---|---|---|---|
| 1 | `Analysis/dashboard.html` | `/` | `DashboardScreen` | DONE |
| 2 | `Analysis/charts_dashboard.html` | `/dashboard/charts` | `ChartsDashboardScreen` | **GAP** |
| 3 | `Analysis/comprehensive_dashboard.html` | `/dashboard/comprehensive` | `ComprehensiveDashboardScreen` | **GAP** |
| 4 | `Analysis/analysis_batch.html` | `/analysis/batch` | `BatchAnalysisScreen` | **GAP** |
| 5 | `Analysis/path_analysis.html` | `/analytics/path-analysis` | `PathAnalysisScreen` | **GAP** |
| 6 | `Analysis/file_classification.html` | (template) | folded into `AnalysisScreen` | DONE |

### Sources

| # | Reference destination | Route | Java screen | Status |
|---|---|---|---|---|
| 7 | `Sources/sources_list.html` | `/sources` | `SourcesScreen` | DONE |
| 8 | `Sources/source_detail.html` | `/sources/<id>` | `SourceDetailScreen` | **GAP** |
| 9 | `Sources/source_categories_keywords.html` | `/sources/<id>/categories-keywords` | `SourceRelationshipsScreen` | **GAP** |
| 10 | source add/edit forms | `/source/add`, `/sources/<id>/edit` | dialog in `SourcesScreen` | DONE (edit added) |

### Aspects (reference: "Sides")

| # | Reference destination | Route | Java screen | Status |
|---|---|---|---|---|
| 11 | `Side/sides_list.html` | `/sides` | `AspectsScreen` | DONE |
| 12 | `Side/side_detail.html` | `/sides/<id>` | `AspectDetailScreen` | **GAP** |
| 13 | `Side/side_categories_keywords.html` | `/sides/<id>/categories-keywords` | `AspectRelationshipsScreen` | **GAP** |
| 14 | side add/edit | `/side/add`, `/sides/<id>/edit` | dialog in `AspectsScreen` | DONE (edit added) |

### Vocabulary

| # | Reference destination | Route | Java screen | Status |
|---|---|---|---|---|
| 15 | `Word/Word_list.html` | `/words` | `WordsScreen` | DONE |
| 16 | `Word/Word_detail.html` | `/words/<id>` | `WordDetailScreen` | **GAP** |
| 17 | `Word/Word_add.html` | `/words/add` | dialog | DONE |
| 18 | `Category/categories_list.html` | `/categories` | `CategoriesScreen` | DONE |
| 19 | `Category/category_words.html` | `/categories/<id>/words` | pane in `CategoriesScreen` | DONE |
| 20 | `Keyword/keywords_list.html` | `/keywords` | `KeywordsScreen` | DONE |
| 21 | `Keyword/keyword_detail.html` | `/keywords/<id>` | `KeywordDetailScreen` | **GAP** |
| 22 | `Keyword/keywords_add_edit.html` | `/keywords/add` | dialog | DONE |

### Files

| # | Reference destination | Route | Java screen | Status |
|---|---|---|---|---|
| 23 | `file/files_list.html` | files list | `FileLibraryScreen` | DONE |
| 24 | `file/file_detail.html` | file detail | `FileDetailScreen` | **GAP** (was a dialog) |
| 25 | `file/full_content.html` | full content | `FullContentScreen` | **GAP** (was a dialog) |
| 26 | `file/upload.html` | `/upload` | `UploadScreen` | DONE |
| 27 | `file/File_Management_Analysis_System.html` | archives | `ArchivesScreen` | **GAP** |
| 28 | `email_words/email_words.html` | `/email-words` | `EmailWordsScreen` | DONE |

### Search

| # | Reference destination | Route | Java screen | Status |
|---|---|---|---|---|
| 29 | `Search/search.html` | `/search` | `SearchScreen` | DONE |
| 30 | `Search/search_advanced.html` | `/search/advanced` | `AdvancedSearchScreen` | **GAP** |
| 31 | `Search/search_enhanced.html` | `/search/enhanced` | folded into `SearchScreen` | DONE |
| 32 | `Search/saved_searches.html` | saved searches | `SavedSearchesScreen` | DONE |

### System

| # | Reference destination | Route | Java screen | Status |
|---|---|---|---|---|
| 33 | `Notifications/notifications.html` | `/notifications` | `NotificationsScreen` | DONE |
| 34 | `ImportExport/import_export.html` | `/import-export` | `ImportExportScreen` | DONE |
| 35 | `Settings/settings.html` | settings | `SettingsScreen` | DONE |
| 36 | `Setup/database_setup.html` | `/setup` | `SetupScreen` | **GAP** |
| 37 | error dashboard (`/api/errors/*`) | API + panel | `ErrorDashboardScreen` | **GAP** |
| 38 | `performance` (`/performance/*`) | API | `PerformanceScreen` | **GAP** |
| 39 | `concurrency/dashboard.html` | `/concurrency` | `ProcessingMonitorScreen` | **GAP** |

### Deliberately not reproduced

| Reference destination | Reason |
|---|---|
| `404.html`, `500.html` | HTTP error pages; a desktop app surfaces errors as dialogs and inline states |
| `components/*.html` | Jinja partials, not destinations |
| `/set_language/<lang>` | Handled by the Settings language selector |
| `/favicon.ico` | Web asset |
| `api/translations/*` | Translation catalogues are a separate workstream |

---

## 3. Per-destination analysis of the gaps

For each gap: what the user sees, actions, data, forms, filtering, navigation,
relationships, processing, persistence, states, and the Java backing.

### Charts Dashboard
- **Sees**: distribution charts — file types, sources, aspects, categories, keywords.
- **Actions**: filter by source/aspect/category/type; switch chart; navigate to filtered list.
- **Data**: aggregate counts from registry + engine.
- **Backend**: `DashboardFacade`, `CorpusDatabase.countPathsBy*` — all exist.
- **New Java needed**: native chart rendering (`ChartPane`), filter model.

### Comprehensive Dashboard
- **Sees**: every dimension on one page — stat tiles, per-dimension tables, filters.
- **Actions**: apply combined filters, drill through to a filtered file list.
- **Backend**: exists; needs a combined filter query.
- **New Java needed**: `DashboardFilter` criteria object + `filteredCounts`.

### Batch Analysis
- **Sees**: batch runs with progress, throughput, per-run outcome, history.
- **Actions**: start batch, pause, cancel, inspect a run.
- **Backend**: `FileProcessingFacade`, `IngestPipeline` — exist.
- **New Java needed**: persisted run history (`batch_run` table) + `BatchFacade`.

### Path Analysis
- **Sees**: directory tree of ingested material with per-folder counts and classification.
- **Actions**: expand/collapse, filter by source/aspect, drill into a folder.
- **Backend**: `path.file_path` — exists.
- **New Java needed**: `PathTreeBuilder` aggregating the registry into a tree.

### Source / Aspect detail
- **Sees**: full record, statistics, associated files, file types, categories, keywords.
- **Actions**: edit, delete, navigate to related records, open a file.
- **Backend**: facades + `CorpusDatabase` joins — exist.
- **New Java needed**: `SourceStatistics` / `AspectStatistics` aggregates.

### Source / Aspect relationships
- **Sees**: categories and keywords associated with that source/aspect, with counts.
- **Backend**: needs new joins across `path → path_category / path_keyword`.
- **New Java needed**: `categoriesForSource`, `keywordsForSource`, and aspect equivalents.

### Word / Keyword detail
- **Sees**: the term, its categories, occurrences, and the files containing it.
- **Backend**: `path_keyword` exists; word-level occurrence does not.
- **New Java needed**: `wordOccurrences` via the index.

### File detail / Full content (promoted from dialogs to destinations)
- **Sees**: complete metadata, hashes, relationships, extracted text with search hits.
- **Actions**: mark read, classify, copy text, navigate to source/aspect.
- **Backend**: `ContentFacade`, `PreviewFacade` — exist.

### Archives
- **Sees**: the archive/container view — nested items, hashes, geolocation, titles.
- **Backend**: `Item.containerPath`, `depth`, `parentId` — all exist and are unused by the UI.
- **New Java needed**: `ArchiveTreeBuilder` over container relationships.

### Advanced Search
- **Sees**: a field-by-field query builder rather than a raw expression box.
- **Backend**: `SearchCriteria`, `LuceneQueryBuilder` — exist.
- **New Java needed**: builder UI that composes `SearchCriteria`.

### Setup
- **Sees**: case/database status, schema check, integrity verification.
- **Backend**: `CaseDatabase`, `IntegrityVerifier` — exist.

### Error Dashboard
- **Sees**: processing failures by type, recent errors, recurring patterns.
- **Backend**: `ItemStatus.ERROR` + `Item.errors()` — exist, currently unsurfaced.
- **New Java needed**: `ErrorAnalysis` aggregation.

### Performance
- **Sees**: index size, query timings, storage, counts.
- **Backend**: `LuceneIndex`, `CaseFolder` — exist.

### Processing Monitor (concurrency)
- **Sees**: live worker/queue state during ingest.
- **Backend**: `IngestPipeline`, `EngineEvent`, queue counts — exist.

---

## 4. Java capabilities the reference does not have

Preserved and surfaced, per the instruction:

| Capability | Where |
|---|---|
| Forensic hashing (MD5 + SHA-256) with duplicate clusters | File detail, Analysis |
| OCR pipeline and status | Processing monitor, Settings |
| Chain-of-custody audit log | Case/Setup |
| Integrity verification | Setup |
| Container/nesting depth with parent links | Archives |
| Lucene query grammar (fuzzy, proximity, regex, field terms) | Search |
| Durable resumable ingest queue | Processing monitor |
| Item status model (Indexed/Error/Locked/Unsupported) | Error dashboard |
| Local AI agent | Assistant (secondary workstream) |

---

## 5. Correction (revision 2)

The first pass of this audit reported "17 gaps closed, 30 destination classes" and a
figure count of 32. That was **one destination short**, and the arithmetic hid it:
25 navigable + 7 drill-through counted Java classes, not reference destinations.

Two separate causes:

1. **`Analysis/analysis_batch.html` had no Java destination.** It was folded into the
   Processing Monitor. The audit of the template shows a genuinely distinct workflow —
   templates, priority, error policy, an explicit selection, and a persisted run
   history with per-file outcomes — so folding it was wrong. It is now
   `BatchAnalysisScreen` + `BatchAnalysisFacade`, with `batch_run` and
   `batch_run_item` tables in `case.db`.
2. **Three destinations existed but were never photographed** —
   `search_enhanced.html`, `category_words.html` and `side_categories_keywords.html`.
   All three worked; the screenshot harness simply did not visit them.

Full reasoning: [FUNCTION_INVENTORY.md](FUNCTION_INVENTORY.md) §1.

```
Reference destinations   33
Java destinations        33   (one per reference destination)
COMPLETE                 33
PARTIAL                   0
MISSING                   0
Figures rendered         33
```

---

## 6. Result

All 17 gaps are now implemented, verified by rendering and by test.

```
Reference page destinations                 33
Java destinations implemented               30 classes
  navigable from the sidebar                25
  reached by drill-through                   7   (details and relationships)
Deliberately not reproduced                  6   (error pages, partials, web assets)
Rendered from the running application       32 figures
```

### Gap closure

| # | Destination | Java class | Verified by |
|---|---|---|---|
| 2 | Charts Dashboard | `ChartsDashboardScreen` | `03-charts.png`, live donut/bar from real counts |
| 3 | Comprehensive Dashboard | `ComprehensiveDashboardScreen` | `combinedFilters` |
| 4 | Batch Analysis | folded into `ProcessingMonitorScreen` | `ProcessingMonitorScreen` polling |
| 5 | Path Analysis | `PathAnalysisScreen` | `directoryTree` |
| 8 | Source detail | `SourceDetailScreen` | `sourceStatistics`, `30-source-detail.png` |
| 9 | Source relationships | `RelationshipsScreen(forSource)` | `relationships` |
| 12 | Aspect detail | `AspectDetailScreen` | `aspectStatistics` |
| 13 | Aspect relationships | `RelationshipsScreen(forAspect)` | `relationships` |
| 16 | Word detail | `TermDetailScreen(WORD)` | `wordDetail` |
| 21 | Keyword detail | `TermDetailScreen(KEYWORD)` | `keywordDetail` |
| 24 | File detail | `FileDetailScreen` | `32-file-detail.png` |
| 25 | Full content | `FullContentScreen` | `33-full-content.png` |
| 27 | Archives | `ArchivesScreen` | `archiveTree`, `20-archives.png` |
| 30 | Advanced search | `AdvancedSearchScreen` | builder composes real criteria |
| 36 | Setup | `SetupScreen` | case status + integrity verification |
| 37 | Error dashboard | `ErrorDashboardScreen` | `errorReport`, `errorReportClean` |
| 38 | Performance | `PerformanceScreen` | live timed query against the index |
| 39 | Processing monitor | `ProcessingMonitorScreen` | live queue/worker state |

Also added, because a destination that cannot be edited is not complete:

| Capability | Java |
|---|---|
| Source edit | `SourceFacade.updateSource(int, SourceDraft)` |
| Aspect edit | `AspectFacade.updateAspect(int, String, double, LocalDate)` |
| Shared source form | `SourceForm` — one definition for create and edit |
| Drill-through navigation | `Router` + `Detail`, with a back stack |

### Backend added

No engine internals were rewritten. New query surface only:

- `CorpusDatabase`: 14 aggregate and relationship queries
- `AnalyticsFacade`: statistics, relationship rollups, directory tree, container tree,
  error report, combined filters
- DTOs: `EntityStatistics`, `CategoryUsage`, `KeywordUsage`, `ErrorReport`, `PathNode`
- `ChartPane`: canvas-drawn donut, bar, horizontal-bar and chip charts

### Defects found by running it

| Defect | How found |
|---|---|
| Processing monitor's INDEFINITE `Timeline` kept the toolkit alive; the app would not exit | Screenshot run hung for 10 minutes |
| `Icons.CHART` referenced but never defined | Compile |
| Archives showed 0 containers | Looked at the render; the seed corpus was flat. Added a real nested ZIP — now 1 container, depth 2 |

---

## 7. Plan

Ordered by dependency: shared infrastructure first, then destinations.

1. Backend additions — statistics aggregates, relationship joins, tree builders,
   error analysis, batch history.
2. Detail destinations — Source, Aspect, Word, Keyword, File.
3. Relationship destinations — Source and Aspect categories/keywords.
4. Analytics destinations — Charts, Comprehensive, Path, Archives.
5. System destinations — Setup, Errors, Performance, Processing monitor.
6. Advanced search builder.
7. Navigation: drill-through wiring between destinations.
8. Tests per destination, then documentation.

**AI is explicitly deferred** to after the application surface is complete.

---

## 8. Re-audit (revision 3) — controls that exist in the reference but do nothing

Every subsequent pass re-reads the reference rather than the previous pass's notes. This
one went looking specifically for the opposite of a missing feature: a *decorative*
feature. The rule applied is the one that governs this project — a control is only worth
reproducing if it terminates in a real operation — and its corollary: **the reference
having a control is not, by itself, a reason to build one.**

Read directly from `RussellTCrivello/file_analysis@d2975c2`.

| Reference control | What it actually does there | Decision here |
|---|---|---|
| Batch Analysis → **Schedule** dropdown ("Off-Hours", "Custom Time") | `static/js/pages/analysis-batch-page.js` puts `schedule: 'off-hours'` into `ui_settings` and posts it to `POST /analysis/batch/process`, which processes the selection immediately. `Api/routes/analysis.py` never reads the field. No scheduler, queue, timer or schedule table exists in the project. | **Not reproduced.** Building a scheduler would invent a capability the reference does not have. The Java Batch Analysis destination runs the chosen template now, over the records the operator selected, and persists a run history that survives restart — which the reference only *appears* to do. |
| Batch Analysis → **History** modal | Hard-coded HTML: "Batch #5 — 1,247 files — 98.5% success", "Batch #4 — 892 files", "Batch #3 — 1,523 files". No query, no route. | **Reproduced for real.** History is read from the case database, written by `BatchAnalysisFacade`, and asserted to survive closing and reopening the case (`BatchAnalysisTest`). |
| Batch Analysis → **Avg Speed / Success Rate** tiles | `avg_processing_time = 2.3` is a literal; `success_rate = 98.5` is the default when the query returns nothing. | **Reproduced for real.** Durations and outcomes come from recorded runs; the Performance destination times a live query against the actual index and reports best/median/worst of five runs. |
| Batch Analysis → **Resource Optimization** meters | `<div class="resource-fill" style="width: 45%">` — CPU 45%, memory 62%, disk I/O 38% are literals in the template. | **Replaced with measurement.** `HostMetrics` reads process CPU, system CPU, physical memory and the case volume's capacity from the operating system and the filesystem; the Performance destination samples every two seconds while it is open. Anything this platform does not expose is printed as "not reported by this operating system" rather than drawn as a bar. |

### Why this matters to the audit

Three of the four items above would have passed a screenshot review in either project.
Two of them — the history list and the resource meters — are exactly the kind of thing a
port reproduces faithfully and thereby imports a lie. The test suites treat this as a
defect class of its own: `HostMetricsTest` requires an unmeasured counter to be reported
as unavailable rather than as a plausible number, and the release gate fails if the
Performance screen stops reading the real counters.
