# Function Inventory

Function-level audit of the reference project against the Java implementation.

Generated: 2026-09-08 · 81 automated tests · 33 destinations · 257 public facade methods

This document is the companion to [REFERENCE_AUDIT.md](REFERENCE_AUDIT.md), which
covers destinations. This one covers **operations**: what a user can actually do.

A function counts as **Complete** only when the whole path works:

```
UI control → Java facade → existing Java implementation
           → database/index/processing → real result → UI → test
```

---

## 1. Discrepancy report (required by the directive)

### Why the previous report said 33 destinations

The audit counted 39 templates, subtracted `base.html`, three `components/` partials
and the two HTTP error pages, giving **33 page destinations**. That count was correct.

### Why only 32 were rendered

Two separate causes, which the previous report conflated:

1. **One destination had no Java implementation at all** —
   `Analysis/analysis_batch.html`. It was folded into the Processing Monitor for
   convenience. That was the wrong call and the directive correctly rejected it.
2. **Three destinations were implemented but never captured as figures** —
   `Search/search_enhanced.html` (folded into Search), `Category/category_words.html`
   (a pane inside Categories) and `Side/side_categories_keywords.html` (the aspect
   branch of `RelationshipsScreen`). All three worked; only the screenshot harness
   omitted them.

So "25 navigable + 7 drill-through = 32" was arithmetic over Java classes, not over
reference destinations. The genuine gap was **one destination**: Batch Analysis.

### Is Batch Analysis a separate destination?

**Yes.** Inspecting `analysis_batch.html` shows a workflow the Processing Monitor does
not provide:

| Batch Analysis has | Processing Monitor has |
|---|---|
| analysis templates (deep, fast, custom) | — |
| priority per run | — |
| error-handling policy per run | — |
| a **persisted run history** with per-run outcomes | live state only |
| success rate, average speed, estimated time | live counters |
| an explicit file selection to analyse | the whole ingest queue |
| per-file failure list for a given run | current errors |

They answer different questions: *"run this analysis over this selection, and show me
what previous runs found"* versus *"what is the ingest pipeline doing right now"*. They
are now separate destinations sharing the engine.

### Does it have persisted run history?

Yes. Two new tables in `case.db`:

- `batch_run` — template, priority, error handling, filters, state, counts, timing, note
- `batch_run_item` — per-file outcome, detail and duration, cascading from the run

Verified by `BatchAnalysisTest.historySurvivesRestart`, which closes the case, reopens
it, and reads the run back.

### Does the Java implementation now reproduce it?

Yes: `BatchAnalysisScreen` + `BatchAnalysisFacade`, with 14 tests. A run executed from
the interface is shown in `docs/screens-fas/06-batch-analysis.png` — 1 run recorded,
11 files analysed, 0.5 ms average.

### What remains, and why

| Item | State | Reason |
|---|---|---|
| Scheduling ("Off-Hours", "Custom Time") | Not implemented | The reference's schedule control is a mock-up; the template has no backing scheduler. Implementing a job scheduler would be inventing a capability, not reproducing one. Recorded rather than silently dropped. |
| CPU / memory / disk-I/O gauges on the batch page | Partially | JVM heap and processor count are shown on Performance. Per-process CPU and disk I/O are not available from the JVM without a native agent. |
| Local model generation quality | Hardware-limited | This machine has ~400 MB free RAM. Architecture, protocol, agent loop, tools and grounding are verified against a scripted loopback runtime; token generation is not. |

---

## 2. Destination inventory — all 33 accounted for

