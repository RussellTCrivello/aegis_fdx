# Interface → Function Matrix

The audit layer after the 33-destination / 73-capability matrix. It traces every
interactive element on the reference's relationship pages through its JavaScript
handler, its Python route and its data access, and names the JavaFX control, handler,
facade operation and database/index operation that reproduce the *observable* behaviour.

Reference: `RussellTCrivello/file_analysis` (templates, `static/js/pages/*.js`,
`static/js/modules/*.js`, `Api/routes/*.py`, `Api/blueprints/*.py`, `Api/services/*.py`,
`database/`). The reference is a behavioural specification. No Python, template or
JavaScript is executed, embedded or called by the Java application
(`ArchitectureInvariantsTest#noWebApiOrPythonRuntimeDependency`).

Relationship evidence: `docs/REFERENCE_RELATIONSHIP_MODEL.md`.
Coverage rows: `docs/coverage.tsv` L01–L12, summarised in `docs/COVERAGE_MATRIX.md`.
Tests: `RelationshipModelTest` (relationships and search), `DestinationCoverageTest`,
`UiParityTest`, `ArchitectureInvariantsTest#everyControlEndsInAnOperation`.

## Status vocabulary

| Status | Meaning |
|---|---|
| **DONE** | The Java control performs the same observable operation on the same data, backed by case.db / Lucene. |
| **DONE+** | As DONE, plus something the reference computes but does not surface (e.g. a per-row match type, a clickable relation). Grounded in relations the reference records; nothing fabricated. |
| **ADAPTED** | Same purpose, different mechanism appropriate to a desktop (e.g. a dialog instead of a modal, a table sort instead of a `?sort=` reload). |
| **INERT-IN-REF** | The reference control does nothing observable (hard-wired markup, a handler that never reaches the server). Not reproduced; noted so it is not mistaken for a gap. |
| **NOT-BUILT** | A reference behaviour with no Java equivalent yet. Stated plainly. |

## The relationship model behind every row

```
FILE (path, content, hash, metadata)
  │  path_keyword(path_id, keyword_id, hits)      ← reference keywords_paths
  ├──── KEYWORD (phrase, ≥ 3 words) ──── category_id ──── CATEGORY (one word)
  │  path_word(path_id, word_id, hits)            ← reference words_paths
  ├──── CATEGORY WORD (one word) ──── word_category ────── CATEGORY
  │  path_category(path_id, category_id)          ← reviewer attribution
  └──── CATEGORY
```

* Edges are derived by `RelationshipAnalyzer` from stored content (whole-word,
  non-overlapping phrase matching) when material is registered
  (`ContentFacade#registerIngestedItems`) and on demand ("Update associations").
  The reference does the same in `content_processor.py` at store time and in
  `POST /api/keywords/update-associations` on demand.
* Every count shown anywhere is `COUNT(DISTINCT path_id)` over the whole case
  (`CorpusDatabase#selectKeywordsWithFileCounts`, `#selectCategoriesWithFileCounts`,
  `#selectCategoryWordsWithFileCounts`).
* A category reaches a file through any of its words or by attribution — the
  reference's `categories_list` count query (`COUNT(DISTINCT p.id)` via
  `words_categorys ⨝ words_paths`).
* Invariants: keyword ≥ 3 words, category = 1 word, category word = 1 word
  (`Terms#requireKeyword`, `#requireCategory`, `#requireSingleWord`), enforced in
  `KeywordFacade#createKeyword`, `CategoryFacade#createCategory` and
  `CategoryFacade#linkWordToCategory`. Display form is stored; normalisation is used
  for comparison only (`TermSummary.text` vs `TermSummary.normalized`).

---

## 1. Keywords list — `templates/Keyword/keywords_list.html` · `keywords-list-page.js`

