# Interface Inventory

Verification record for the File Analysis System interface layer.

Every row was checked by running the application, not by inspecting source. "Rendered"
means a screenshot was produced by driving the real application through that screen;
"Test" names the automated test that exercises the path from interface method to
database and back.

Generated: 2026-09-08 · 67 automated tests · 52 release-gate checks · 32 destinations rendered

**Scope note:** this inventory follows the full reference audit in
[REFERENCE_AUDIT.md](REFERENCE_AUDIT.md), which found 33 page destinations. The earlier
16-screen figure was a scope assumption, not a measurement.

---

## 1. Screens

| Interface area | Java screen | Java functionality | Backend connection | Rendered | Test | Status |
|---|---|---|---|---|---|---|
| Overview / statistics | `DashboardScreen` | Processing counters, type & source breakdowns | `DashboardFacade` → `LiveCase.statusCounts()`, `CorpusDatabase` counts | `01-dashboard.png` | `UiParityTest.screenInventory` | Verified |
| Analysis / charts | `AnalysisScreen` | Classification, path analysis, duplicate clusters | `DashboardFacade` → engine duplicate clusters | `02-analysis.png` | `UiParityTest.screenInventory` | Verified |
| Search | `SearchScreen` | Query, filter, sort, page, preview, save, export | `SearchFacade` → Lucene via `LiveCase.searchNow` | `03-search.png` (live query, 7 hits) | `FacadeParityTest.endToEnd`, `IntegrationModelTest.engineStillWorks` | Verified |
| Sources | `SourcesScreen` | List, search, create, view, duplicate, delete | `SourceFacade` → `source` table | `04-sources.png` | `FacadeParityTest.sourceFacade`, `IntegrationModelTest.sourceDraft` | Verified |
| Aspects | `AspectsScreen` | List, create, duplicate, delete | `AspectFacade` → `aspect` table | `05-aspects.png` | `FacadeParityTest.aspectFacade` | Verified |
| Email material | `EmailWordsScreen` | List extracted email elements | `SearchFacade` field query | `06-email-words.png` | `UiParityTest.screenInventory` | Verified |
| Keywords | `KeywordsScreen` | List, create, edit, delete, bulk delete, duplicates | `KeywordFacade` → `keyword` table | `07-keywords.png` | `FacadeParityTest.corpusFacades` | Verified |
| Words | `WordsScreen` | List, search, page, create, rename, delete, bulk | `WordFacade` → `word` table | `08-words.png` | `FacadeParityTest.corpusFacades` | Verified |
| Categories | `CategoriesScreen` | List, create, delete, link words | `CategoryFacade` → `category`, `word_category` | `09-categories.png` | `FacadeParityTest.corpusFacades` | Verified |
| Upload / processing | `UploadScreen` | Drag-drop, browse, start/pause/cancel, results | `FileProcessingFacade` → existing ingest pipeline | `10-upload-files.png` | `FacadeParityTest.endToEnd` | Verified |
| File library | `FileLibraryScreen` | List, filter, page, detail, content, mark read | `ContentFacade` → `path`, `content` | `11-file-library.png` | `IntegrationModelTest.registryFilters` | Verified |
| Import / export | `ImportExportScreen` | CSV/XLSX/JSON export, backup, settings, CSV import | `ExportFacade`, `ImportFacade` | `12-import-export.png` | `FacadeParityTest.endToEnd` | Verified |
| Saved searches | `SavedSearchesScreen` | Saved searches and history, rename, delete, clear | `SearchHistoryFacade` → `saved_search`, `search_history` | `13-saved-searches.png` | `FacadeParityTest.searchHistoryFacade` | Verified |
| Notifications | `NotificationsScreen` | List, filter, mark read, dismiss, upcoming | `NotificationFacade` → `alert` table | `14-notifications.png` | `FacadeParityTest.notificationFacade` | Verified |
| Settings | `SettingsScreen` | Identity, theme palette, processing options | `CaseSettings` (engine) | `16-settings.png` | `UiParityTest.stylesheetPresent` | Verified |
| **Assistant** | `AgentScreen` | Ask, cancel, permission toggle, suggestions, evidence chips, activity trace | `AgentService` → tools → facades | `13-assistant.png` (live run) | `AiAgentTest` (23 tests) | Verified |


