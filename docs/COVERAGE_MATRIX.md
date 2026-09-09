# Coverage matrix — what exists, what is limited, and what is deliberately absent

Generated from `docs/coverage.tsv` by `tools/render-coverage.sh`. The data file is
checked by `CoverageMatrixTest`, which fails the build if a row is unclassified, if it
names Java or a test that does not exist, if a limitation is left unexplained, or if a
destination in the interface is missing from the inventory. If this document and the
code ever disagree, the build says so.

**Generated:** 2026-09-09T03:16:31Z

## How to read it

| Classification | Meaning |
|---|---|
| **VERIFIED** | Implemented, reachable from the interface, persisted where it should be, and proven by a test that runs in this environment. |
| **LIMITED** | Implemented and reachable, but full validation needs hardware or software that is not present here. The note says what is missing and how to validate it. |
| **ADAPTED** | The reference behaviour is delivered in the form the Java architecture calls for — a dialog instead of a form route, a canvas chart instead of a web chart — not in the reference's form. |
| **UNSUPPORTED** | Deliberately not reproduced. The note says why; "the reference has a control" is not on its own a reason to build one. |
| **ABSENT** | No Java counterpart. The note says why and what adding it would involve. |
| **NOT RUN** | Implemented and statically wired, but the JavaFX layer has not been compiled or executed here, so no test proves the control itself. The note names the test behind the operation. |

"Cannot be validated on this machine" is recorded as **LIMITED**, never as absent, and
never as verified.

## Summary

| Classification | Rows |
|---|---:|
| VERIFIED | 91 |
| LIMITED | 3 |
| ADAPTED | 8 |
| UNSUPPORTED | 3 |
| ABSENT | 1 |
| NOT RUN | 9 |
| **Total** | **115** |


## Overview

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| D01 | Analysis/dashboard.html | **VERIFIED** | `DashboardScreen` | `DashboardFacade#getStats` | `FacadeParityTest#DashboardStatsCheck` | Counts, breakdowns and recent items are read from the case database on each visit. |
| D02 | Analysis/charts_dashboard.html | **ADAPTED** | `ChartsDashboardScreen` | `DashboardFacade#getFileTypeBreakdown` | `DestinationCoverageTest#reviewProgress` | Charts are drawn on a JavaFX canvas by ChartPane instead of a browser charting library; the aggregates behind them are identical. |
| D03 | Analysis/comprehensive_dashboard.html | **VERIFIED** | `ComprehensiveDashboardScreen` | `AnalyticsFacade#filteredCounts` | `DestinationCoverageTest#combinedFilters` | Combined source/aspect/category/type filters narrow every tile consistently. |
| D04 | Analysis/analysis_batch.html | **VERIFIED** | `BatchAnalysisScreen` | `BatchAnalysisFacade#start` | `BatchAnalysisTest#hitsComeFromRealAnalysis` | Keyword hits are counted in the extracted text; runs are persisted and survive a restart. |
| D05 | Analysis/path_analysis.html | **VERIFIED** | `PathAnalysisScreen` | `AnalyticsFacade#directoryTree` | `DestinationCoverageTest#directoryTree` | A real tree over path.file_path with rolled-up totals per folder. |
| D06 | Analysis/file_classification.html | **ADAPTED** | `AnalysisScreen` | `DashboardFacade#getCategoriesFiltered` | `UiParityTest#screenInventory` | Folded into the Analysis destination rather than kept as a separate page, which is how the reference reaches it in practice. |

## Sources

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| D07 | Sources/sources_list.html | **VERIFIED** | `SourcesScreen` | `SourceFacade#listSources` | `FacadeParityTest#sourceFacade` | List, create, duplicate and delete all write through to the case database. |
| D08 | Sources/source_detail.html | **VERIFIED** | `SourceDetailScreen` | `AnalyticsFacade#sourceStatistics` | `DestinationCoverageTest#sourceStatistics` | Statistics are computed from the registry, not stored counters. |
| D09 | Sources/source_categories_keywords.html | **VERIFIED** | `RelationshipsScreen` | `AnalyticsFacade#categoriesForSource` | `DestinationCoverageTest#relationships` | Joins path → path_category / path_keyword for that source. |
| D10 | source add / edit forms | **VERIFIED** | `SourceForm` | `SourceFacade#updateSource` | `DestinationCoverageTest#editSource` | Edit persists and a duplicate name is refused with a message. |