Java destination: `KeywordsScreen` ("Keywords").

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| keywords_list.html | `#searchKeywords` | input / Enter | `applyFilters` → `fetchPage` | `GET /api/keywords?q=` → `keywords LEFT JOIN keywords_paths` | Keywords | (list is unfiltered; search lives on Search) | `SearchScreen#runRelationshipSearch` scope Keyword | `selectKeywordsMatching` | ADAPTED |
| keywords_list.html | `#statusFilter` (Active/Unused) | change | `applyFilters` | `status = EXISTS keywords_paths` | Keywords | `Files` column | `RelationshipFacade#keywordFileCounts` | `COUNT(DISTINCT path_id)` | ADAPTED — the count is shown; 0 = Unused |
| keywords_list.html | `#categoryFilter` | change | `applyFilters` | `?category=` | Keywords | `Category` column | `KeywordFacade#listKeywords` | `keyword ⨝ category ⨝ word` | ADAPTED |
| keywords_list.html | `#sortBtn-text/-usage_count/-status` | click | `sortBy` → `updateSortIcons` | `?sort=&order=` | Keywords | table header click | JavaFX TableView sort | in-memory over the page | ADAPTED |
| keywords_list.html | `#perPage` | change | `changePageSize` | `?per_page=` | Keywords | `perPage` ComboBox | `KeywordsScreen#onShow` | `LIMIT ? OFFSET ?` | DONE |
| keywords_list.html | pagination | click | `renderPaginator` / `fetchPage` | `?page=` | Keywords | Previous / Next | `KeywordsScreen#onShow` | `LIMIT ? OFFSET ?` | DONE |
| keywords_list.html | row `usage_count` badge | — | `renderRows` | `COUNT(kp.path_id)` | Keywords | `Files` column | `RelationshipFacade#keywordFileCounts` | `path_keyword` COUNT DISTINCT | DONE — whole case, not the page |
| keywords_list.html | row **Duplicate** flag | — | `renderRows` | lower-cased text equality | Keywords | Find Duplicates | `KeywordFacade#findDuplicates` | `GROUP BY LOWER(phrase)` | DONE |
| keywords_list.html | row view (`href=/keywords/{id}`) | click | navigation | `GET /keywords/<id>` | Keywords | eye button / double-click | `Router#openKeyword` → `TermDetailScreen(KEYWORD)` | `RelationshipFacade#keyword` | DONE |
| keywords_list.html | row edit | click | `editKeywordInModal` → `submitUpdateKeyword` | `PUT /api/keywords/<id>` | Keywords | pencil button | `KeywordFacade#updateKeyword` | `UPDATE keyword` | DONE (dialog) |
| keywords_list.html | row delete | click | `deleteKeyword` | `DELETE /api/keywords/<id>` | Keywords | trash button | `KeywordFacade#deleteKeyword` | `DELETE keyword` (+ cascades) | DONE |
| keywords_list.html | `#selectAllCheckbox`, `.keyword-checkbox` | change | `toggleSelectAll` / `updateSelection` | — | Keywords | multi-select table | `SelectionMode.MULTIPLE` | — | DONE |
| keywords_list.html | Delete Selected | click | `bulkDelete` | `POST /api/keywords/bulk-delete` | Keywords | Delete Selected | `KeywordFacade#bulkDeleteKeywords` | `DELETE … IN (?)` | DONE |
| keywords_list.html | `#bulkUpdateBtn` | click | `bulkUpdate` | *(no route; assigns category client-side only)* | — | — | — | — | INERT-IN-REF |
| keywords_list.html | Merge duplicates | click | `mergeAllDuplicates` | `POST /api/keywords/merge-all-duplicates` | Keywords | Find Duplicates (reports) | `KeywordFacade#findDuplicates` | — | NOT-BUILT (merge); duplicates are reported, not merged |
| keywords_list.html | `#updateKeywordsBtn` "Update associations" | click | `updateKeywordAssociations` | `POST /api/keywords/update-associations` → `contents` → `extract_keywords_fast` → `keywords_paths` | Search | Update associations | `RelationshipAnalyzer#analyzeAll` | `clearDerivedRelations` + `linkPathToKeyword` + `linkPathToWord` | DONE |
| keywords_list.html | Export | click | `exportKeywords` (client CSV of visible rows) | — | Search | Export CSV | `SearchScreen#exportCsv` | — | ADAPTED |
| keywords_list.html | Add Keyword | click | `openAddKeywordModal` | — | Keywords | Add Keyword | `KeywordsScreen#openForm` | — | DONE (dialog) |
| addKeywordModal | `#keywordWordInput` autocomplete | input | `searchWordsForKeyword` → `renderWordResultsForKeyword` | `GET /api/words/search?q=` | Keywords | Keyword text field | — | — | ADAPTED — phrase typed directly; validated by `Terms#requireKeyword` |
| addKeywordModal | `#keywordCategoryInput` autocomplete | input | `searchCategoriesForKeyword` | `GET /api/categories/search?q=` | Keywords | category ComboBox | `CategoryFacade#listCategories` | `category ⨝ word` | DONE |
| addKeywordModal | Save | click | `saveKeyword` → `fetch('/api/keyword/check')` then `fetch('/keywords/add')` | `keyword_check` (exists?) then `keywords_add` (`validate_keyword_text`, pickle word ids) | Keywords | form Save | `KeywordFacade#keywordExists` + `#createKeyword` | `INSERT keyword` | DONE — ≥ 3 words enforced |
| keywords_list.html | toast | — | `showToast` | — | Keywords | Alert / inline label | `KeywordsScreen#err` | — | ADAPTED |
| keywords_list.html | `onclick="location.reload"` | click | reload | — | Keywords | Refresh via re-`onShow` | `KeywordsScreen#onShow` | — | DONE |