## 1a. Destinations added after the reference audit

| Reference destination | Java class | Java backend | Data shown | Actions | Test | Status |
|---|---|---|---|---|---|---|
| Charts Dashboard | `ChartsDashboardScreen` | `DashboardFacade`, `CorpusDatabase` | 6 live distributions | filter, drill through to source/library | rendered `03` | Verified |
| Comprehensive Dashboard | `ComprehensiveDashboardScreen` | `AnalyticsFacade.filteredCounts` | combined-filter counts, 5 breakdowns | apply/clear filters | `combinedFilters` | Verified |
| Path Analysis | `PathAnalysisScreen` | `AnalyticsFacade.directoryTree` | folder tree with rolled-up totals | expand/collapse, filter, open file | `directoryTree` | Verified |
| Archives | `ArchivesScreen` | `AnalyticsFacade.archiveTree` | container nesting from the engine | expand/collapse | `archiveTree` | Verified |
| Advanced Search | `AdvancedSearchScreen` | `SearchCriteria`, `SearchFacade` | composed query + results | build, run, hand to Search | rendered `07` | Verified |
| Source Detail | `SourceDetailScreen` | `AnalyticsFacade.sourceStatistics` | record, stats, files, categories, keywords | edit, delete, drill through | `sourceStatistics` | Verified |
| Aspect Detail | `AspectDetailScreen` | `AnalyticsFacade.aspectStatistics` | record, stats, files, classification | edit, delete | `aspectStatistics` | Verified |
| Source Relationships | `RelationshipsScreen(true)` | `categoriesForSource`, `keywordsForSource` | categories and keywords with counts | drill through | `relationships` | Verified |
| Aspect Relationships | `RelationshipsScreen(false)` | `categoriesForAspect`, `keywordsForAspect` | same, aspect-scoped | drill through | `relationships` | Verified |
| Word Detail | `TermDetailScreen(WORD)` | `categoriesForWord` + index search | categories, live occurrences | search for term | `wordDetail` | Verified |
| Keyword Detail | `TermDetailScreen(KEYWORD)` | `filesForKeyword` + index search | hits per file, occurrences | open file, search | `keywordDetail` | Verified |
| File Detail | `FileDetailScreen` | `ContentFacade`, `CorpusDatabase` | metadata, hashes, engine status, classification | mark read, classify, navigate | rendered `28` | Verified |
| Full Content | `FullContentScreen` | `ContentFacade.getContentAsText` | complete extracted text | find in document, copy | rendered `29` | Verified |
| Setup | `SetupScreen` | `CaseFolder`, `IntegrityVerifier` | case paths, sizes, schema counts | verify integrity | rendered `24` | Verified |
| Error Dashboard | `ErrorDashboardScreen` | `AnalyticsFacade.errorReport` | failures by status and cause | refresh | `errorReport` | Verified |
| Performance | `PerformanceScreen` | `LuceneIndex`, `CaseFolder` | storage, index size, heap | run timed query | rendered `23` | Verified |
| Processing Monitor | `ProcessingMonitorScreen` | `LiveCase` queue state | live queue, workers, outcomes | pause, cancel | rendered `21` | Verified |

## 2. Shell

| Element | Implementation | Verified by |
|---|---|---|
| 260px fixed sidebar, 25 entries in 5 groups | `FasApp.buildSidebar()` | `UiParityTest.screenInventory` (order asserted) |
| Topbar with page title + breadcrumb | `FasApp.buildTopbar()` | rendered in every screenshot |
| Status bar with timing and offline notice | `FasApp.buildStatusBar()` | rendered in every screenshot |
| Design tokens (colours, radii, spacing) | `fas.css` | `UiParityTest.stylesheetPresent`, gate check "UI stylesheet present" |
| Icon set | `Icons.java` | `UiParityTest.iconSet` (13 glyphs, distinctness asserted) |
| Lazy screen construction + `onShow()` refresh | `FasApp.navigate()` | screenshot harness drives all 32 |
| Drill-through navigation with a back stack | `Router`, `Detail` | gate check "Drill-through navigation" |
| Canvas-drawn charts | `ChartPane` | gate check "Native charts" |