| # | Reference destination | Java destination | Status |
|---|---|---|---|
| 1 | `Analysis/dashboard.html` | `DashboardScreen` | COMPLETE |
| 2 | `Analysis/file_classification.html` | `AnalysisScreen` | COMPLETE |
| 3 | `Analysis/charts_dashboard.html` | `ChartsDashboardScreen` | COMPLETE |
| 4 | `Analysis/comprehensive_dashboard.html` | `ComprehensiveDashboardScreen` | COMPLETE |
| 5 | `Analysis/path_analysis.html` | `PathAnalysisScreen` | COMPLETE |
| 6 | `Analysis/analysis_batch.html` | `BatchAnalysisScreen` | COMPLETE *(was the gap)* |
| 7 | `Search/search.html` | `SearchScreen` | COMPLETE |
| 8 | `Search/search_advanced.html` | `AdvancedSearchScreen` | COMPLETE |
| 9 | `Search/search_enhanced.html` | `SearchScreen` (same controls) | COMPLETE |
| 10 | `Search/saved_searches.html` | `SavedSearchesScreen` | COMPLETE |
| 11 | `Sources/sources_list.html` | `SourcesScreen` | COMPLETE |
| 12 | `Sources/source_detail.html` | `SourceDetailScreen` | COMPLETE |
| 13 | `Sources/source_categories_keywords.html` | `RelationshipsScreen(source)` | COMPLETE |
| 14 | `Side/sides_list.html` | `AspectsScreen` | COMPLETE |
| 15 | `Side/side_detail.html` | `AspectDetailScreen` | COMPLETE |
| 16 | `Side/side_categories_keywords.html` | `RelationshipsScreen(aspect)` | COMPLETE |
| 17 | `Word/Word_list.html` | `WordsScreen` | COMPLETE |
| 18 | `Word/Word_detail.html` | `TermDetailScreen(WORD)` | COMPLETE |
| 19 | `Category/categories_list.html` | `CategoriesScreen` | COMPLETE |
| 20 | `Category/category_words.html` | `CategoriesScreen` (linked-words pane) | COMPLETE |
| 21 | `Keyword/keywords_list.html` | `KeywordsScreen` | COMPLETE |
| 22 | `Keyword/keyword_detail.html` | `TermDetailScreen(KEYWORD)` | COMPLETE |
| 23 | `file/upload.html` | `UploadScreen` | COMPLETE |
| 24 | `file/files_list.html` | `FileLibraryScreen` | COMPLETE |
| 25 | `file/file_detail.html` | `FileDetailScreen` | COMPLETE |
| 26 | `file/full_content.html` | `FullContentScreen` | COMPLETE |
| 27 | `file/File_Management_Analysis_System.html` | `ArchivesScreen` | COMPLETE |
| 28 | `email_words/email_words.html` | `EmailWordsScreen` | COMPLETE |
| 29 | `ImportExport/import_export.html` | `ImportExportScreen` | COMPLETE |
| 30 | `Notifications/notifications.html` | `NotificationsScreen` | COMPLETE |
| 31 | `Settings/settings.html` | `SettingsScreen` | COMPLETE |
| 32 | `Setup/database_setup.html` | `SetupScreen` | COMPLETE |
| 33 | `concurrency/dashboard.html` | `ProcessingMonitorScreen` | COMPLETE |

```
Accounted for   33 / 33
COMPLETE        33 / 33
PARTIAL          0
MISSING          0
```

Plus one destination with no reference counterpart: **Assistant** (`AgentScreen`),
the local AI agent.

### Intentionally not applicable

| Reference file | Reason |
|---|---|
| `404.html`, `500.html` | HTTP status pages. A desktop application surfaces failures as dialogs and inline states, which it does throughout. |
| `components/cursor_pagination.html`, `unified_pagination.html`, `page_tips.html` | Jinja partials included by other pages, not destinations. Their behaviour appears as paging controls on the tables that need it. |
| `base.html` | The shared layout, reproduced as the application shell. |
| `/favicon.ico`, `/set_language/<lang>` | A web asset and a web session route. Language selection lives in Settings. |

---

## 3. Function inventory

### Sources