## 2. Keyword detail — `templates/Keyword/keyword_detail.html` · `keyword-detail-page.js`

Java destination: `TermDetailScreen(KEYWORD)` ("Keyword Detail").

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| keyword_detail.html | ID / usage_count / file_types / text / last_used | — | (server-rendered) | `keyword_detail`: `keywords_paths` COUNT, DISTINCT `file_type` | Keyword Detail | heading, stat cards | `RelationshipFacade#keyword` | `selectKeywordById`, `selectFilesForKeyword`, `countFilesForKeyword` | DONE |
| keyword_detail.html | *(no file list)* | — | — | relation exists in `keywords_paths` but is not listed | Keyword Detail | Related Files table (file, path, source, type, hits) | `TermSummary.files` | `selectFilesForKeyword` | DONE+ |
| keyword_detail.html | *(category shown by name only)* | — | — | `keywords.category` | Keyword Detail | Categories chip (with its file count) | `TermSummary.categories` | `selectCategoryById` + `countFilesForCategory` | DONE+ |
| keyword_detail.html | Edit (`/keywords/add?keyword_id=`) | click | navigation | `keywords_add` GET | Keyword Detail | Open in list → pencil | `KeywordFacade#updateKeyword` | `UPDATE keyword` | ADAPTED |
| keyword_detail.html | Delete | click | `deleteKeyword` → `fetch(/api/keywords/${id}, DELETE)` → redirect | `delete_keyword_api` | Keywords list | trash button | `KeywordFacade#deleteKeyword` | `DELETE keyword` | ADAPTED (from list) |
| keyword_detail.html | Back | click | navigation | `/keywords` | Keyword Detail | Back to Keywords | `Router#open("Keywords")` | — | DONE |
| — | file row | double-click | — | — | Keyword Detail | Related Files row | `Router#openFile` | — | DONE+ |
| — | "Search for this term" | click | — | — | Keyword Detail | button | `Router#openSearch(quoted)` → Lucene | `LiveCase#searchNow` | DONE+ |

## 3. Categories list — `templates/Category/categories_list.html` · `categories-list-page.js`

Java destination: `CategoriesScreen` ("Categories"). Reference rendering is fully
client-side over `categories-page-data` (`initializeCategoriesData`).

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| categories_list.html | row `file_count` / `word_count` | — | `renderTableView` | `categories_list`: `COUNT(DISTINCT p.id)`, `COUNT(DISTINCT wc.word_id)` via `words_categorys ⨝ words_paths ⨝ paths` | Categories | `Files` column; words pane header "N word(s)" | `RelationshipFacade#categoryFileCounts`; `CategoryFacade#getCategoryWords` | `PATHS_OF_CATEGORY` COUNT DISTINCT | DONE |
| categories_list.html | `#categorySearch` | input (debounced) | `applyFiltersAndRender` | client-side | Categories | — (500 rows shown) | — | — | ADAPTED — Search page scope Category |
| categories_list.html | `#sortBy`, `#sortBtn-name/-files/-words` | change / click | `sortCategories` / `sortByColumn` | client-side | Categories | table header click | TableView sort | in-memory | DONE |
| categories_list.html | `#displayFormat` table/grid/list/compact | change | `changeDisplayFormat` → `render*View` | client-side | Categories | table only | — | — | ADAPTED |
| categories_list.html | `#itemsPerPage`, `#pagination` | change / click | `changePageSize` / `changePage` | client-side | Categories | — | `listCategories(500,0)` | `LIMIT 500` | ADAPTED |
| categories_list.html | View Words (`/categories/{id}/words`) | click | `viewCategoryWords` | `category_words` | Categories | select row → right pane; `Router#openCategory` | `CategoriesScreen#loadWords` | `selectWordsForCategory` | DONE |
| categories_list.html | Add word to category | click | `addWordToCategory` → `saveWordToCategory` | `POST /api/words-categorys/add` (`createNewWord` first if absent) | Categories | Link Word | `CategoryFacade#linkWordToCategory` | `insertWord` + `linkWordToCategory` | DONE — one word enforced |
| categories_list.html | Add Category | click | `openCategoryModal` → `saveCategory` → `fetch('/api/category/check')` → `POST /category/add` | `category_check`, `category_add` | Categories | Add Category | `CategoryFacade#categoryExists` + `#createCategory` | `INSERT word`, `INSERT category` | DONE — one word enforced |
| categories_list.html | delete | click | `deleteCategory` → `DELETE /api/categories/<id>` | `api_delete_category` | Categories | trash button | `CategoryFacade#deleteCategory` | `DELETE category` | DONE |
| categories_list.html | Find duplicates / Remove duplicates | click | `findDuplicates`, `removeDuplicates` | `GET /api/categories/find-duplicates`, `POST /api/categories/remove-duplicates` | — | — | — | — | NOT-BUILT (categories); keyword duplicates are reported on Keywords |
| categories_list.html | Export | click | `exportCategories` (client CSV) | — | — | — | — | — | NOT-BUILT |
| categories_list.html | `#totalCategoriesCount` … | — | `updateCounts` | client-side | Categories | count label | `Page#totalCount` | `COUNT(*)` | DONE |
| — | row view | eye / double-click | — | *(no category detail in the reference)* | Categories | eye button | `Router#openCategoryDetail` → `TermDetailScreen(CATEGORY)` | `RelationshipFacade#category` | DONE+ |