## Aspects

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| D11 | Side/sides_list.html | **VERIFIED** | `AspectsScreen` | `AspectFacade#listAspects` | `FacadeParityTest#aspectFacade` | The reference's "side" is named "aspect" throughout; the deprecated alias still resolves. |
| D12 | Side/side_detail.html | **VERIFIED** | `AspectDetailScreen` | `AnalyticsFacade#aspectStatistics` | `DestinationCoverageTest#aspectStatistics` | Statistics are scoped to that aspect alone. |
| D13 | Side/side_categories_keywords.html | **VERIFIED** | `RelationshipsScreen` | `AnalyticsFacade#keywordsForAspect` | `DestinationCoverageTest#relationships` | Same relationship view, scoped to an aspect. |
| D14 | side add / edit | **VERIFIED** | `AspectsScreen` | `AspectFacade#updateAspect` | `DestinationCoverageTest#editAspect` | Edit persists and a duplicate name is refused. |

## Vocabulary

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| D15 | Word/Word_list.html | **VERIFIED** | `WordsScreen` | `WordFacade#getWords` | `FacadeParityTest#corpusFacades` | Create, rename, bulk delete. |
| D16 | Word/Word_detail.html | **VERIFIED** | `TermDetailScreen` | `AnalyticsFacade#categoriesForWord` | `DestinationCoverageTest#wordDetail` | Resolves the categories a word belongs to and the files carrying it. |
| D17 | Word/Word_add.html | **ADAPTED** | `WordsScreen` | `WordFacade#createWord` | `FacadeParityTest#corpusFacades` | A dialog on the list destination rather than a separate page — the desktop equivalent of a form route. |
| D18 | Category/categories_list.html | **VERIFIED** | `CategoriesScreen` | `CategoryFacade#listCategories` | `FacadeParityTest#corpusFacades` | List, create, delete, and word membership. |
| D19 | Category/category_words.html | **ADAPTED** | `CategoriesScreen` | `CategoryFacade#getCategoryWords` | `DestinationCoverageTest#categoryDrillThrough` | A pane inside the category destination; drill-through to the files under a category is a real query. |
| D20 | Keyword/keywords_list.html | **VERIFIED** | `KeywordsScreen` | `KeywordFacade#listKeywords` | `FacadeParityTest#corpusFacades` | Includes duplicate detection and bulk delete. |
| D21 | Keyword/keyword_detail.html | **VERIFIED** | `TermDetailScreen` | `AnalyticsFacade#filesForKeyword` | `DestinationCoverageTest#keywordDetail` | Lists the files carrying the keyword with per-file hit counts. |
| D22 | Keyword/keywords_add_edit.html | **ADAPTED** | `KeywordsScreen` | `KeywordFacade#createKeyword` | `FacadeParityTest#corpusFacades` | Add and edit are a dialog on the list destination rather than two separate routes, which is the desktop equivalent of a form page and keeps the reviewer's place in the list. |

## Files

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| D23 | file/files_list.html | **VERIFIED** | `FileLibraryScreen` | `ContentFacade#getPaths` | `UiParityTest#contentsIntegration` | Columns, filters and paging match the reference list. |
| D24 | file/file_detail.html | **VERIFIED** | `FileDetailScreen` | `ContentFacade#getPath` | `UiParityTest#contentValidation` | Promoted from a dialog to a destination: metadata, hashes, relationships, extracted text. |
| D25 | file/full_content.html | **VERIFIED** | `FullContentScreen` | `ContentFacade#getContentAsText` | `UiParityTest#contentValidation` | Full extracted text with in-document search. |
| D26 | file/upload.html | **VERIFIED** | `UploadScreen` | `FileProcessingFacade#processFolder` | `DragDropIngestTest` | Real ingest: drag-and-drop, folders, containers and multi-select. |
| D27 | file/File_Management_Analysis_System.html | **VERIFIED** | `ArchivesScreen` | `AnalyticsFacade#archiveTree` | `DestinationCoverageTest#archiveTree` | Container nesting from the engine's parent/depth relationships. |
| D28 | email_words/email_words.html | **VERIFIED** | `EmailWordsScreen` | `SearchFacade#search` | `UiParityTest#screenInventory` | Term frequency over email bodies, backed by the index. |