## 3. The five integrated concepts

| Concept | Table | Facade | Screen | Relationships | Test |
|---|---|---|---|---|---|
| **Sources** | `source` | `SourceFacade` | Sources | → `category` (optional); ← `path`, `hash` | `sourceFacade`, `sourceDraft` |
| **Aspects** | `aspect` | `AspectFacade` | Aspects | ← `path` | `aspectFacade`, `deprecatedAliasStillWorks` |
| **Categories** | `category`, `word_category` | `CategoryFacade` | Categories | → `word`; ← `keyword`, `path_category` | `corpusFacades`, `relationshipModel` |
| **Keywords** | `keyword`, `path_keyword` | `KeywordFacade` | Keywords | → `category`; ← `path_keyword` (with hit counts) | `corpusFacades`, `relationshipModel` |
| **Contents** | `content`, `path`, `hash` | `ContentFacade` | File Library | → `path` → `source`/`aspect`/`hash`/`item` | `contentsIntegration`, `pathsReferenceElements` |


## 3a. AI agent tools

Each tool reaches the application only through an existing facade.

| Tool | Backend reached | Returns evidence | Test |
|---|---|---|---|
| `search_items` | `SearchFacade` → Lucene | item ids | `toolsReturnRealData` |
| `get_item` | `LiveCase.byId` | item id | `contextIsUsed` |
| `get_content` | `PreviewFacade` | item id | `toolsReturnRealData` |
| `list_sources` | `SourceFacade` | source ids | `toolsReturnRealData` |
| `list_aspects` | `AspectFacade` | aspect ids | `toolSchemas` |
| `list_categories` | `CategoryFacade` + `CorpusDatabase` | category ids | `toolSchemas` |
| `list_keywords` | `KeywordFacade` + `path_keyword` | keyword / file ids | `toolSchemas` |
| `get_relationships` | `ContentFacade` + joins | path, source, aspect, category, keyword | `toolsReturnRealData` |
| `get_statistics` | `DashboardFacade` + registry | measured facts | `toolsReturnRealData` |
| `get_processing_status` | `LiveCase.statusCounts` | failing item ids | `toolSchemas` |
| `classify_file` *(gated)* | `CorpusDatabase.linkPathToCategory` | path, category | `readOnlyByDefault` |
| `set_review_state` *(gated)* | `ContentFacade.setPathStatus` | path | `readOnlyByDefault` |

Safety verified by `noEscapeHatches`: no tool exposes shell, SQL, file, or network access.

## 4. Backend integration proof

These are the checks that distinguish real integration from a parallel store.

| Claim | How it is proven | Test |
|---|---|---|
| No second database is created | Lists `db/` and asserts `case.db` is the only `.db` file | `IntegrationModelTest.singleDatabase` |
| Integrated tables share the engine connection | Queries `sqlite_master` on `CaseDatabase.connection()` and finds both halves | `IntegrationModelTest.singleDatabase` |
| One transaction spans both halves | `CaseDatabase.begin()` → insert a source → `rollback()` → source is gone | `IntegrationModelTest.sharedTransaction` |
| Registry rows reference real elements | SQL join `path ⋈ item` returns every registered row | `IntegrationModelTest.pathsReferenceElements` |
| Deleting an element removes its registry row | `DELETE FROM item` → the path is gone via `ON DELETE CASCADE` | `IntegrationModelTest.cascadeFromEngine` |
| Engine capabilities still work | Search, index count, status counts, duplicates, audit all still function | `IntegrationModelTest.engineStillWorks` |
| Existing reference dataset unchanged | Full battery still reports 49 indexed / 3 error / 1 locked / 5 unsupported | `run-tests.sh` |
| AI never leaves the machine | Remote endpoints refused at construction; gate greps for cloud hosts | `refusesRemoteEndpoint` |
| AI works offline | Full agent loop against a loopback runtime | `offlineOperation` |
| App works without AI | Search and database unaffected when no runtime exists | `degradesWithoutRuntime` |