## 4. Category words — `templates/Category/category_words.html` · `category-words-page.js`

Java: right pane of `CategoriesScreen` ("Category Words").

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| category_words.html | word rows (id, text) | — | (server-rendered, client re-render) | `category_words`: `words_categorys ⨝ words` | Categories | word ListView | `CategoryFacade#getCategoryWords` | `selectWordsForCategory` | DONE |
| category_words.html | *(no per-word file count)* | — | — | relation exists in `words_paths` | Categories | per-word `N files` badge | `RelationshipFacade#wordFileCounts` | `path_word` COUNT DISTINCT | DONE+ |
| category_words.html | `#wordSearch` autocomplete | input | `searchWords` → `selectWord` | `GET /api/words/search?q=` | Categories | Link Word dialog | `CategoryFacade#linkWordToCategory` | `insertWord` (idempotent) | ADAPTED |
| category_words.html | Save word | click | `saveWordToCategory` | `POST /api/words-categorys/add` | Categories | Link Word | `CategoryFacade#linkWordToCategory` | `INSERT OR IGNORE word_category` | DONE |
| category_words.html | remove | click | `removeWord` → `DELETE /api/categories/<id>/words/<word_id>` | `api_remove_word_from_category` | Categories | Remove Word | `CategoryFacade#removeWordFromCategory` | `DELETE word_category` | DONE |
| category_words.html | `#sortBtn-word`, `#displayFormat`, pagination | click / change | `sortByColumn`, `sortWords`, `updateCounts` | client-side | Categories | — | — | — | ADAPTED |
| category_words.html | Export | click | `exportWords` | client CSV | — | — | — | — | NOT-BUILT |
| category_words.html | Back to categories | click | navigation | `/categories` | Categories | (same destination) | — | — | DONE |
| — | word row | double-click | *(no link in the reference)* | — | Categories | ListView | `Router#openWord` → `TermDetailScreen(WORD)` | `RelationshipFacade#categoryWord` | DONE+ |

## 5. Words list — `templates/Word/Word_list.html` · `words-list-page.js`

Java destination: `WordsScreen` ("Words").

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| Word_list.html | `#searchWords` | input / Enter | `fetchPage` | `GET /api/words?q=` | Words | search field | `WordFacade#searchWords` | `LIKE` on `word` | DONE |
| Word_list.html | row `usage_count` | — | `renderRows` | `COUNT(DISTINCT wp.path_id)` | Words | `Files` column | `RelationshipFacade#wordFileCounts` | `path_word` COUNT DISTINCT | DONE — a word in no category has no relation, shows 0 |
| Word_list.html | `#activeCount` / `#unusedCount`, `#statusFilter` | — / change | `updateSearchInfo` | `status` from usage | Words | `Files` column | as above | as above | ADAPTED |
| Word_list.html | `#sortBtn-word/-usage_count/-status` | click | `sortBy` → `updateSortIcons` | `?sort=&order=` | Words | table header click | TableView sort | in-memory | ADAPTED |
| Word_list.html | `#perPage`, paginator | change / click | `renderPaginator` | `?page=&per_page=` | Words | perPage, Previous/Next | `WordsScreen#onShow` | `LIMIT ? OFFSET ?` | DONE |
| Word_list.html | row view (`/words/{id}`) | click | navigation | `word_detail` | Words | eye / double-click | `Router#openWord` → `TermDetailScreen(WORD)` | `RelationshipFacade#categoryWord` | DONE |
| Word_list.html | row edit | click | `editWord` → `submitWord` | `PUT /api/words/<id>` | Words | pencil | `WordFacade#updateWord` | `UPDATE word` | DONE |
| Word_list.html | row delete | click | `deleteWord` | `DELETE /api/words/<id>` | Words | trash | `WordFacade#deleteWord` | `DELETE word` | DONE |
| Word_list.html | select all / Delete Selected | change / click | `toggleSelectAll`, `bulkDelete` | `POST /api/words/bulk-delete` | Words | multi-select, Delete Selected | `WordFacade#bulkDeleteWords` | `DELETE … IN (?)` | DONE |
| Word_list.html | `#bulkUpdateBtn` | click | `bulkUpdate` | *(no route)* | — | — | — | — | INERT-IN-REF |
| Word_list.html | Add Word | click | `openAddWordModal` → `submitWord` | `POST /api/words` | Words | Add Word | `WordFacade#createWord` | `INSERT word` | DONE |