## Search

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| D29 | Search/search.html | **VERIFIED** | `SearchScreen` | `SearchFacade#search` | `QueryParserTest` | 33 grammar checks over the real Lucene index. |
| D30 | Search/search_advanced.html | **VERIFIED** | `AdvancedSearchScreen` | `SearchCriteria` | `QueryValidationTest` | Field-by-field builder composing SearchCriteria; invalid input is reported, not thrown. |
| D31 | Search/search_enhanced.html | **ADAPTED** | `SearchScreen` | `SearchFacade#getSearchSuggestions` | `FacadeParityTest#searchHistoryFacade` | Folded into the main search destination as suggestions and filters rather than a second page. |
| D32 | Search/saved_searches.html | **VERIFIED** | `SavedSearchesScreen` | `SearchHistoryFacade#saveSearch` | `FacadeParityTest#searchHistoryFacade` | Saved searches and history persist in the case database. |

## System

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| D33 | Notifications/notifications.html | **VERIFIED** | `NotificationsScreen` | `NotificationFacade#getNotifications` | `FacadeParityTest#notificationFacade` | Alerts are rows in the case database, dismissable and counted. |
| D34 | ImportExport/import_export.html | **VERIFIED** | `ImportExportScreen` | `ImportFacade#importDatabaseBackup` | `M3AcceptanceTest` | Export formats and a database backup/restore path, both verified end to end. |
| D35 | Settings/settings.html | **VERIFIED** | `SettingsScreen` | `CaseSettings#saveTo` | `SettingsPersistenceTest#optionsSurviveRestart` | Choices are written to settings.properties in the case folder and reapplied on open. |
| D36 | Setup/database_setup.html | **VERIFIED** | `SetupScreen` | `IntegrityVerifier` | `M3AcceptanceTest` | Case and schema status, plus a real integrity verification run. |
| D37 | error dashboard (/api/errors/*) | **VERIFIED** | `ErrorDashboardScreen` | `AnalyticsFacade#errorReport` | `DestinationCoverageTest#errorReport` | Failures by type with causes; a clean case reports none rather than failing. |
| D38 | performance (/performance/*) | **VERIFIED** | `PerformanceScreen` | `HostMetrics#sample` | `HostMetricsTest#cpuIsMeasuredOrDeclaredMissing` | CPU, memory and disk are measured from the OS; anything unmeasurable is shown as text, never as a zero. |
| D39 | concurrency/dashboard.html | **VERIFIED** | `ProcessingMonitorScreen` | `LiveCase#ingestRunning` | `PipelineAcceptanceTest` | Live worker and queue state during a real ingest, with pause, resume and cancel. |

## Reference behaviour

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| R01 | batch Schedule / Off-Hours controls | **UNSUPPORTED** | `BatchAnalysisScreen` | `BatchAnalysisFacade#start` | `BatchAnalysisTest#distinctFromProcessing` | In the reference these controls are inert: analysis-batch-page.js posts to the immediate-processing endpoint and the schedule is never stored or acted on. Reproducing a control that does nothing would be a fake feature; the destination offers explicit start, cancel and re-run instead. |
| R02 | batch History modal | **VERIFIED** | `BatchAnalysisScreen` | `BatchAnalysisFacade#history` | `BatchAnalysisTest#historySurvivesRestart` | The reference's history modal is hardcoded HTML; here runs are rows in the case database and survive a restart. |
| R03 | CPU / Memory / Disk meters | **VERIFIED** | `HostMetrics` | `HostMetrics#sample` | `HostMetricsTest#volumeIsMeasuredFromTheRealFilesystem` | The reference's meters are static markup. These are read from OperatingSystemMXBean and FileStore, and declare themselves unavailable when a counter cannot be read. |
| R04 | avg_processing_time / success_rate tiles | **ADAPTED** | `AnalyticsFacade` | `AnalyticsFacade#reviewProgress` | `DestinationCoverageTest#reviewProgress` | The reference hardcodes 2.3 s and 98.5%. Only figures that can be computed from the case are shown: processed, errors, locked, read/unread. |
| R05 | per-file retry | **VERIFIED** | `ErrorDashboardScreen` | `FileProcessingFacade#retryFile` | `DestinationCoverageTest#retryElement` | The Errors destination retries a selected element through the same pipeline the case was built with. The element keeps its identifier, notes and tags; hashes and text are recomputed; a missing original is reported and nothing is changed. |
| R06 | 404 / 500 pages | **UNSUPPORTED** | `Fas` | `Fas#emptyState` | `UiParityTest#screenInventory` | HTTP error pages have no desktop equivalent; failures surface as inline states and dialogs on the destination that caused them. |
| R07 | /set_language/<lang> | **ABSENT** | `SettingsScreen` | `CaseSettings#saveTo` | `SettingsPersistenceTest#optionsSurviveRestart` | Deliberately deferred, not forgotten: translation begins after the English interface is stable. Inventory, resource architecture and frozen domain vocabulary are in docs/LOCALIZATION_PREPARATION.md; the matching interface row is interface-function-matrix.tsv ST2. |
| R08 | Jinja components/*.html | **UNSUPPORTED** | `Fas` | `Fas#card` | `UiParityTest#stylesheetPresent` | Template partials are not destinations; the equivalent reusable pieces are UiParts and ChartPane. |

## Engine

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| E01 | ingest of mixed formats | **VERIFIED** | `IngestPipeline` | `IngestPipeline#ingest` | `PipelineAcceptanceTest` | AT-01: 44-file mixed corpus, containers, mail, office, images. |
| E02 | nested container extraction | **VERIFIED** | `IngestPipeline` | `IngestPipeline#ingest` | `PipelineAcceptanceTest` | AT-02: ZIP → ZIP → EML → PDF chain. |
| E03 | encrypted containers | **VERIFIED** | `IngestPipeline` | `CaseSettings#passwords` | `PipelineAcceptanceTest` | AT-03: marked Locked, the run continues. |
| E04 | deduplication scopes | **VERIFIED** | `CaseDatabase` | `CaseDatabase#findDuplicate` | `PipelineAcceptanceTest` | AT-04: off / per-custodian / global; nothing is ever deleted. |
| E05 | interrupt and resume | **VERIFIED** | `IngestPipeline` | `CaseDatabase#setQueueState` | `M3AcceptanceTest` | AT-05: a resumed case matches an uninterrupted one. |
| E06 | evidence hashing | **VERIFIED** | `IngestPipeline` | `IntegrityVerifier#verify` | `M3AcceptanceTest` | AT-09: SHA-256 and MD5 verified after write; tampering is detected. |
| E07 | OCR of image-only documents | **LIMITED** | `OcrEngine` | `OcrEngine#run` | `M3AcceptanceTest` | Implemented and wired, including the "not installed" path. Tesseract is not present in this environment, so the OCR assertions report as skipped rather than passing. Install Tesseract to validate. |
| E08 | export (native / EML / PDF / CSV) | **VERIFIED** | `Exporter` | `Exporter#export` | `M3AcceptanceTest` | With a manifest and load file. |
| E09 | Windows path and filename handling | **LIMITED** | `CaseFolder` | `CaseFolder#dataFileFor` | `WindowsCompatibilityTest` | 18 checks pass on Linux over reserved names, trailing dots, MAX_PATH and CRLF/BOM. Certification on a Windows host is still required. |

## AI

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| A01 | optional local agent | **VERIFIED** | `AgentService` | `AgentService#fromEnvironment` | `AiBoundaryTest` | B-04: the application is fully usable with the agent disabled, absent or unreachable. |
| A02 | manual invocation only | **VERIFIED** | `AnalyzeAction` | `AgentService#ask` | `AiBoundaryTest` | B-03/B-05: the agent runs from the Assistant screen and Analyze buttons; ingest makes no model call. |
| A03 | read-only tool boundary | **VERIFIED** | `CaseTools` | `AgentTool#schema` | `AiAgentTest#readOnlyByDefault` | B-01/B-02: ten read-only tools, no shell, SQL, filesystem or network reach. |
| A04 | confirmed write actions | **VERIFIED** | `MutatingTools` | `MutatingTools#all` | `AiAgentTest#noEscapeHatches` | B-07: write tools appear only after an explicit confirmation and never touch evidence. |
| A05 | nothing loads at startup | **VERIFIED** | `AgentService` | `AgentService#isModelLoaded` | `ArchitectureInvariantsTest#nothingAiLoadsWhileTheApplicationStarts` | B-08: wiring the service builds no provider and contacts no runtime. |
| A06 | answers from a real local model | **LIMITED** | `HttpLocalModelProvider` | `HttpLocalModelProvider#chat` | `AiAgentTest#wireProtocol` | The client, tool loop, grounding and audit trail are exercised against a scripted runtime that speaks the real protocol. Generating text with an actual 7B model needs hardware this environment does not have; run Ollama locally to validate quality. |
| A07 | assistant destination | **VERIFIED** | `AgentScreen` | `AgentService#ask` | `AiBoundaryTest` | The only destination that can start the agent. It reports availability on arrival without loading anything, and asks for confirmation before write tools are registered. |

## Failure & recovery

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| F01 | damaged search index | **VERIFIED** | `LiveCase` | `LiveCase#rebuildIndex` | `ResilienceTest#damagedIndexIsRebuilt` | The index is derived, so a case whose index cannot be opened is repaired from the database rather than refused. The damaged copy is kept under logs/ and the repair is recorded as a notification in the case. |
| F02 | case already open elsewhere | **VERIFIED** | `LiveCase` | `LiveCase#indexRepair` | `ResilienceTest#aCaseOpenElsewhereIsRefused` | A lock conflict is refused in plain words and the open case is left untouched — in particular it is never mistaken for damage and rebuilt underneath the session that holds it. |
| F03 | unreadable case database | **VERIFIED** | `LiveCase` | `CaseFolder#database` | `ResilienceTest#unreadableDatabaseIsExplained` | The one file a case cannot do without: named, explained, and paired with what to do about it, instead of a driver-level message. |
| F04 | missing extracted text or metadata | **VERIFIED** | `IntegrityVerifier` | `IntegrityVerifier#verify` | `ResilienceTest#missingTextIsReported` | Search keeps working from the index, the verifier raises findings for the missing evidence text, and a lost case.json is written again. |
| F05 | interrupted run | **VERIFIED** | `IngestPipeline` | `CaseDatabase#setQueueState` | `M3AcceptanceTest` | An abruptly stopped run resumes without redoing finished work, and the recovered case matches an uninterrupted one. |

## Architecture

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| X01 | one datastore | **VERIFIED** | `CaseDatabase` | `CaseFolder#database` | `ArchitectureInvariantsTest#caseDatabaseIsTheOnlyDatastore` | No second store may appear in a case folder. |
| X02 | database authoritative, index derived | **VERIFIED** | `LiveCase` | `LiveCase#indexedCount` | `ArchitectureInvariantsTest#theDatabaseOutlivesTheIndex` | Destroying the index leaves every record readable. |
| X03 | no reference-runtime dependency | **VERIFIED** | `AegisFacades` | `AegisFacades#open` | `ArchitectureInvariantsTest#noWebApiOrPythonRuntimeDependency` | No Python, no web API, and only the AI client may use the network. |
| X04 | clean shutdown | **VERIFIED** | `LiveCase` | `LiveCase#close` | `ArchitectureInvariantsTest#aClosedCaseReleasesItsWorkersAndFiles` | Workers stop, files unlock, and the case reopens intact. |
| X05 | interface controls are wired | **VERIFIED** | `FasApp` | `FasApp#register` | `ArchitectureInvariantsTest#everyControlEndsInAnOperation` | Every control ends in an operation and every destination is reachable. |

## Relationships

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| L01 | keywords ↔ files (keywords_paths) | **VERIFIED** | `RelationshipFacade` | `RelationshipFacade#keyword` | `RelationshipModelTest#keywordBidirectional` | Keyword → files equals files → keyword; per-file hit counts come from the analyzer, never from seeding. |
| L02 | category ↔ files through its words | **VERIFIED** | `RelationshipFacade` | `RelationshipFacade#category` | `RelationshipModelTest#categoryBidirectional` | A category reaches the distinct files containing any of its words, as the reference count query does; list counts equal detail counts across the whole case. |
| L03 | category word ↔ files (words_paths) | **VERIFIED** | `RelationshipFacade` | `RelationshipFacade#categoryWord` | `RelationshipModelTest#categoryWordBidirectional` | Word → files equals files → word for every registered file. |
| L04 | keyword ↔ category ↔ word (keywords.category, words_categorys) | **VERIFIED** | `RelationshipFacade` | `RelationshipFacade#keyword` | `RelationshipModelTest#termToTerm` | Only edges the schema records: a keyword's one category, a category's words, a word's categories. |
| L05 | edge derivation from stored content | **VERIFIED** | `RelationshipAnalyzer` | `RelationshipAnalyzer#analyzeAll` | `RelationshipModelTest#duplicateRelationship` | Whole-word, non-overlapping phrase matching over the stored text; re-running or double-linking never duplicates an edge. |
| L06 | term invariants (keyword ≥ 3 words, category = 1 word) | **VERIFIED** | `Terms` | `Terms#classify` | `RelationshipModelTest#invariants` | Enforced in the facade for keywords, categories and category words; display form survives normalisation. |
| L07 | search across everything with match type | **VERIFIED** | `RelationshipFacade` | `RelationshipFacade#search` | `RelationshipModelTest#searchByLocation` | Every row says where it matched: file name, path, metadata, content, keyword, category or category word. Empty and blank queries return nothing. |
| L08 | Keyword/keywords_list.html usage_count | **VERIFIED** | `KeywordsScreen` | `RelationshipFacade#keywordFileCounts` | `RelationshipModelTest#categoryBidirectional` | The Files column is COUNT(DISTINCT path_id) over the whole case for every keyword, not the visible page. |
| L09 | Category/categories_list.html file_count | **VERIFIED** | `CategoriesScreen` | `RelationshipFacade#categoryFileCounts` | `RelationshipModelTest#categoryBidirectional` | Files column per category and a per-word file badge on the category-words pane. |
| L10 | Word/Word_list.html usage_count | **VERIFIED** | `WordsScreen` | `RelationshipFacade#wordFileCounts` | `RelationshipModelTest#categoryWordBidirectional` | Files column per word; a word outside every category has no relation and shows 0. |
| L11 | category detail (files, keywords, words) | **ADAPTED** | `TermDetailScreen` | `RelationshipFacade#category` | `RelationshipModelTest#categoryBidirectional` | The reference has no category detail page (only the words list). The Java Category Detail exists because the relation is queryable; it fabricates nothing beyond the recorded edges. |
| L12 | file/file_detail.html relationships | **VERIFIED** | `FileDetailScreen` | `RelationshipFacade#forFile` | `RelationshipModelTest#fileDetail` | Path, name, size, SHA-256, MD5, MIME, created, modified, processing status, OCR, then clickable keyword, category and category-word chips with whole-case counts. |
| L13 | relationship integrity traversal (File→Keyword→Category→Word→Files, both directions) | **VERIFIED** | `SearchScreen` | `RelationshipIntegrity#check` | `RelationshipModelTest#integrityConsistent` | Every edge walked both ways with agreeing whole-case counts; a planted orphan edge is reported (integrityDetectsDamage). docs/RELATIONSHIP_INTEGRITY_REPORT.md |
| L14 | merge duplicate keywords / categories | **VERIFIED** | `KeywordsScreen` | `KeywordFacade#mergeDuplicates` | `RelationshipModelTest#mergeDuplicates` | Case-insensitive duplicates merged into the oldest term; edges re-pointed with INSERT OR IGNORE so counts do not double. |
| L15 | CSV export of keywords, categories, words | **VERIFIED** | `KeywordsScreen` | `ExportFacade#exportTermsCsv` | `RelationshipModelTest#mergeDuplicates` | Whole-case terms with file counts written as UTF-8 CSV via Fas.saveBytes. |
| L16 | interface-function matrix policed | **VERIFIED** | `Router` | `Router#open` | `InterfaceFunctionMatrixTest#javaSymbolsResolve` | docs/interface-function-matrix.tsv: every Java symbol, test and screen/button label resolved against the source tree. |

## Failure & recovery

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| F13 | corrupt / unsupported / malformed archive / depth-limited input | **VERIFIED** | `ErrorDashboardScreen` | `AnalyticsFacade#errorReport` | `FailureRecoveryTest#corruptFile` | Each bad input becomes a status with a reason; the run continues; nothing is dropped. |
| F14 | empty case, empty/malformed query, duplicate relationship, invariant violation, restart, malformed model reply | **VERIFIED** | `SearchScreen` | `SearchFacade#search` | `FailureRecoveryTest#emptyAndMalformedSearch` | Zero answers, validation messages, no double counts, relationships survive reopen, garbage model body is a reported failure. |

## Performance & scale

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| P01 | batched ingest transactions, chosen batch size, crash mid-batch | **VERIFIED** | `Ingest` | `IngestPipeline#run` | `BatchRecoveryTest#interruptedBatchLeavesConsistentState` | Batch of 5,000 chosen from a six-point sweep (6,451 -> 17,128 rows/s); an interrupted batch leaves item and queue rows consistent and the lost work is re-offered as un-started. |
| P02 | connection pragmas actually in force at runtime | **VERIFIED** | `Store` | `CaseDatabase#configure` | `BatchRecoveryTest#pragmasAreInForce` | WAL, synchronous, foreign keys, busy timeout, 64 MB cache, MEMORY temp store, 256 MB mmap and autocheckpoint read back from a live connection, not assumed from the source. |
| P03 | derived dashboard statistics, correctness under mutation | **VERIFIED** | `ComprehensiveDashboardScreen` | `DashboardStats#snapshot` | `DashboardStatsTest#countersSurviveMutation` | Counters maintained by triggers stay equal to a direct aggregate across insert, update, delete, status change and rollback; 15,208 ms -> 0.14 ms at 5M items. |
| P04 | deterministic statistics rebuild | **VERIFIED** | `ComprehensiveDashboardScreen` | `DashboardStats#rebuild` | `DashboardStatsTest#rebuildIsDeterministic` | Rebuild from base tables reproduces the counters exactly, including after planted corruption; verify() reports agreement. |
| P05 | query plans free of unintended scans | **VERIFIED** | `Store` | `CaseDatabase#schema` | `BatchRecoveryTest#resumeLookupUsesIndex` | EXPLAIN QUERY PLAN over the hot queries; the quadratic resume scan is gone (ix_queue_source); remaining scans are bounded and listed in docs/bench/plans.tsv. |

## Search & retrieval

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| P06 | facet counts over a result set | **VERIFIED** | `SearchScreen` | `SearchFacets#counts` | `SearchFacetsTest#bucketSumsEqualHitCount` | Postings intersection over existing fields; bucket sums equal the hit count, each bucket equals its own filtered query, uncommitted documents are counted (NRT). |

## Relationships

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| P07 | case-wide bidirectional counts | **VERIFIED** | `FileDetailScreen` | `CorpusDatabase#selectCategoryUsage` | `RelationshipCountsTest#categoryUsageMatchesPerPathListing` | Counts are COUNT(DISTINCT path_id) over the whole case, not the loaded page; grouped counts agree with independently computed per-term counts in both directions. |

## Performance & scale

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| P08 | runtime instrumentation of the timed operations | **VERIFIED** | `Store` | `OperationTimings#snapshot` | `OperationTimingsTest#percentilesSeparateFastFromSlow` | Counts, totals, max and bucketed p50/p95/p99 per operation; never under-reports, never over-reports by more than 2x, under 2 microseconds per call, exact under eight concurrent writers. |

## Architecture

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| P09 | CorpusDatabase is a DAO on the one case connection, not a second database | **VERIFIED** | `Store` | `CorpusDatabase#CorpusDatabase` | `CorpusAuthorityTest#corpusSharesTheCaseConnectionRatherThanOpeningItsOwn` | Proven by transaction visibility and rollback, not by inspection: an uncommitted corpus write is visible through CaseDatabase and vanishes on its rollback. Only case.db is ever created; PRAGMA database_list shows main and temp only. |
| P10 | path is a projection of item, not a rival record of case membership | **VERIFIED** | `Store` | `CorpusSchema#migrate` | `CorpusAuthorityTest#pathIsASatelliteOfItemAndIsDeletedWithIt` | path.element_id REFERENCES item(id) ON DELETE CASCADE; deleting the item removes the path row. Four denormalised columns are recorded as technical debt, not drift that is currently reachable. |
| P11 | exactly one class opens a database connection | **VERIFIED** | `Store` | `CaseDatabase#connection` | `ArchitectureInvariantsTest#onlyCaseDatabaseOpensAConnection` | Walks every main-tree source file; fails if any class but CaseDatabase opens a connection. Replaces a grep that would not survive a merge, after a second UI connection was found bypassing every configured PRAGMA. |

## Relationships

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| P12 | term semantics enforced by the schema, not only by Java | **VERIFIED** | `Store` | `CorpusSchema#migrate` | `CorpusAuthorityTest#rawSqlCannotBypassTheKeywordThreeWordRule` | Triggers on INSERT and UPDATE reject a keyword under three words and a category word of more than one. Triggers rather than CHECK so existing multi-gigabyte cases gain the rule at migration without a table rebuild. |

## Analysis hub

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| D40 | Analysis hub — titles card | **VERIFIED** | `TitlesScreen` | `ContentFacade#getPaths` | `UiParityTest#contentsIntegration` | Distinct file names grouped from the path registry with per-title file, type and source counts; double-click opens the file. |
| D41 | Analysis hub — relations card | **VERIFIED** | `RelationsScreen` | `RelationshipFacade#search` | `RelationshipModelTest#searchByLocation` | Whole-case edge totals plus a scoped graph search; every match reports where the file matched. |
| D42 | Analysis hub — geolocation card | **VERIFIED** | `GeolocationScreen` | `ContentFacade#getPaths` | `UiParityTest#contentsIntegration` | Groups non-blank path.coordinates from registered material; an empty case reports 0 / 0 with an explicit empty state. |

## Vocabulary

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| U11 | category detail file cards (Unit 1) | **NOT RUN** | `TermDetailScreen` | `RelationshipFacade#category` | `RelationshipModelTest#categoryBidirectional` | Cards show source, side, size, date and extension per file with select, view, full-view and download; the operation behind them is proven but the cards themselves have not been compiled or clicked through. |
| U21 | keyword Cards/List views (Unit 2) | **NOT RUN** | `KeywordsScreen` | `RelationshipFacade#keywordFileCounts` | `RelationshipModelTest#keywordBidirectional` | Both views render the same page of records with phrase, category, usage and active state; counts are proven but the toggle and cards have not been compiled or clicked through. |
| U22 | Update Keywords re-derivation (Unit 2) | **NOT RUN** | `KeywordsScreen` | `RelationshipAnalyzer#analyzeAll` | `FailureRecoveryTest#duplicateRelationship` | Runs analyzeAll off the FX thread and reports files scanned plus keyword links; the analyzer is proven but the button and its background handoff have not been compiled or executed. |

## Sources

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| U31 | source detail file cards (Unit 3) | **NOT RUN** | `SourceDetailScreen` | `ContentFacade#getPaths` | `UiParityTest#contentsIntegration` | Collected material as file cards with selection and Download Selected above the table; registry reads are proven but the cards have not been compiled or clicked through. |

## Aspects

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| U32 | aspect detail file cards (Unit 3) | **NOT RUN** | `AspectDetailScreen` | `ContentFacade#getPaths` | `UiParityTest#contentsIntegration` | Attributed material as file cards with selection and Download Selected above the table; registry reads are proven but the cards have not been compiled or clicked through. |

## Search

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| U41 | search filter-bar polish (Unit 4) | **NOT RUN** | `SearchScreen` | `SearchFacade#search` | `SearchFacetsTest#facetsAgreeWithResultSet` | Wrapping filters, Clear Filters reset plus re-run, null-safe preview; search itself is proven but the polish has not been compiled or clicked through. |

## Vocabulary

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| U42 | word summary tiles (Unit 4) | **NOT RUN** | `WordsScreen` | `RelationshipFacade#wordFileCounts` | `RelationshipModelTest#categoryBidirectional` | Total / In Files / Unused tiles from whole-case counts; the counts are proven but the tiles have not been compiled or rendered. |

## Search

| # | Reference / capability | Classification | Java | Operation | Test | Notes |
|---|---|---|---|---|---|---|
| U51 | search result → File Detail (Unit 5) | **NOT RUN** | `SearchScreen` | `ContentFacade#getPathByElementId` | `SearchResultResolverTest#luceneHitResolves` | Element id resolves through the indexed UNIQUE path.element_id to the exact record; duplicates, Unicode, children and failures are tested, but navigation has not been compiled or clicked through. |
| U52 | Search Everywhere match-type routing (Unit 5) | **NOT RUN** | `SearchScreen` | `RelationshipFacade#search` | `RelationshipModelTest#searchByLocation` | Term matches open the term detail, file matches open the file; typed rows with term ids are proven but the routing has not been compiled or clicked through. |

## The three items that needed a decision

**Batch scheduling (R01).** The reference's Schedule and Off-Hours controls do not
schedule anything: the page posts to the immediate-processing endpoint and the chosen
time is never stored or acted upon. Reproducing the controls would have reproduced the
appearance of a feature. The destination offers explicit start, cancel and re-run
instead, all of which do what they say.

**Host CPU, memory and disk meters (R03).** The reference's meters are static markup.
They are feasible in Java, so they were built: `HostMetrics` reads the operating system
bean and the filesystem. Where a counter genuinely cannot be read — some containers do
not expose system CPU — the reading is reported as unavailable in words, never as a
plausible-looking zero.

**Real local model generation (A06).** The client, the tool loop, the grounding rules
and the audit trail are exercised against a scripted runtime that speaks the real
protocol, so the architecture is proven. Generating text with an actual 7B model needs
hardware this environment does not have; that is a hardware limit, not a missing
feature, and it is recorded as LIMITED with the procedure for validating it.