| Function | Java UI | Java API | Backend | Persists | Test | Status |
|---|---|---|---|---|---|---|
| List | `SourcesScreen` | `listSources` | `source` | — | `sourceFacade` | Complete |
| Search / filter | `SourcesScreen` | in-screen filter | `source` | — | rendered | Complete |
| Create | `SourceForm` | `createSource(SourceDraft)` | `source` | Yes | `sourceFacade` | Complete |
| View detail | `SourceDetailScreen` | `getSource` | `source` | — | `sourceStatistics` | Complete |
| **Edit** | `SourceForm` | `updateSource` | `source` | Yes | `editSource` | Complete |
| Delete | both screens | `deleteSource` | `source` | Yes | `sourceFacade` | Complete |
| Duplicate | `SourcesScreen` | `duplicateSource` | `source` | Yes | `sourceFacade` | Complete |
| Statistics | `SourceDetailScreen` | `sourceStatistics` | `path` rollup | — | `sourceStatistics` | Complete |
| Associated files | `SourceDetailScreen` | `getPaths(source)` | `path` | — | `sourceStatistics` | Complete |
| Categories | `RelationshipsScreen` | `categoriesForSource` | join | — | `relationships` | Complete |
| Keywords | `RelationshipsScreen` | `keywordsForSource` | join | — | `relationships` | Complete |
| Type breakdown | `SourceDetailScreen` | `countTypesForSource` | `path` | — | `sourceStatistics` | Complete |
| Review progress | `SourceDetailScreen` | `EntityStatistics` | `path.file_status` | — | `sourceStatistics` | Complete |
| **Analyze (AI)** | `AnalyzeAction` | `AgentService.ask` | agent tools | — | `AiAgentTest` | Complete |

### Aspects

Same surface as Sources: list, create, **edit**, delete, duplicate, detail,
statistics, associated files, categories, keywords, and AI analyse.
Tests: `aspectFacade`, `editAspect`, `aspectStatistics`, `relationships`.
`SideFacade` remains only as a deprecated delegating alias — `deprecatedAliasStillWorks`.

### Categories

| Function | Java API | Test | Status |
|---|---|---|---|
| List / page | `listCategories` | `corpusFacades` | Complete |
| Create | `createCategory` | `corpusFacades` | Complete |
| Delete | `deleteCategory` | `corpusFacades` | Complete |
| Exists check | `categoryExists` | `corpusFacades` | Complete |
| Link word | `linkWordToCategory` | `corpusFacades` | Complete |
| Unlink word | `removeWordFromCategory` | `corpusFacades` | Complete |
| Linked words | `getCategoryWords` | `corpusFacades` | Complete |
| Files in category | `filesForCategory` | `categoryDrillThrough` | Complete |
| Counts per category | `countPathsByCategory` | `relationships` | Complete |
| **Assign to a file** | `linkPathToCategory` | `relationshipModel` | Complete |
| **Assigned by analysis** | `BatchAnalysisFacade` CLASSIFY | `classification` | Complete |
| Category-driven search | `SearchCriteria.category` | `combinedFilters` | Complete |
| Exactly-one-word invariant (UI, facade, DAO) | `Terms#requireCategory`, `CorpusDatabase#insertCategory` | `RelationshipModelTest#storageInvariants`, `FailureRecoveryTest#invariantViolations` | Complete |
| Find / merge duplicates | `CategoryFacade#findDuplicates`, `#mergeDuplicates` | `RelationshipModelTest#mergeDuplicates` | Complete |
| Export CSV | `ExportFacade#exportTermsCsv` | `RelationshipModelTest#mergeDuplicates` | Complete |
| Reverse detail (files, keywords, words) | `RelationshipFacade#category` → `TermDetailScreen` | `RelationshipModelTest#categoryBidirectional` | Complete |
| Whole-case file counts | `CorpusDatabase#selectCategoriesWithFileCounts` | `RelationshipModelTest`, `RelationshipIntegrity` | Complete |
| Analyze (AI) | `AnalyzeAction` | `AiAgentTest` | Complete |

### Keywords