## 6. Word detail — `templates/Word/Word_detail.html` · `word-detail-page.js`

Java destination: `TermDetailScreen(WORD)` ("Word Detail").

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| Word_detail.html | id / usage_count / text | — | server-rendered | `word_detail`: `words_paths` COUNT | Word Detail | heading, Related Files stat | `RelationshipFacade#categoryWord` | `countFilesForWord` | DONE |
| Word_detail.html | Edit | click | `editWord` → `PUT /api/words/<id>` → redirect | `update_word_api` | Words | pencil | `WordFacade#updateWord` | `UPDATE word` | ADAPTED (from list) |
| Word_detail.html | Delete | click | `deleteWord` → `DELETE` → redirect | `delete_word_api` | Words | trash | `WordFacade#deleteWord` | `DELETE word` | ADAPTED (from list) |
| Word_detail.html | Back | click | navigation | `/words` | Word Detail | Back to Words | `Router#open("Words")` | — | DONE |
| — | *(no file list, no categories)* | — | — | `words_paths`, `words_categorys` exist | Word Detail | Related Files table; Categories chips with counts | `TermSummary.files`, `.categories` | `selectFilesForWord`, `selectCategoriesForWord` | DONE+ |

## 7. File detail — `templates/file/file_detail.html` · `file-detail-page.js`

Java destination: `FileDetailScreen` ("File Detail"), `FullContentScreen` ("Full Content").

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| file_detail.html | Info: Type, Size, Created, Status, Source, Side | — | server-rendered | `file_detail`: `paths ⨝ hashs ⨝ sources ⨝ sides` | File Detail | File Record grid | `ContentFacade#getPath` + `LiveCase#byId` | `selectPathById`; engine `Item` | DONE |
| — | *(hash, MIME, modified, OCR not on page)* | — | — | `hashs.hash_value` stored; MIME/OCR not stored | File Detail | rows Path, Name, Size, SHA-256, MD5, MIME Type, Created, Modified, Processing Status, OCR, Review Status | `Item#sha256/md5/mediaType/created/modified/status/ocrApplied` | engine record | DONE+ (Java records these) |
| file_detail.html | `#contentText` paginated 50 000 chars; `#pageJump`, `goToPage`, `jumpToPage`, `changePageSize` | click / change | `goToPage` → `window.location = url` | `/file/<id>?page=&per_page=` → `contents` | File Detail / Full Content | Extracted Text preview (4 000 chars) → Full Content | `ContentFacade#getContentAsText` | `selectContentsByPath` | DONE |
| file_detail.html | `#searchInput`, `#caseSensitive`, `#wholeWord`, Find next/prev | input / click | `performSearch`, `highlightMatches`, `findNext`, `findPrevious`, `scrollToMatch`, `restoreSearchState` | client-side over loaded text | Full Content | find field, Find Next | `FullContentScreen` find | in-memory | DONE |
| file_detail.html | `copyContent`, `downloadContent`, `window.print`, `toggleFullscreen`, `shareFile` | click | same | client-side | Full Content | Copy All | clipboard | — | ADAPTED (copy); print/share/fullscreen are browser features |
| file_detail.html | Metadata tab: `#metaName`, `#metaNotes`, Save | click | `saveMetadata` | `PUT /api/file/<id>/details` *(name/notes)* | File Detail | Mark Read / Unread | `ContentFacade#setPathStatus` | `UPDATE path.file_status` | ADAPTED — notes editing lives on the engine's item notes |
| file_detail.html | Analysis tab: `#classificationChart` | — | `createClassificationChart` | `words_paths → words → words_categorys → categorys` share of word_count | File Detail | Categories chips (each with whole-case count) | `RelationshipFacade#forFile` | `selectCategoriesForPath` + words-of-path ⨝ word_category | DONE+ (as a list, not a pie) |
| file_detail.html | Analysis tab: "Top Keywords" `#frequencyChart` | — | `createFrequencyChart` | `get_word_frequencies(15)` — **words**, not keywords | File Detail | Category Words chips | `FileRelationships.categoryWords` | `selectWordsForPath` | DONE — only vocabulary words are shown; the reference's table is word frequency |
| — | *(keywords_paths not listed on page)* | — | — | relation exists | File Detail | Keywords chips `phrase ×hits (N files)` | `FileRelationships.keywords` | `selectPathKeywords` | DONE+ |
| — | chips | click | — | — | File Detail | keyword / category / word chip | `Router#openKeyword` / `#openCategoryDetail` / `#openWord` | — | DONE+ |
| file_detail.html | `exportFile` (`/file/<id>/export?format=pdf`) | click | `exportFile` | `files.export` | File Library | export via engine Exporter | `Exporter#export` | — | ADAPTED |
| file_detail.html | Reprocess (`/file/<id>/reprocess`) | click | `confirm` | `files.reprocess` | Errors | Retry | `FileProcessingFacade#retryFile` | pipeline | DONE |
| file_detail.html | Delete (`/file/<id>/delete`) | click | `confirm` | `files.delete` | — | — | — | — | NOT-BUILT — evidence is never deleted (design decision, see COVERAGE_MATRIX E04) |
| — | Classify | click | — | `path_category` attribution | File Detail | Classify | `CorpusDatabase#linkPathToCategory` | `INSERT OR IGNORE path_category` | DONE |