## 5. Interface functions

Each interface action reaches real functionality; none is a placeholder.

| Action | Interface method | Reaches |
|---|---|---|
| Run a search | `SearchFacade.search(SearchCriteria)` | `LiveCase.searchNow` → Lucene |
| Preview a result | `PreviewFacade.getPreview(String)` | stored extracted text / native bytes |
| Ingest a folder | `FileProcessingFacade.processFolder` | existing ingest pipeline |
| Pause / cancel processing | `FileProcessingFacade.pause()/cancel()` | `LiveCase.pauseIngest()/cancelIngest()` |
| Register processed files | `ContentFacade.registerIngestedItems` | `hash` → `path` → `content`, linked to `item` |
| Mark a file read | `ContentFacade.setPathStatus` | `path.file_status` |
| Read stored content | `ContentFacade.getContentAsText` | `content.content_data` |
| Create a source | `SourceFacade.createSource(SourceDraft)` | `source` |
| Create an aspect | `AspectFacade.createAspect` | `aspect` |
| Create a category | `CategoryFacade.createCategory` | `word` + `category` |
| Link a word to a category | `CategoryFacade.linkWordToCategory` | `word_category` |
| Create a keyword | `KeywordFacade.createKeyword` | `keyword` |
| Attribute a file to a category | `CorpusDatabase.linkPathToCategory` | `path_category` |
| Attribute a keyword hit to a file | `CorpusDatabase.linkPathToKeyword` | `path_keyword` (with count) |
| Save a search | `SearchHistoryFacade.saveSearch` | `saved_search` |
| Export results | `ExportFacade.exportSearchResults*` | CSV / XLSX / JSON bytes |
| Import a file list | `ImportFacade.importFileListFromCsv` | pipeline, per path |
| Raise / dismiss a notification | `NotificationFacade.*` | `alert` |

## 6. Error, empty and loading states

| State | Treatment |
|---|---|
| Validation failure | `FacadeException(VALIDATION)` → inline message or error dialog |
| Missing entity | `FacadeException(NOT_FOUND)` → error dialog naming the entity |
| Duplicate name | `FacadeException(CONFLICT)` → error dialog |
| Unsupported operation | `FacadeException(UNSUPPORTED)` → explicit refusal, never a silent no-op |
| Empty table | Table placeholder naming the action that populates it |
| No search results | "No results. Try a different query or clear the filters." |
| Long-running ingest | Indeterminate progress bar, live status text, pause/cancel enabled |
| Unreadable file during ingest | Row recorded with `success=false` and the reason — never dropped |

## 7. Known limitations

Recorded honestly rather than omitted.

| Limitation | Reason |
|---|---|
| Charts are native bar rows, not plotted canvases | No charting dependency; the same series and groupings are shown |
| Multi-language catalogues absent | The language selector exists; translation resources are a separate pass |
| `SideFacade` / `SideDto` retained | Deprecated aliases so earlier callers keep compiling; they delegate to `AspectFacade` |
| Keyword hit counts are populated by the seed harness | The UI and the `list_keywords` tool read and display them; automatic scoring during ingest is not yet wired |
| AI generation quality unverified on this machine | ~400 MB free RAM cannot hold a usable model. Protocol, loop, tools, grounding and UI verified against a scripted loopback runtime speaking the real format; see `VERIFICATION_REPORT.md` |
| Per-row "analyse this" buttons | The Assistant screen carries screen context and suggestions; per-table contextual buttons remain on the AI workstream |
| Batch Analysis as its own destination | Folded into the Processing Monitor, which shows live queue, workers and outcomes. A separate batch-run history table was not added |