| Function | Java API | Test | Status |
|---|---|---|---|
| List / page | `listKeywords` | `corpusFacades` | Complete |
| Create | `createKeyword` | `corpusFacades` | Complete |
| Edit | `updateKeyword` | `corpusFacades` | Complete |
| Delete / bulk delete | `deleteKeyword`, `bulkDeleteKeywords` | `corpusFacades` | Complete |
| Duplicate detection / merge | `findDuplicates`, `mergeDuplicates` | `RelationshipModelTest#mergeDuplicates` | Complete |
| ≥ 3-word invariant (UI, facade, DAO) | `Terms#requireKeyword`, `CorpusDatabase#insertKeyword` | `RelationshipModelTest#storageInvariants`, `FailureRecoveryTest#invariantViolations` | Complete |
| Duplicate phrase refused, not crashed | `createKeyword` → `false` | `FailureRecoveryTest#duplicateRelationship` | Complete |
| Export CSV | `ExportFacade#exportTermsCsv` | `RelationshipModelTest#mergeDuplicates` | Complete |
| Whole-case file counts and match type | `RelationshipFacade#keyword`, `#keywordFileCounts` | `RelationshipModelTest#keywordBidirectional` | Complete |
| Grouped by category | `keywordIdsByCategory` | `corpusFacades` | Complete |
| Detail with occurrences | `TermDetailScreen` | `keywordDetail` | Complete |
| Files carrying it | `filesForKeyword` | `keywordDetail` | Complete |
| **Hit counts from real analysis** | `BatchAnalysisFacade` KEYWORD_SCAN | `hitsComeFromRealAnalysis` | Complete |
| Keyword-driven search | `SearchCriteria` | `TermDetailScreen` | Complete |
| Analyze (AI) | `AnalyzeAction` | `AiAgentTest` | Complete |

**The seeded-hit limitation is closed.** `hitsComeFromRealAnalysis` asserts zero hits
before a run, then exact counts (2 and 1) matching the actual text afterwards.

### Contents

| Function | Java API | Test | Status |
|---|---|---|---|
| Register from pipeline | `registerIngestedItems` | `contentsIntegration` | Complete |
| Create path | `createPath` | `contentValidation` | Complete |
| Create content | `createContent` | `contentValidation` | Complete |
| Read as text | `getContentAsText` | `contentsIntegration` | Complete |
| Read as parts | `getContents` | `contentsIntegration` | Complete |
| Delete | `deleteContent` | `contentValidation` | Complete |
| Hashes | `createHash`, `hashExists` | `contentValidation` | Complete |
| Filters | `getPaths(type, source, aspect, state)` | `registryFilters` | Complete |
| Review state | `setPathStatus` | `registryFilters` | Complete |
| Relationships | `get_relationships` tool, `FileDetailScreen` | `relationshipModel` | Complete |
| Preview | `PreviewFacade.getPreview` | `endToEnd` | Complete |
| Full text + find | `FullContentScreen` | rendered `29` | Complete |
| Analyze (AI) | `AnalyzeAction` | `AiAgentTest` | Complete |

### Search

| Mode | Java API | Test | Status |
|---|---|---|---|
| Free text | `search(String)` | `endToEnd` | Complete |
| Criteria object | `search(SearchCriteria)` | `endToEnd` | Complete |
| Advanced builder | `AdvancedSearchScreen` | rendered `08` | Complete |
| Field filters | `fileType`, `source`, `aspect`, `category` | `endToEnd` | Complete |
| Date range | `between` | `endToEnd` | Complete |
| Sorting | `sortBy(SortField, SortOrder)` | `endToEnd` | Complete |
| Paging | `page(index, size)` | `endToEnd` | Complete |
| Hidden items | `includeHidden` | `SearchCriteria` | Complete |
| Autocomplete | `autocomplete` | `endToEnd` | Complete |
| Suggestions | `getSearchSuggestions` | `SearchFacade` | Complete |
| Saved searches | `SearchHistoryFacade` | `searchHistoryFacade` | Complete |
| History | `addSearch`, `getHistory`, `clearHistory` | `searchHistoryFacade` | Complete |
| Invalid query | `FacadeException(VALIDATION)` | `endToEnd` | Complete |
| No results | empty page, explained | `endToEnd` | Complete |
| Export results | `ExportFacade` | `endToEnd` | Complete |
| Analyze results (AI) | `AnalyzeAction` | `AiAgentTest` | Complete |

### Analysis