## 8. Search — `templates/Search/search.html` · `search-page.js` · `search_service.py`

Java destination: `SearchScreen` ("Search").

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| search.html | `#searchForm` / `#searchQuery` | submit | form GET | `/search?q=` → `full_text_search`: per term `file_name ILIKE` OR EXISTS `words_paths ⨝ words` | Search | query field, Search | `SearchFacade#search` | Lucene `LiveCase#searchNow` | DONE — index instead of ILIKE |
| search.html | `#useFuzzy`, `#useExpansion`, `#useBM25`, `#useAdvanced` | change | `getAdvancedSearchOptions` → `performEnhancedSearch` → `endpoints.search({query,page,per_page,sort_by,use_*})` | `/api/search` | Search | filters (type, source, aspect, dates, sort) | `SearchCriteria` | Lucene query + filters | ADAPTED — Lucene scoring is BM25 already |
| search.html | result `<a href=/file/{id}>` name, Source, Side, Date, type badge | click | `displaySearchResults` | rows: id, file_name, file_path, file_type, file_size, file_date, file_status, source_name, side_name, relevance_score, line_matches | Search | results table, double-click | `Router#openFile` | — | DONE |
| search.html | `highlightSearchTerms` | — | client-side | `line_matches` | Search | Preview pane snippets | `SearchResultDto.snippets` | Lucene highlighter | DONE |
| search.html | `goToPage` | click | `performEnhancedSearch(page)` | `?page=` | Search | Previous / Next, perPage | `SearchScreen#runSearch` | Lucene paging | DONE |
| — | *(match location not reported)* | — | — | relevance CASE: name 2.0 / word 1.0 | Search | Search Everywhere table, `Matched In` column | `RelationshipFacade#search` | `selectFilesByNameOrPath`, `selectFilesByMetadata`, `selectFilesByContent`, `selectKeywordsMatching` → `selectFilesForKeyword`, `selectCategoriesMatching` → `selectFilesForCategory`, `selectCategoryWordsMatching` → `selectFilesForWord` | DONE+ |
| — | scope | change | — | — | Search | Scope ComboBox | `RelationshipFacade.Scope` | as above | DONE+ |
| keywords_list.html | Update associations | click | `updateKeywordAssociations` | `POST /api/keywords/update-associations` | Search | Update associations | `RelationshipAnalyzer#analyzeAll` | `clearDerivedRelations`, `linkPathToWord`, `linkPathToKeyword` | DONE |
| search.html | Save Search / Saved searches | click | — | `/api/search/saved` | Search | Save Search | `SearchHistoryFacade` | `saved_search` | DONE |
| search.html | Export | click | — | `/api/search/export` | Search | Export CSV | `SearchScreen#exportCsv` | — | DONE |