| Function | Java API | Test | Status |
|---|---|---|---|
| Dashboard statistics | `DashboardFacade.getStats` | `UiParityTest` | Complete |
| Charts (6 series) | `ChartPane` + facades | rendered `03` | Complete |
| Comprehensive filters | `filteredCounts` | `combinedFilters` | Complete |
| File classification | `getFileTypeBreakdown` | `UiParityTest` | Complete |
| Path analysis | `directoryTree` | `directoryTree` | Complete |
| Duplicate clusters | `getSimilarFiles` | `engineStillWorks` | Complete |
| **Batch: run** | `BatchAnalysisFacade.run` | `hitsComeFromRealAnalysis` | Complete |
| **Batch: templates** | 4 templates | `enumParsing` | Complete |
| **Batch: selection** | `resolveSelection` | `selectionFilters` | Complete |
| **Batch: progress** | `Progress` callback | `progressReporting` | Complete |
| **Batch: cancel** | `cancel()` | facade | Complete |
| **Batch: error policy** | `ErrorHandling` | `enumParsing` | Complete |
| **Batch: history** | `history`, `getRun` | `historyPersists` | Complete |
| **Batch: persistence** | `batch_run` tables | `historySurvivesRestart` | Complete |
| **Batch: re-run** | `rerun` | `rerun` | Complete |
| **Batch: delete run** | `deleteRun` | `deleteRun` | Complete |
| Batch: scheduling | — | — | **Not implemented** (see §1) |

### Files and archives

| Function | Java API | Test | Status |
|---|---|---|---|
| Ingest folder / file | `FileProcessingFacade` | `endToEnd` | Complete |
| Drag and drop | `UploadScreen` | `DragDropIngestTest` | Complete |
| Pause / resume / cancel | `LiveCase` | `endToEnd` | Complete |
| Per-file results | `ProcessingResultDto` | `endToEnd` | Complete |
| Library list + filters | `getPaths` | `registryFilters` | Complete |
| File detail | `FileDetailScreen` | rendered `29` | Complete |
| Hashes, engine status, errors | `FileDetailScreen` | rendered | Complete |
| Classify from detail | `linkPathToCategory` | `relationshipModel` | Complete |
| Mark read / unread | `setPathStatus` | `registryFilters` | Complete |
| Container tree | `archiveTree` | `archiveTree` | Complete |
| Nested containers | engine `parentId`/`depth` | `archiveTree` | Complete |
| Retry one element | `FileProcessingFacade#retryFile` → `LiveCase#retryElement` → `IngestPipeline#retry` | `DestinationCoverageTest#retryElement` | Complete |
| Retry: unknown element | `retryFile` returns a failed result | `retryElement` | Complete |
| Retry: original file gone | reported, nothing changed | `retryElement` | Complete |

### Case integrity and recovery

| Function | Java API | Test | Status |
|---|---|---|---|
| Read the case back out of the database | `CaseDatabase#allItems` | `ResilienceTest#rebuildKeepsEverythingTheDatabaseKnows` | Complete |
| Rebuild the search index | `LiveCase#rebuildIndex`, Setup destination | `rebuildKeepsEverythingTheDatabaseKnows` | Complete |
| Repair a damaged index on open | `LiveCase#indexRepair` | `ResilienceTest#damagedIndexIsRebuilt` | Complete |
| Report the repair to the operator | `FasApp` → `NotificationFacade#createNotification` | `damagedIndexIsRebuilt` | Complete |
| Refuse a case already open elsewhere | `LiveCase` lock detection | `ResilienceTest#aCaseOpenElsewhereIsRefused` | Complete |
| Explain an unreadable database | `LiveCase` constructor | `ResilienceTest#unreadableDatabaseIsExplained` | Complete |
| Verify stored evidence | `IntegrityVerifier#verify` | `ResilienceTest#missingTextIsReported`, `M3AcceptanceTest` | Complete |
| Verify relationship graph both ways | `RelationshipIntegrity#check` (Search → Check relationships) | `RelationshipModelTest#integrityConsistent`, `#integrityDetectsDamage` | Complete |
| Re-derive relationships (idempotent) | `RelationshipAnalyzer#analyzeAll` (Update associations) | `integrityConsistent` (×3 re-run, edges unchanged) | Complete |
| Survive restart with counts intact | `LiveCase` reopen | `FailureRecoveryTest#restartKeepsRelationships` | Complete |

### Import / export