## 9. Archives (grid/section navigation) — `archives.py` · `event-delegation.js` · `section-view.js` · `grid-renderer.js` · `item-view.js`

Java destination: `ArchivesScreen` ("Archives") plus the detail destinations above.

| Reference | Element | Event | JS function | Python route → data | Java screen | Java control | Java function | DB / Lucene op | Status |
|---|---|---|---|---|---|---|---|---|---|
| archives | section tile (`data-section`) | click (delegated) | `navigateToSection` | `/api/archives/{files,categories,keywords,titles,sources,sides}` | Archives | section list | `ArchivesScreen` | per-section counts | DONE |
| archives | category card label "N Files / N Words" | — | `grid-renderer` | `COUNT(DISTINCT p.id)`, `COUNT(DISTINCT word_id)` | Categories | `Files` column + words pane | `RelationshipFacade#categoryFileCounts` | as §3 | DONE |
| archives | keyword card "N files" | — | `grid-renderer` | `keywords_paths` COUNT | Keywords | `Files` column | `RelationshipFacade#keywordFileCounts` | as §1 | DONE |
| archives | item card (`data-item-id`) | click | `navigateToItem` → `endpoints.itemFiles(section,id,{page,limit})` | section `category` → `paths ⨝ words_paths ⨝ words_categorys`; `keywords` → `paths ⨝ keywords_paths` | Keyword / Category / Word Detail | Related Files table | `RelationshipFacade#filesForKeyword / #filesForCategory / #filesForWord` | `selectFilesFor*` | DONE |
| archives | file card `data-file-id` details / open | click | `file-view` | `/file/<id>` | detail tables | double-click row | `Router#openFile` | — | DONE |
| archives | file card export / checkbox | click | `file-view` | `/api/archives/files/export` | File Library | Exporter | `Exporter#export` | — | ADAPTED |
| archives | section params `limit, search, category_id, source_id, side_id, date_from, date_to, sort_by, sort_dir, cursor` | change | `section-view` | `/api/archives/files?…` | Search | filters | `SearchCriteria` | Lucene filters | ADAPTED |

## 10. JS audit checklist

Each row states where the reference's mechanism is handled in Java.

| Concern | Reference mechanism | Java mechanism |
|---|---|---|
| onclick attributes | `onclick="sortBy('text')"`, `onclick="deleteKeyword(id)"` … | `Button#setOnAction`; every handler body calls an operation (`ArchitectureInvariantsTest#everyControlEndsInAnOperation`) |
| addEventListener | `keydown` (Enter/Escape in modals), `input` (autocomplete), `change` (filters), `click` | `TextField#setOnAction`, ComboBox value listeners, `TableRow#setOnMouseClicked`, dialogs' own Enter/Escape |
| Event delegation | `event-delegation.js` on `[data-section]`, `[data-item-id]`, `[data-file-id]` | `TableView#setRowFactory` — one handler per table, row item resolved on click |
| Form submission | `#searchForm` GET, `#addKeywordForm`, `#categoryForm`, `#wordForm`, `#metadataForm` | `TextInputDialog` / `ChoiceDialog` / form panes → facade call; validation errors surface as `FacadeException` messages |
| fetch / XHR | `fetch('/api/…')`, `apiClient`, `endpoints.*` | direct facade calls; no HTTP anywhere (`ArchitectureInvariantsTest#noWebApiOrPythonRuntimeDependency`) |
| URL building / query params | `URLSearchParams`, `?page=&per_page=&sort=&order=&q=&status=&category=` | `SearchCriteria` builder; `limit/offset` arguments; `Router#open*(id)` |
| Dynamic rows | `renderRows`, `renderTableView`, `innerHTML` | `ObservableList#setAll` bound to `TableView` |
| Modals | Bootstrap modals (`addKeywordModal`, `categoryModal`, `wordModal`, `duplicatesModal`, `addWordToCategoryModal`) | JavaFX dialogs (`TextInputDialog`, `ChoiceDialog`, `Alert`) |
| Dropdowns / select2 | `#wordSelect`, `#keywordCategory` | `ComboBox` |
| Autocomplete | `searchWordsForKeyword`, `searchCategoriesForKeyword`, `searchWords` against `/api/*/search?q=` | direct entry validated by `Terms`; category chosen from a ComboBox of real categories |
| Filter / sort / pagination | server (`keywords`, `words`) and client (`categories`, `category_words`) | server-side `LIMIT/OFFSET` on Keywords/Words/File Library; `TableView` column sort; Categories loads 500 |
| Async / loading states | `duplicatesLoading`, `await fetch` | synchronous facade calls on small tables; batch work reports `Progress` (`RelationshipAnalyzer.Progress`, `BatchAnalysisFacade.Progress`) |
| Error handling | `try/catch` → `showToast(…, 'error')` | `FacadeException` → `Alert` (`err(...)`) or inline label; the case record is never left half-written (transactions in `CorpusDatabase`) |
| Notifications / toast | `showToast`, `#toastContainer` | `NotificationsScreen` for persisted notices; `Alert` for immediate ones |
| State | `keywords-list-page-data` JSON, `?t=Date.now()` reloads, `restoreSearchState` | screen fields (`offset`, `total`, `fileCounts`), refreshed on `onShow` |
| DOM replacement | `innerHTML`, `categoriesDisplayContainer` swaps | `getChildren().setAll(...)` on chips/stat rows |
| Keyboard | Enter submits search / modals, Escape closes | `setOnAction` on fields; dialog defaults |
| Navigation | `window.location.href = /keywords/${id}`, `/words/${id}`, `/categories/${id}/words`, `/file/${id}`, `/search?q=` | `Router#openKeyword / openWord / openCategory / openCategoryDetail / openFile / openSearch`, with `back()` history |
| Export | client-side CSV (`exportKeywords`, `exportCategories`, `exportWords`), server export (`/api/search/export`, `/file/<id>/export`) | `SearchScreen#exportCsv`; engine `Exporter` |
| Preview | `line_matches` highlight, `contentText` viewer | Search preview pane; File Detail extracted-text preview; Full Content |