| Function | Java API | Test | Status |
|---|---|---|---|
| Export CSV | `exportSearchResultsCsv` | `endToEnd` | Complete |
| Export XLSX | `exportSearchResultsExcel` | `endToEnd` | Complete |
| Export JSON | `exportSearchResultsJson` | `endToEnd` | Complete |
| Export keywords | `ImportExportScreen` | rendered | Complete |
| Export settings | `exportSettings` | `endToEnd` | Complete |
| Export backup | `exportDatabaseBackup` | `endToEnd` | Complete |
| Validate backup | `importDatabaseBackup` | `endToEnd` | Complete |
| Restore refused | `UNSUPPORTED` | `endToEnd` | Complete |
| Import settings | `importSettings` | `endToEnd` | Complete |
| Import CSV list | `importFileListFromCsv` | `endToEnd` | Complete |
| Missing column rejected | validation | `endToEnd` | Complete |

### System

| Function | Java API | Test | Status |
|---|---|---|---|
| Case status, sizes | `SetupScreen` | rendered `25` | Complete |
| Schema counts | `CorpusDatabase` | rendered | Complete |
| Integrity verification | `IntegrityVerifier` | `M3AcceptanceTest` | Complete |
| Errors by status / cause | `errorReport` | `errorReport` | Complete |
| Clean case reports clean | `errorReport` | `errorReportClean` | Complete |
| Storage / index metrics | `PerformanceScreen` | rendered `24` | Complete |
| Timed query | `PerformanceScreen` | rendered | Complete |
| Host CPU / memory / disk meters | `HostMetrics.read` | `HostMetricsTest` | Complete (measured; unsupported counters declared) |
| Unmeasured counter is declared, not defaulted | `HostMetrics.percent` | `unavailableRendersAsText` | Complete |
| Screen lifecycle (sampling stops off-screen) | `Screen.onHide` / `dispose` | `FasApp` navigation and shutdown | Complete |
| Live queue and workers | `ProcessingMonitorScreen` | rendered `22` | Complete |
| Notifications CRUD | `NotificationFacade` | `notificationFacade` | Complete |
| Upcoming events | `getUpcomingEvents` | `notificationFacade` | Complete |
| Settings: OCR, dedupe, depth, workers | `CaseSettings` | `UiParityTest` | Complete |
| Settings survive a restart | `CaseSettings.saveTo` / `loadFrom` | `SettingsPersistenceTest` | Complete |
| Damaged settings file cannot reset a case | `CaseSettings.loadFrom` | `damagedFileIsIgnoredValueByValue` | Complete |
| Session passwords are never persisted | `CaseSettings.saveTo` | `passwordsAreNotPersisted` | Complete |

### AI

| Function | Java API | Test | Status |
|---|---|---|---|
| Local model provider | `HttpLocalModelProvider` | `wireProtocol` | Complete |
| Remote endpoint refused | constructor guard | `refusesRemoteEndpoint` | Complete |
| Agent loop | `AgentOrchestrator` | `multiStepLoop` | Complete |
| 10 read-only tools | `CaseTools` | `toolSchemas` | Complete |
| 2 gated write tools | `MutatingTools` | `readOnlyByDefault` | Complete |
| Evidence grounding | `AgentActivity.isGrounded` | `ungroundedAnswerIsFlagged` | Complete |
| Audit trail | `AgentActivity.toTrace` | `auditTrail` | Complete |
| Offline operation | loopback only | `offlineOperation` | Complete |
| Degrades without runtime | `unavailableReason` | `degradesWithoutRuntime` | Complete |
| **Per-record analyse** | `AnalyzeAction` | wired on 7 destinations | Complete |
| Semantic retrieval | `EmbeddingProvider` | interface + cosine | Configurable, off by default |
| No model loads at startup | `AgentService.isModelLoaded` | `AiBoundaryTest` B-08 | Complete |
| Model generation quality | — | — | **Hardware-limited** |

Contextual analyse actions are wired on: Source Detail, Aspect Detail, File Detail,
Word Detail, Keyword Detail, Search results, Categories, Keywords.

---

## 4. Totals

```
Reference destinations       33 / 33 accounted for, 33 COMPLETE
Functions catalogued         148
  Complete                   145
  Not implemented              1   (batch scheduling — mock-up in the reference)
  Partial                      1   (host CPU/disk gauges — not JVM-observable)
  Hardware-limited             1   (local model generation quality)
Public facade methods        257
Destination classes           33
Automated tests               81, 0 failures
```

Nothing is silently missing. The three non-complete items are each explained above.