## 11. Known gaps (stated, not hidden)

| Gap | Where | Why |
|---|---|---|
| Merge duplicate keywords / remove duplicate categories | §1, §3 | Duplicates are detected and reported (`KeywordFacade#findDuplicates`); merging rewrites `path_keyword` edges and needs a rule for which phrase survives. Not built. |
| Client-side CSV export on Keywords / Categories / Category Words | §1, §3, §4 | Search exports; the term lists do not yet. |
| Delete a file | §7 | Evidence is never deleted from a case by design (COVERAGE_MATRIX E04). |
| Grid / list / compact display formats | §3, §4 | Table only. |
| Bulk "update" buttons | §1, §5 | Inert in the reference; nothing to reproduce. |

## 12. Tests that hold this matrix to account

| Test | What it proves |
|---|---|
| `RelationshipModelTest#invariants` | keyword ≥ 3 words, category = 1 word, category word = 1 word; display form survives normalisation |
| `RelationshipModelTest#keywordBidirectional` | keyword → files == files → keyword; per-file hits are real (2 in the invoice, 1 in the ledger) |
| `RelationshipModelTest#categoryWordBidirectional` | word → files == files → word |
| `RelationshipModelTest#categoryBidirectional` | category → files (through words) == files → category; every list count equals its detail count across the whole case |
| `RelationshipModelTest#termToTerm` | keyword ↔ category ↔ word edges are exactly the schema's |
| `RelationshipModelTest#duplicateRelationship` | double-linking and re-analysis never duplicate an edge; `totals()` agrees |
| `RelationshipModelTest#searchByWordCount` | 3-word keyword, 2-word fragment, 1-word term |
| `RelationshipModelTest#searchByCategoryAndWord` | category and category-word match types; no content match fabricated for a category name absent from text |
| `RelationshipModelTest#searchKeywordForms` | exact and partial keyword, case-insensitive |
| `RelationshipModelTest#searchByLocation` | file name vs path vs content vs metadata (type, source) told apart |
| `RelationshipModelTest#searchEdges` | empty result, blank query, one file matched several ways, no duplicate (file, type) rows |
| `RelationshipModelTest#fileDetail` | a file lists exactly the terms related to it; an unrelated file lists none |
| `BatchAnalysisTest#hitsComeFromRealAnalysis`, `EndToEndScenarioTest#fullScenario` | registration derives edges; a batch run agrees with them rather than adding to them |
| `ArchitectureInvariantsTest#everyControlEndsInAnOperation`, `#everyScreenIsReachableFromTheNavigation`, `#everyBackendCapabilityHasAWayIn` | every new control, screen (`Category Detail`) and facade accessor (`relationships()`, `relationshipAnalyzer()`) is wired |
| `CoverageMatrixTest` | rows L01–L12 name Java and tests that exist |
| `UiParityTest` | screens construct with the routerless constructors used by the harness |

End-to-end UI interaction (clicking through Keywords → Keyword Detail → File Detail →
Category Detail) needs a JavaFX runtime with a graphics device; it is not runnable in
this environment and is listed in `run-tests.sh` as environment-dependent, the same way
the icon-set check is.
