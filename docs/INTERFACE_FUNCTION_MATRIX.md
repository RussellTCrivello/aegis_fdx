# Interface → Function Matrix

Machine-readable source: **`docs/interface-function-matrix.tsv`** (120 rows, 16 audit columns + id). This page is rendered from it by `tools/render_interface_matrix.py`; `InterfaceFunctionMatrixTest` fails the build if the TSV names a Java handler, facade, database operation or test that does not exist, if a screen or button label in the JavaFX interface has no row, or if a non-VERIFIED row lacks an explanation.

Every interactive element of the reference (`RussellTCrivello/file_analysis`: templates, `static/js/pages/*.js`, `Api/routes`, `Api/services`, `database/`) is traced forward to the Java control that reproduces its observable behaviour, and every Java control is traced back to the reference behaviour it exists for. The reference is a behavioural specification only — no Python, Flask route, template or JavaScript is executed, embedded or called by the Java application (`ArchitectureInvariantsTest#noWebApiOrPythonRuntimeDependency`).

## Status vocabulary

| Status | Meaning | Rows |
|---|---|---|
| **VERIFIED** | The Java control performs the same observable operation on the same data (case.db / Lucene) and an executable test named in the row exercises it. | 82 |
| **ADAPTED** | Same purpose and same stored result, different mechanism appropriate to a desktop (dialog instead of modal, table sort instead of `?sort=` reload, search on the Search destination instead of an inline filter). The note says what differs. | 27 |
| **LIMITED** | Part of the reference behaviour is reproduced; the missing part and the reason are stated. | 2 |
| **UNSUPPORTED** | Deliberately not reproduced, with the reason (evidence is never deleted; render-format toggles carry no information; a flag nothing reads). | 5 |
| **REFERENCE-INERT** | The reference control does nothing observable (no route behind the handler, or a stored flag nothing evaluates). Reproducing it would be a fake feature. | 4 |
| **ENVIRONMENT-LIMITED** | Implemented and covered, but the confirming step needs something this build environment lacks (a display for a GUI click-through). None at present: GUI click-through is recorded per suite in `VERIFICATION_REPORT.md` instead. | 0 |

Nothing is ABSENT: every reference element has a Java destination or an explained status.

## Columns

`reference_destination`, `reference_template`, `html_element`, `js_handler`, `js_api_operation`, `python_route`, `python_backend_operation` describe the reference. `java_destination`, `java_control`, `java_handler`, `java_facade`, `db_index_operation`, `expected_visible_result`, `test`, `status`, `note` describe the Java application. Java symbols are `Class#method` and are resolved against the source tree by the test.

## Rows that are not VERIFIED

### ADAPTED (27)

| Id | Reference element | Java destination | Why |
|---|---|---|---|
| K01 | #searchKeywords | Search | Keyword text search lives on the Search destination (scope Keyword); the Keywords list itself is paged, not filtered. |
| K02 | #statusFilter Active/Unused | Keywords | Status is shown as the real count rather than a two-state filter. |
| K03 | #categoryFilter | Keywords | Category is a column; filtering by it is a header sort in the table. |
| K04 | #sortBtn-text/-usage_count/-status | Keywords | JavaFX TableView column sort over the loaded page rather than a server round-trip. |
| K16 | #keywordWordInput autocomplete | Keywords | The reference builds a keyword from word ids chosen one at a time; here the phrase is typed and validated. Same stored result. |
| KD4 | Edit (href /keywords/add?keyword_id=) | Keyword Detail | Editing happens in the list row rather than a separate add page. |
| KD5 | Delete | Keywords | Delete is offered on the list row, not on the detail page. |
| C02 | #categorySearch | Search | Category text search is on the Search destination; the list shows up to 500 categories at once. |
| C05 | #itemsPerPage, #pagination | Categories | Categories are few; a single table replaces client-side paging. |
| CW3 | #wordSearch autocomplete, Save | Categories | Direct entry instead of autocomplete; the stored result is identical. |
| CW5 | #sortBtn-word, #displayFormat, pagination, Export | Words | Export is offered on the Words destination for the whole vocabulary rather than per category. |
| W03 | #activeCount/#unusedCount, #statusFilter | Words | The count column carries the same information as the Active/Unused tiles. |
| W04 | #sortBtn-word/-usage_count/-status | Words | Column sort reorders the rows already loaded in the table; the reference re-queries the server with ORDER BY. Same visible ordering, no round trip. |
| WD2 | Edit / Delete | Words | Edit and delete live on the list row; the detail links back to it. |
| CD1 | (none) | Category Detail | The reference has only a words page for a category. This detail exists because the relation is queryable; it shows only recorded edges. |
| F04 | copyContent, downloadContent, print, toggleFullscreen, shareFile | Full Content | Copy is provided; print, share and fullscreen are browser conveniences without a desktop counterpart here. |
| F05 | Metadata tab #metaName #metaNotes Save | File Detail | Review status is the editable field; item notes are engine-side. |
| F06 | Analysis tab #classificationChart | File Detail | Shown as a clickable list rather than a pie; the underlying relation is the same. |
| F07 | Analysis tab Top Keywords #frequencyChart | File Detail | The reference's table is word frequency; only vocabulary words are related here, and they are clickable. |
| F09 | exportFile (/file/id/export?format=pdf) | Import / Export | Per-file PDF rendering is not offered; the registry and search results export, and native export runs through the engine Exporter. |
| S02 | #useFuzzy #useExpansion #useBM25 #useAdvanced | Search | Lucene scoring is BM25 already; fuzzy and expansion are query syntax (~, wildcards) rather than checkboxes. |
| AR4 | limit, search, category_id, source_id, side_id, date_from, date_to, sort_by, sort_dir, cursor | Search | Filtering by source, aspect, date and sort is done on Search over the index rather than on a paged archive grid. |
| FL1 | #smartSearch, filters, sort, perPageFiles, quickPreview, bulkAnalyze, bulkExport, bulkDelete | File Library | Bulk analyse lives on Batch Analysis; bulk delete is not offered (F11); filtering by type/source/aspect/status is on Search. |
| DB2 | filters, doughnut/bar charts, section tables | Charts | Drawn on a JavaFX canvas by ChartPane instead of a browser charting library. |
| DB5 | method selector, analyse file / folder / database | Analysis | The three reference modes collapse into one destination that reads the same relations. |
| ST1 | branding, theme colours, toggles (autoProcess, autoAnalyze, animations, breadcrumbs, logging), language | Settings | Engine options that change behaviour are exposed; branding and colour theming are presentation-only and deferred to the localisation/theming phase (LOCALIZATION_PREPARATION.md). |
| G04 | toast notifications | (any) | Dialogs and inline labels instead of toasts. |

### LIMITED (2)

| Id | Reference element | Java destination | Why |
|---|---|---|---|
| DB6 | search, per page, copy, add to contacts, show files, export | Email Words | Add-to-contacts has no contacts store in this application; copy and file listing are provided. |
| ST2 | /set_language/<lang> | (none) | Deliberately deferred: localisation begins after the English interface is stable. Preparation is documented in LOCALIZATION_PREPARATION.md. |

### UNSUPPORTED (5)

| Id | Reference element | Java destination | Why |
|---|---|---|---|
| C04 | #displayFormat table/grid/list/compact | Categories | Alternative render formats of the same rows carry no additional information; one table is kept. |
| F11 | Delete (/file/id/delete) | (none) | Evidence is never deleted from a case by design (COVERAGE_MATRIX E04). Deletion would break the hash chain and the audit trail. |
| SR3 | toggleSourceStatus | Sources | The reference flag is never read anywhere else in the application; storing a status no view consults would be decoration. |
| G02 | error pages | (inline) | HTTP error pages have no desktop equivalent (COVERAGE_MATRIX R06). |
| G03 | partials | Fas | Template partials are not destinations (COVERAGE_MATRIX R08). |

### REFERENCE-INERT (4)

| Id | Reference element | Java destination | Why |
|---|---|---|---|
| K12 | #bulkUpdateBtn | Keywords | The reference handler assigns a category client-side and never posts it; reproducing a control that does nothing would be a fake feature. |
| W09 | #bulkUpdateBtn | Words | Handler has no route behind it in the reference. |
| S14 | #alertModal toggleAlert saveAlert | (none) | The reference stores alert flags but nothing ever evaluates or sends them; a control that promises alerts and delivers none is not reproduced. |
| BA2 | #scheduleSelect, off-hours, #resourceLimit | Batch Analysis | Schedule and resource controls in the reference are never read by the server (COVERAGE_MATRIX R01). |

## Full matrix

### Keywords list — `Keyword/keywords_list.html` · `keywords-list-page.js`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| K01 | #searchKeywords | applyFilters | keywords.api_keywords → keywords LEFT JOIN keywords_paths ILIKE | Search / Scope=Keyword + query | SearchScreen#runRelationshipSearch → RelationshipFacade#search | CorpusDatabase#selectKeywordsMatching | Files reached through matching keywords, match type KEYWORD | RelationshipModelTest#searchKeywordForms | ADAPTED |
| K02 | #statusFilter Active/Unused | applyFilters | keywords.api_keywords → EXISTS keywords_paths | Keywords / Files column | KeywordsScreen#onShow → RelationshipFacade#keywordFileCounts | CorpusDatabase#selectKeywordsWithFileCounts | Whole-case file count per row; 0 means unused | RelationshipModelTest#categoryBidirectional | ADAPTED |
| K03 | #categoryFilter | applyFilters | keywords.api_keywords → keywords JOIN categorys JOIN words | Keywords / Category column | KeywordsScreen#onShow → KeywordFacade#listKeywords | CorpusDatabase#selectAllKeywords | Category word shown per row | FacadeParityTest | ADAPTED |
| K04 | #sortBtn-text/-usage_count/-status | sortBy | keywords.api_keywords → ORDER BY | Keywords / table header | KeywordsScreen#build → KeywordFacade#listKeywords | in-memory sort of the page | Rows reorder | UiParityTest | ADAPTED |
| K05 | #perPage, paginator | changePageSize, renderPaginator | keywords.api_keywords → LIMIT OFFSET | Keywords / perPage, Previous, Next | KeywordsScreen#onShow → KeywordFacade#listKeywords | CorpusDatabase#selectAllKeywords | Page label from-to of total; rows change | FacadeParityTest | VERIFIED |
| K06 | row usage badge | renderRows | COUNT(kp.path_id) → path_keyword | Keywords / Files column | KeywordsScreen#onShow → RelationshipFacade#keywordFileCounts | CorpusDatabase#selectKeywordsWithFileCounts | COUNT(DISTINCT path_id) over the whole case | RelationshipModelTest#categoryBidirectional | VERIFIED |
| K07 | row Duplicate flag / Merge duplicates | mergeAllDuplicates | keywords.merge_all_duplicates → LOWER(text) groups | Keywords / Find Duplicates | KeywordsScreen#build → KeywordFacade#mergeDuplicates | CorpusDatabase#mergeKeyword | Groups listed; on confirm merged into the oldest, edges moved | RelationshipModelTest#mergeDuplicates | VERIFIED |
| K08 | row view (href /keywords/id) | navigation | keywords.keyword_detail → keywords_paths COUNT | Keywords / eye button / double-click row | KeywordsScreen#build → RelationshipFacade#keyword | CorpusDatabase#selectFilesForKeyword | Keyword Detail opens | RelationshipModelTest#keywordBidirectional | VERIFIED |
| K09 | row edit | editKeywordInModal, submitUpdateKeyword | keywords.update_keyword → UPDATE keywords | Keywords / pencil button | KeywordsScreen#build → KeywordFacade#updateKeyword | CorpusDatabase#updateKeyword | Phrase renamed; rejected under three words | RelationshipModelTest#storageInvariants | VERIFIED |
| K10 | row delete | deleteKeyword | keywords.delete_keyword_api → DELETE keywords | Keywords / trash button | KeywordsScreen#build → KeywordFacade#deleteKeyword | CorpusDatabase#deleteKeyword | Row disappears; edges cascade | FacadeParityTest | VERIFIED |
| K11 | #selectAllCheckbox, Delete Selected | toggleSelectAll, bulkDelete | keywords.bulk_delete → DELETE IN | Keywords / multi-select + Delete Selected | KeywordsScreen#build → KeywordFacade#bulkDeleteKeywords | CorpusDatabase#deleteKeyword | Confirmation, then rows removed | FacadeParityTest | VERIFIED |
| K12 | #bulkUpdateBtn | bulkUpdate | (none) → (none) | Keywords / (none) | (none) → (none) | (none) | Nothing happens in the reference | UiParityTest | REFERENCE-INERT |
| K13 | #updateKeywordsBtn Update associations | updateKeywordAssociations | keywords.update_keyword_associations → contents -> extract_keywords_fast -> keywords_paths | Search / Update associations | SearchScreen#updateAssociations → RelationshipAnalyzer#analyzeAll | CorpusDatabase#clearDerivedRelations, linkPathToKeyword, linkPathToWord | Label reports files scanned and links written; counts refresh | RelationshipModelTest#duplicateRelationship | VERIFIED |
| K14 | Export | exportKeywords | (none) → (none) | Keywords / Export CSV | KeywordsScreen#build → ExportFacade#exportTermsCsv | RelationshipFacade#keywords | CSV with id, text, normalized, word_count, file_count saved where chosen | FacadeParityTest | VERIFIED |
| K15 | Add Keyword modal, Save | openAddKeywordModal, saveKeyword | keywords.keyword_check, keywords.keywords_add → validate_keyword_text, INSERT keywords | Keywords / Add Keyword dialog | KeywordsScreen#openForm → KeywordFacade#createKeyword | CorpusDatabase#insertKeyword | New row; fewer than three words refused with a message | RelationshipModelTest#invariants | VERIFIED |
| K16 | #keywordWordInput autocomplete | searchWordsForKeyword | words.api_words → words ILIKE | Keywords / phrase text field | KeywordsScreen#openForm → Terms#requireKeyword | (validation only) | Phrase typed directly and validated | RelationshipModelTest#invariants | ADAPTED |
| K17 | #keywordCategoryInput autocomplete | searchCategoriesForKeyword | categories.api_categories_search → categorys JOIN words ILIKE | Keywords / category ComboBox | KeywordsScreen#openForm → CategoryFacade#listCategories | CorpusDatabase#selectAllCategories | Only existing categories offered | FacadeParityTest | VERIFIED |

### Keyword detail — `Keyword/keyword_detail.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| KD1 | id, usage_count, file_types, text, last_used | (server-rendered) | keywords.keyword_detail → keywords_paths COUNT, DISTINCT file_type | Keyword Detail / heading, stat cards | TermDetailScreen#onShow → RelationshipFacade#keyword | CorpusDatabase#countFilesForKeyword | Phrase, word count, normalised form, Related Files count | RelationshipModelTest#keywordBidirectional | VERIFIED |
| KD2 | (no file list in reference) | (none) | (none) → keywords_paths (queryable, not shown) | Keyword Detail / Related Files table | TermDetailScreen#build → RelationshipFacade#filesForKeyword | CorpusDatabase#selectFilesForKeyword | File, path, source, type, hits; double-click opens File Detail | RelationshipModelTest#keywordBidirectional | VERIFIED |
| KD3 | category name | (server-rendered) | keywords.keyword_detail → keywords.category | Keyword Detail / Categories chip | TermDetailScreen#build → RelationshipFacade#keyword | CorpusDatabase#selectCategoryById | Chip with the category's own file count; click opens Category Detail | RelationshipModelTest#termToTerm | VERIFIED |
| KD4 | Edit (href /keywords/add?keyword_id=) | navigation | keywords.keywords_add → UPDATE keywords | Keyword Detail / Open in list | TermDetailScreen#build → KeywordFacade#updateKeyword | CorpusDatabase#updateKeyword | Returns to Keywords where the pencil edits | FacadeParityTest | ADAPTED |
| KD5 | Delete | deleteKeyword | keywords.delete_keyword_api → DELETE keywords | Keywords / trash button | KeywordsScreen#build → KeywordFacade#deleteKeyword | CorpusDatabase#deleteKeyword | Deleted from the list | FacadeParityTest | ADAPTED |
| KD6 | Back | navigation | keywords.keywords_list → (none) | Keyword Detail / Back to Keywords | TermDetailScreen#build → FasApp#open | (none) | Keywords list shown | ArchitectureInvariantsTest#everyControlEndsInAnOperation | VERIFIED |
| KD7 | (none) | (none) | (none) → (none) | Keyword Detail / Search for this term | TermDetailScreen#build → FasApp#openSearch | LiveCase#searchNow | Search runs with the quoted phrase | EndToEndScenarioTest | VERIFIED |

### Categories list — `Category/categories_list.html` · `categories-list-page.js`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| C01 | row file_count / word_count | renderTableView | categories.categories_list → COUNT(DISTINCT p.id), COUNT(DISTINCT wc.word_id) | Categories / Files column; words pane header | CategoriesScreen#onShow → RelationshipFacade#categoryFileCounts | CorpusDatabase#selectCategoriesWithFileCounts | Distinct files reached through any of the category's words, whole case | RelationshipModelTest#categoryBidirectional | VERIFIED |
| C02 | #categorySearch | applyFiltersAndRender | (none) → (none) | Search / Scope=Category | SearchScreen#runRelationshipSearch → RelationshipFacade#search | CorpusDatabase#selectCategoriesMatching | Files reached through matching categories, match type CATEGORY | RelationshipModelTest#searchByCategoryAndWord | ADAPTED |
| C03 | #sortBy, #sortBtn-name/-files/-words | sortCategories, sortByColumn | (none) → (none) | Categories / table header | CategoriesScreen#build → CategoryFacade#listCategories | in-memory sort | Rows reorder | UiParityTest | VERIFIED |
| C04 | #displayFormat table/grid/list/compact | changeDisplayFormat | (none) → (none) | Categories / (table only) | CategoriesScreen#build → (none) | (none) | Table view only | UiParityTest | UNSUPPORTED |
| C05 | #itemsPerPage, #pagination | changePageSize, changePage | (none) → (none) | Categories / (none) | CategoriesScreen#onShow → CategoryFacade#listCategories | CorpusDatabase#selectAllCategories | Up to 500 categories in one scrollable table | FacadeParityTest | ADAPTED |
| C06 | View Words (href /categories/id/words) | viewCategoryWords | categories.category_words → words_categorys JOIN words | Categories / select row -> Category Words pane | CategoriesScreen#loadWords → CategoryFacade#getCategoryWords | CorpusDatabase#selectCategoryWords | Right pane lists the words with per-word file badges | DestinationCoverageTest | VERIFIED |
| C07 | Add word to category, Save | addWordToCategory, saveWordToCategory | categories.words_categorys_add → INSERT words, INSERT words_categorys | Categories / Link Word | CategoriesScreen#build → CategoryFacade#linkWordToCategory | CorpusDatabase#linkWordToCategory | Word appears in the pane; multi-word input refused | RelationshipModelTest#invariants | VERIFIED |
| C08 | Add Category modal, Save | openCategoryModal, saveCategory | categories.category_check, categories.category_add → INSERT words, INSERT categorys | Categories / Add Category | CategoriesScreen#build → CategoryFacade#createCategory | CorpusDatabase#insertCategory | New row; multi-word refused with a message | RelationshipModelTest#invariants | VERIFIED |
| C09 | delete | deleteCategory | categories.api_delete_category → DELETE categorys | Categories / trash button | CategoriesScreen#build → CategoryFacade#deleteCategory | CorpusDatabase#deleteCategory | Row disappears | FacadeParityTest | VERIFIED |
| C10 | Find duplicates / Remove duplicates | findDuplicates, removeDuplicates | categories.api_find_duplicate_categories, categories.api_remove_duplicate_categories → LOWER(word) groups | Categories / Find Duplicates | CategoriesScreen#build → CategoryFacade#mergeDuplicates | CorpusDatabase#mergeCategory | Groups listed; on confirm merged into the oldest, words/keywords/attributions moved | RelationshipModelTest#mergeDuplicates | VERIFIED |
| C11 | Export | exportCategories | (none) → (none) | Categories / Export CSV | CategoriesScreen#build → ExportFacade#exportTermsCsv | RelationshipFacade#categories | CSV of every category with whole-case file counts | FacadeParityTest | VERIFIED |
| C12 | #totalCategoriesCount | updateCounts | (none) → (none) | Categories / count label | CategoriesScreen#onShow → CategoryFacade#listCategories | CorpusDatabase#countCategories | N categories | FacadeParityTest | VERIFIED |
| C13 | (none) | (none) | (none) → (none) | Categories / eye button / double-click row | CategoriesScreen#build → RelationshipFacade#category | CorpusDatabase#selectFilesForCategory | Category Detail opens | RelationshipModelTest#categoryBidirectional | VERIFIED |

### Category words — `Category/category_words.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| CW1 | word rows id, text | (server-rendered) | categories.category_words → words_categorys JOIN words | Categories / Category Words ListView | CategoriesScreen#loadWords → CategoryFacade#getCategoryWords | CorpusDatabase#selectCategoryWords | Words of the selected category | DestinationCoverageTest | VERIFIED |
| CW2 | (no per-word count in reference) | (none) | (none) → words_paths (queryable, not shown) | Categories / N files badge per word | CategoriesScreen#loadWords → RelationshipFacade#wordFileCounts | CorpusDatabase#selectCategoryWordsWithFileCounts | Badge with COUNT(DISTINCT path_id) per word | RelationshipModelTest#categoryWordBidirectional | VERIFIED |
| CW3 | #wordSearch autocomplete, Save | searchWords, selectWord, saveWordToCategory | words.api_words, categories.words_categorys_add → INSERT OR IGNORE words_categorys | Categories / Link Word dialog | CategoriesScreen#build → CategoryFacade#linkWordToCategory | CorpusDatabase#linkWordToCategory | Word linked, created first if new | RelationshipModelTest#invariants | ADAPTED |
| CW4 | remove | removeWord | categories.api_remove_word_from_category → DELETE words_categorys | Categories / Remove Word | CategoriesScreen#build → CategoryFacade#removeWordFromCategory | CorpusDatabase#unlinkWordFromCategory | Word leaves the pane; category file count refreshes | FacadeParityTest | VERIFIED |
| CW5 | #sortBtn-word, #displayFormat, pagination, Export | sortByColumn, sortWords, exportWords | (none) → (none) | Words / Export CSV | WordsScreen#build → ExportFacade#exportTermsCsv | RelationshipFacade#categoryWords | All category words exported with counts | FacadeParityTest | ADAPTED |
| CW6 | (none) | (none) | (none) → (none) | Categories / double-click word | CategoriesScreen#build → RelationshipFacade#categoryWord | CorpusDatabase#selectFilesForWord | Word Detail opens | RelationshipModelTest#categoryWordBidirectional | VERIFIED |

### Category detail (Java-only reverse view)

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| CD1 | (none) | (none) | (none) → categorys, words_categorys, keywords, words_paths | Category Detail / heading, stats, Related Terms, Related Files | TermDetailScreen#onShow → RelationshipFacade#category | CorpusDatabase#selectFilesForCategory | Files through words or attribution; keywords; words; each clickable | RelationshipModelTest#categoryBidirectional | ADAPTED |
| CD2 | (none) | (none) | (none) → (none) | Category Detail / Manage words | TermDetailScreen#build → FasApp#openCategory | (none) | Categories list opens with this category selected | ArchitectureInvariantsTest#everyControlEndsInAnOperation | VERIFIED |

### Words list — `Word/Word_list.html` · `words-list-page.js`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| W01 | #searchWords | fetchPage | words.api_words → words ILIKE | Words / search field | WordsScreen#onShow → WordFacade#searchWords | CorpusDatabase#searchWords | Rows filtered by substring | FacadeParityTest | VERIFIED |
| W02 | row usage_count | renderRows | COUNT(DISTINCT wp.path_id) → words_paths | Words / Files column | WordsScreen#onShow → RelationshipFacade#wordFileCounts | CorpusDatabase#selectCategoryWordsWithFileCounts | Whole-case count; a word in no category has no edges and shows 0 | RelationshipModelTest#categoryWordBidirectional | VERIFIED |
| W03 | #activeCount/#unusedCount, #statusFilter | updateSearchInfo | words.api_words → EXISTS words_paths | Words / Files column | WordsScreen#onShow → RelationshipFacade#wordFileCounts | CorpusDatabase#selectCategoryWordsWithFileCounts | Count shown per row instead of an aggregate tile | FacadeParityTest | ADAPTED |
| W04 | #sortBtn-word/-usage_count/-status | sortBy | words.api_words → ORDER BY | Words / table header | WordsScreen#build → WordFacade#searchWords | in-memory sort of the page | Rows reorder | UiParityTest | ADAPTED |
| W05 | #perPage, paginator | renderPaginator | words.api_words → LIMIT OFFSET | Words / perPage, Previous, Next | WordsScreen#onShow → WordFacade#searchWords | CorpusDatabase#searchWords | Page label and rows change | FacadeParityTest | VERIFIED |
| W06 | row view (href /words/id) | navigation | words.word_detail → words_paths COUNT | Words / eye button / double-click | WordsScreen#build → RelationshipFacade#categoryWord | CorpusDatabase#selectFilesForWord | Word Detail opens | RelationshipModelTest#categoryWordBidirectional | VERIFIED |
| W07 | row edit | editWord, submitWord | words.update_word_api → UPDATE words | Words / pencil button | WordsScreen#build → WordFacade#updateWord | CorpusDatabase#updateWord | Renamed; multi-word refused | RelationshipModelTest#storageInvariants | VERIFIED |
| W08 | row delete, bulk delete | deleteWord, bulkDelete | words.delete_word_api, words.bulk_delete → DELETE words | Words / trash, Delete Selected | WordsScreen#build → WordFacade#bulkDeleteWords | CorpusDatabase#deleteWord | Rows removed after confirmation | FacadeParityTest | VERIFIED |
| W09 | #bulkUpdateBtn | bulkUpdate | (none) → (none) | Words / (none) | (none) → (none) | (none) | Nothing happens in the reference | UiParityTest | REFERENCE-INERT |
| W10 | Add Word modal | openAddWordModal, submitWord | words.api_words_create → INSERT words | Words / Add Word | WordsScreen#build → WordFacade#createWord | CorpusDatabase#insertWord | New row; multi-word refused | RelationshipModelTest#storageInvariants | VERIFIED |

### Word detail — `Word/word_detail.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| WD1 | id, usage_count, text | (server-rendered) | words.word_detail → words_paths COUNT | Word Detail / heading, Related Files stat | TermDetailScreen#onShow → RelationshipFacade#categoryWord | CorpusDatabase#countFilesForWord | Word, normalised form, file count | RelationshipModelTest#categoryWordBidirectional | VERIFIED |
| WD2 | Edit / Delete | editWord, deleteWord | words.update_word_api, words.delete_word_api → UPDATE/DELETE words | Words / pencil / trash | WordsScreen#build → WordFacade#updateWord | CorpusDatabase#updateWord | Handled on the list row | FacadeParityTest | ADAPTED |
| WD3 | (no files or categories in reference) | (none) | (none) → words_paths, words_categorys | Word Detail / Related Files table; Categories chips | TermDetailScreen#build → RelationshipFacade#categoryWord | CorpusDatabase#selectCategoriesForWord | Files with hits; categories (including the one this word names) with counts | RelationshipModelTest#integrityConsistent | VERIFIED |

### File detail & content — `File/file_detail.html`, `File/full_content.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| F01 | Info: Type, Size, Created, Status, Source, Side | (server-rendered) | files.file_detail → paths JOIN hashs JOIN sources JOIN sides | File Detail / File Record grid | FileDetailScreen#onShow → ContentFacade#getPath | CorpusDatabase#selectPathById | Path, name, size, SHA-256, MD5, MIME, type, created, modified, registered, processing status, OCR, review status, source, aspect | UiParityTest#contentValidation | VERIFIED |
| F02 | #contentText, #pageJump, goToPage, changePageSize | goToPage, jumpToPage | files.file_detail → contents 50000-char pages | File Detail / Full Content / Extracted Text preview; Full Content | FileDetailScreen#onShow → ContentFacade#getContentAsText | CorpusDatabase#selectContentsByPath | First 4000 chars on detail; whole text on Full Content | DestinationCoverageTest | VERIFIED |
| F03 | #searchInput, #caseSensitive, #wholeWord, findNext/findPrevious | performSearch, highlightMatches, scrollToMatch | (none) → (none) | Full Content / find field, Find Next | FullContentScreen#build → ContentFacade#getContentAsText | in-memory | Caret moves to the next match | UiParityTest | VERIFIED |
| F04 | copyContent, downloadContent, print, toggleFullscreen, shareFile | same | (none) → (none) | Full Content / Copy All | FullContentScreen#build → (clipboard) | (none) | Text on the clipboard | UiParityTest | ADAPTED |
| F05 | Metadata tab #metaName #metaNotes Save | saveMetadata | files.api_file_details → UPDATE paths | File Detail / Mark Read / Mark Unread | FileDetailScreen#toggleRead → ContentFacade#setPathStatus | CorpusDatabase#updatePathStatus | Badge flips and persists | UiParityTest#contentValidation | ADAPTED |
| F06 | Analysis tab #classificationChart | createClassificationChart | files.chart_data → words_paths -> words_categorys -> categorys share | File Detail / Categories chips | FileDetailScreen#onShow → RelationshipFacade#forFile | CorpusDatabase#selectCategoriesForPath | Each category the file reaches, with its whole-case count; click opens Category Detail | RelationshipModelTest#fileDetail | ADAPTED |
| F07 | Analysis tab Top Keywords #frequencyChart | createFrequencyChart | files.chart_data → get_word_frequencies(15) — words, not keywords | File Detail / Category Words chips | FileDetailScreen#onShow → RelationshipFacade#forFile | CorpusDatabase#selectWordsForPath | Vocabulary words present in the file, each with its count | RelationshipModelTest#fileDetail | ADAPTED |
| F08 | (keywords_paths not shown in reference) | (none) | (none) → keywords_paths | File Detail / Keywords chips phrase x hits (N files) | FileDetailScreen#onShow → RelationshipFacade#forFile | CorpusDatabase#selectPathKeywords | Every keyword phrase found in the file; click opens Keyword Detail | RelationshipModelTest#fileDetail | VERIFIED |
| F09 | exportFile (/file/id/export?format=pdf) | exportFile | files.export → (render) | Import / Export / Export File Registry | ImportExportScreen#build → ExportFacade#exportSearchResultsCsv | (none) | Registry exported as CSV | FacadeParityTest | ADAPTED |
| F10 | Reprocess (/file/id/reprocess) | confirm | files.reprocess → re-run pipeline | Error Dashboard / Retry | ErrorDashboardScreen#build → FileProcessingFacade#retryFile | IngestPipeline#retry | Element re-processed; hashes and text recomputed | DestinationCoverageTest#retryElement | VERIFIED |
| F11 | Delete (/file/id/delete) | confirm | files.delete → DELETE paths | (none) / (none) | (none) → (none) | (none) | Not offered | ArchitectureInvariantsTest#caseDatabaseIsTheOnlyDatastore | UNSUPPORTED |
| F12 | (none) | (none) | (none) → (none) | File Detail / Classify | FileDetailScreen#classify → CorpusDatabase#linkPathToCategory | path_category INSERT OR IGNORE | Category chip appears; count on Categories rises | DestinationCoverageTest | VERIFIED |
| F13 | (none) | (none) | (none) → (none) | File Detail / Open Source / Open Aspect | FileDetailScreen#build → FasApp#openSource | (none) | Source or Aspect Detail opens | ArchitectureInvariantsTest#everyControlEndsInAnOperation | VERIFIED |

### Search — `Search/search_enhanced.html`, `advanced_search.html`, saved searches

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| S01 | #searchForm #searchQuery | form GET | search.search_page → full_text_search: file_name ILIKE OR words_paths JOIN words | Search / query field, Search | SearchScreen#runSearch → SearchFacade#search | LiveCase#searchNow | Ranked results with snippets; total count | EndToEndScenarioTest | VERIFIED |
| S02 | #useFuzzy #useExpansion #useBM25 #useAdvanced | getAdvancedSearchOptions, performEnhancedSearch | search.api_search → search_service options | Search / type / source / aspect / date / sort filters | SearchScreen#runSearch → SearchFacade#search | LiveCase#searchNow | Results narrowed | EndToEndScenarioTest | ADAPTED |
| S03 | result link (href /file/id) | displaySearchResults | files.file_detail → (none) | Search / double-click result row | SearchScreen#build → FasApp#openFile | (none) | File Detail opens | UiParityTest | VERIFIED |
| S04 | highlightSearchTerms | (client-side) | search_service → (none) | Search / Preview pane | SearchScreen#preview → SearchFacade#search | Lucene highlighter | Matching fragments shown | EndToEndScenarioTest | VERIFIED |
| S05 | goToPage | performEnhancedSearch(page) | search.api_search → LIMIT OFFSET | Search / Previous / Next, perPage | SearchScreen#runSearch → SearchFacade#search | LiveCase#searchNow | Page changes | EndToEndScenarioTest | VERIFIED |
| S06 | (match location not reported in reference) | (none) | (none) → relevance CASE name 2.0 / word 1.0 | Search / Search Everywhere table, Matched In column | SearchScreen#runRelationshipSearch → RelationshipFacade#search | CorpusDatabase#selectFilesByNameOrPath, selectFilesByMetadata, selectFilesByContent, selectFilesForKeyword, selectFilesForCategory, selectFilesForWord | One row per file per match type: file name, path, metadata, content, keyword, category, category word | RelationshipModelTest#searchByLocation | VERIFIED |
| S07 | (none) | (none) | (none) → (none) | Search / Scope ComboBox | SearchScreen#runRelationshipSearch → RelationshipFacade#search | (as S06) | Only the chosen scope is searched | RelationshipModelTest#searchEdges | VERIFIED |
| S08 | (none) | (none) | (none) → (none) | Search / Check relationships | SearchScreen#checkRelationships → RelationshipIntegrity#check | CorpusDatabase#selectOrphanEdges | Summary dialog; full report in the preview pane | RelationshipModelTest#integrityDetectsDamage | VERIFIED |
| S09 | Save Search | (saved_searches) | search.saved → INSERT saved_searches | Search / Save Search | SearchScreen#saveSearch → SearchHistoryFacade#saveSearch | CorpusDatabase#insertSavedSearch | Named search appears on Saved Searches | FacadeParityTest | VERIFIED |
| S10 | Export | (export) | search.export → CSV render | Search / Export CSV | SearchScreen#exportCsv → ExportFacade#exportSearchResultsCsv | (none) | CSV file written where chosen | FacadeParityTest | VERIFIED |
| S11 | filters, suggestions, history, feelingLucky, export CSV/JSON/Excel | executeAdvancedSearch, displaySuggestions, exportResults | search.api_search → search_service | Advanced Search / Search, Clear, Open in Search | AdvancedSearchScreen#build → SearchFacade#search | LiveCase#searchNow | Filtered results; history recorded; hand-off to Search | UiParityTest | VERIFIED |
| S12 | history list | init | search.history → search_history | Saved Searches / history table, Clear History | SavedSearchesScreen#build → SearchHistoryFacade#getHistory | CorpusDatabase#selectHistory | Recent queries listed; clear empties them | FacadeParityTest | VERIFIED |
| S13 | runSearch, editSearch, deleteSearch | same | search.run_saved, search.delete_saved → saved_searches | Saved Searches / run / rename / delete row actions | SavedSearchesScreen#build → SearchHistoryFacade#updateSavedSearch | CorpusDatabase#updateSavedSearch | Search opens with the saved query; rename and delete persist | FacadeParityTest | VERIFIED |
| S14 | #alertModal toggleAlert saveAlert | toggleAlert, saveAlert | search.alert_configure → (stores flags; no scheduler) | (none) / (none) | (none) → (none) | (none) | Not offered | UiParityTest | REFERENCE-INERT |

### Archives — `Archive/*.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| AR1 | section tile data-section | navigateToSection | archives.* → per-section counts | Archives / section tree | ArchivesScreen#build → AnalyticsFacade#archiveTree | CorpusDatabase#selectAllPathsForTree | Tree of sources / aspects / categories / keywords with counts | DestinationCoverageTest | VERIFIED |
| AR2 | item card data-item-id | navigateToItem | archives.item_files → paths JOIN words_paths JOIN words_categorys / JOIN keywords_paths | Keyword / Category / Word Detail / Related Files table | TermDetailScreen#onShow → RelationshipFacade#filesForCategory | CorpusDatabase#selectFilesForCategory | Files reached through the term | RelationshipModelTest#categoryBidirectional | VERIFIED |
| AR3 | file card details / open / export / checkbox | file-view handlers | files.file_detail, archives.export → (render) | File Library / view / content / toggle buttons, Export via engine | FileLibraryScreen#build → ContentFacade#getPaths | CorpusDatabase#selectPaths | Paged registry with actions; double-click opens File Detail | UiParityTest | VERIFIED |
| AR4 | limit, search, category_id, source_id, side_id, date_from, date_to, sort_by, sort_dir, cursor | section-view | archives.files → filtered SELECT | Search / filters | SearchScreen#runSearch → SearchFacade#search | LiveCase#searchNow | Filtered results | EndToEndScenarioTest | ADAPTED |

### Sources — `Sources/*.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| SR1 | Add / Edit modal #sourceForm, importance slider, city, country, category | openSourceModal, submitSourceForm | sources.* → INSERT/UPDATE sources | Sources / Add Source, Edit | SourceForm#showEdit → SourceFacade#createSource | CorpusDatabase#insertSource | New or updated row; duplicate name refused | DestinationCoverageTest#editSource | VERIFIED |
| SR2 | duplicateSource | duplicateSource | sources.duplicate → INSERT copy | Sources / duplicate button | SourcesScreen#build → SourceFacade#duplicateSource | CorpusDatabase#insertSource | Copy appears with a suffixed name | FacadeParityTest | VERIFIED |
| SR3 | toggleSourceStatus | toggleSourceStatus | sources.toggle_status → UPDATE sources.access_status | Sources / (none) | (none) → (none) | (none) | Not offered | UiParityTest | UNSUPPORTED |
| SR4 | deleteSource, bulkExport, viewSource, viewSourceCategoriesKeywords | same | sources.* → sources, path JOIN path_category | Sources / Source Detail / Source Relationships / delete, row double-click, Categories & Keywords | SourceDetailScreen#build → AnalyticsFacade#categoriesForSource | CorpusDatabase#selectCategoriesForSource | Detail with real statistics; relationships scoped to the source | DestinationCoverageTest#sourceStatistics | VERIFIED |
| SR5 | category and keyword tables, row links | viewCategory, viewKeyword | sources.*, sides.* → path JOIN path_category / path_keyword | Source Relationships / Aspect Relationships / category row click, keyword row click | RelationshipsScreen#build → AnalyticsFacade#categoriesForSource, AnalyticsFacade#keywordsForSource, AnalyticsFacade#categoriesForAspect, AnalyticsFacade#keywordsForAspect | CorpusDatabase#selectCategoriesForSource | Categories and keywords found in files of this source/aspect with file counts; click opens the term detail | DestinationCoverageTest#sourceStatistics | VERIFIED |

### Sides / Aspects — `Side/*.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| SD1 | same as Sources | same as Sources | sides.* → sides | Aspects / Aspect Detail / Aspect Relationships / Add Aspect, Edit, Delete, duplicate, Categories & Keywords | AspectDetailScreen#build → AnalyticsFacade#aspectStatistics | CorpusDatabase#selectAspectStatistics | Same shape as Sources, scoped to the aspect | DestinationCoverageTest#aspectStatistics | VERIFIED |

### File library — `File/files_list.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| FL1 | #smartSearch, filters, sort, perPageFiles, quickPreview, bulkAnalyze, bulkExport, bulkDelete | same | files.files_list → paths filtered | File Library / Refresh, view / content / toggle, Previous, Next | FileLibraryScreen#build → ContentFacade#getPaths | CorpusDatabase#selectPaths | Paged registry; preview; read toggle | UiParityTest | ADAPTED |

### Upload — `Upload/*.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| UP1 | #fileInput #folderInput #sourceSelect #sideSelect #processBtn, log | handleFileSelection, handleFolderSelection, startProcessing, addLog | upload.process_path → pipeline | Upload / Choose Files, Choose Folder, Start Processing, Pause, Cancel | UploadScreen#build → FileProcessingFacade#processFolder | IngestPipeline#ingest | Live log and progress; items registered under the chosen source and aspect | DragDropIngestTest | VERIFIED |

### Batch analysis — `Batch/*.html`

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| BA1 | templates, priority, error handling, filters, Start, Stop, Retry | applyTemplate, startAnalysis, stopAnalysis, retryFile | analysis.batch_process → keyword scan / classify over contents | Batch Analysis / Start Analysis, Stop, Re-run, Delete Run | BatchAnalysisScreen#build → BatchAnalysisFacade#run | CorpusDatabase#linkPathToKeyword | Progress per file; results persisted as runs | BatchAnalysisTest#hitsComeFromRealAnalysis | VERIFIED |
| BA2 | #scheduleSelect, off-hours, #resourceLimit | (none) | (none) → (none) | Batch Analysis / (none) | (none) → (none) | (none) | Not offered | BatchAnalysisTest#distinctFromProcessing | REFERENCE-INERT |

### Dashboards, charts, analysis, path analysis, email words, performance

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| DB1 | summary tiles, file types, categories, timeline, word frequency, storage | loadAllStatistics et al. | analytics.* → aggregate SELECTs | Dashboard / stat cards, charts | DashboardScreen#onShow → DashboardFacade#getStats | CorpusDatabase#countPathsAll | Counts computed from the case at each show | DestinationCoverageTest#reviewProgress | VERIFIED |
| DB2 | filters, doughnut/bar charts, section tables | applyFilters, renderDoughnutChart | analytics.* → aggregate SELECTs | Charts / Refresh; chart segment click | ChartsDashboardScreen#build → DashboardFacade#getFileTypeBreakdown | CorpusDatabase#countTypesForSource | Canvas charts; clicking a segment opens Search or a Source | DestinationCoverageTest#reviewProgress | ADAPTED |
| DB3 | source/aspect/category/type filters, tabs | applyFilters, initializeTabNavigation | analytics.dashboard_summary → filtered counts | Comprehensive Dashboard / Apply Filters, Clear | ComprehensiveDashboardScreen#build → AnalyticsFacade#filteredCounts | CorpusDatabase#countPaths | Every tile narrows consistently | DestinationCoverageTest#combinedFilters | VERIFIED |
| DB4 | tree, expand/collapse, filters, classification modal | createPathNode, expandAll, handleFilterChange | analytics.path_* → paths grouped by folder | Path Analysis / Expand All, Collapse All, Refresh, node double-click | PathAnalysisScreen#build → AnalyticsFacade#directoryTree | CorpusDatabase#selectAllPathsForTree | Folder tree with rolled-up totals; a file node opens File Detail | DestinationCoverageTest#directoryTree | VERIFIED |
| DB5 | method selector, analyse file / folder / database | analyzeFile, analyzeFolder, analyzeDatabase | analytics.path_classifications → words_paths JOIN words_categorys | Analysis / source / aspect / category / type distributions, similar files | AnalysisScreen#build → DashboardFacade#getCategoriesFiltered | CorpusDatabase#selectCategoriesForPath | Distributions over real relations | DestinationCoverageTest | ADAPTED |
| DB6 | search, per page, copy, add to contacts, show files, export | searchInFiles, showEmailFiles, addToContacts | email_words.* → LIKE on words | Email Words / Refresh; address row | EmailWordsScreen#build → SearchFacade#search | LiveCase#searchNow | Addresses found in indexed text with the files carrying them | UiParityTest | LIMITED |
| DB7 | metrics, pools, threads, processes, async tasks | updateMetrics et al. | concurrency.* → runtime introspection | Processing Monitor / Pause, Cancel, Refresh | ProcessingMonitorScreen#build → LiveCase#ingestRunning | LiveCase#statusCounts | Live worker and queue state during a real ingest | PipelineAcceptanceTest | VERIFIED |
| DB8 | CPU / memory / disk meters, timed query | (static markup) | (none) → (none) | Performance / Run Timed Query, Refresh | PerformanceScreen#build → HostMetrics#read | LiveCase#indexedCount | Real OS counters or an explicit 'unavailable'; measured query time | HostMetricsTest#cpuIsMeasuredOrDeclaredMissing | VERIFIED |
| DB9 | error table, retry | retry | analysis.retry → re-run | Error Dashboard / Refresh, Retry | ErrorDashboardScreen#build → AnalyticsFacade#errorReport | CorpusDatabase#selectPaths | Failures by type with causes; retry re-processes | DestinationCoverageTest#errorReport | VERIFIED |

### Notifications

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| NT1 | tabs all/duplicates/future, search, side filter, scan, mark read, dismiss, detail modal | initializeNotificationsPage | notifications.* → alerts table | Notifications / Refresh, mark read, dismiss, row detail | NotificationsScreen#build → NotificationFacade#getNotifications | CorpusDatabase#insertAlert | Rows by tab; read/dismiss persist | FacadeParityTest | VERIFIED |

### Import / export

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| IE1 | export settings, export backup (include data), import settings, import backup, batch import paths / CSV | exportSettings, exportDatabaseBackup, importSettings, importDatabaseBackup, handleBatchImport | import_export.* → settings JSON, table dump, INSERT paths | Import / Export / Export File Registry, Export Keywords, Export Settings, Export Database Backup, Validate Backup, Import Settings, Import File List (CSV) | ImportExportScreen#build → ImportFacade#importDatabaseBackup | ExportFacade#exportDatabaseBackup | Files written / read; results summarised | SettingsPersistenceTest | VERIFIED |

### Settings

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| ST1 | branding, theme colours, toggles (autoProcess, autoAnalyze, animations, breadcrumbs, logging), language | (form) | settings.* → settings JSON | Settings / OCR, deduplication scope, passwords and other engine options | SettingsScreen#build → CaseSettings#saveTo | CaseFolder#database | Choices persist across restart | SettingsPersistenceTest#optionsSurviveRestart | ADAPTED |
| ST2 | /set_language/<lang> | (link) | settings.set_language → session flag | (none) / (none) | (none) → (none) | (none) | Not offered yet | SettingsPersistenceTest#optionsSurviveRestart | LIMITED |

### Setup / maintenance

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| SU1 | schema status, verify, rebuild | (server-rendered) | setup.* → schema introspection | Setup / Verify Integrity, Rebuild Search Index, Refresh | SetupScreen#build → LiveCase#rebuildIndex | LiveCase#verifyIntegrity | Integrity report; index rebuilt from case.db with the same searchable state | ResilienceTest#damagedIndexIsRebuilt | VERIFIED |

### AI (optional, local, manual, read-only)

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| AI1 | (none) | (none) | (none) → (none) | Assistant / Ask, Cancel | AgentScreen#build → AgentService#ask | CaseTools | Answer with tool activity and evidence identifiers; nothing loaded until asked | AiAgentTest#readOnlyByDefault | VERIFIED |
| AI2 | (none) | (none) | (none) → (none) | Sources, Aspects, Keywords, Categories, File Detail, Search / Analyze ... | AnalyzeAction#button → AgentService#ask | CaseTools | Screen context reaches the tools without pasting ids | AiAgentTest | VERIFIED |
| AI9 | (none) | (none) | (none) → (none) | Assistant / answer pane, trace pane | AgentScreen#build → AgentOrchestrator#systemPrompt | (none) | Every statement of an answer prefixed [OBSERVED]/[DERIVED]/[INFERRED]/[USER-PROVIDED]/[UNKNOWN]; trace shows counts per label | AiAgentTest#provenanceLabels | VERIFIED |

### Shell: navigation, errors, partials, toasts

| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |
|---|---|---|---|---|---|---|---|---|---|
| G01 | sidebar nav links, breadcrumb, back | (links) | (each) → (none) | FasApp / navigation rail, Back | FasApp#navigate → FasApp#register | (none) | Every destination reachable; back returns along history | ArchitectureInvariantsTest#everyScreenIsReachableFromTheNavigation | VERIFIED |
| G02 | error pages | (none) | (error handlers) → (none) | (inline) / empty states and dialogs | Fas#emptyState → FacadeException#internal | (none) | Failures surface where they happen | ResilienceTest | UNSUPPORTED |
| G03 | partials | (none) | (none) → (none) | Fas / card, pageHeader, Previous/Next | Fas#pageHeader → (none) | (none) | Reusable pieces, not destinations | UiParityTest#stylesheetPresent | UNSUPPORTED |
| G04 | toast notifications | showToast | (none) → (none) | (any) / Alert / inline label | Fas#saveBytes → (none) | (none) | Immediate feedback on every action | ArchitectureInvariantsTest#everyControlEndsInAnOperation | ADAPTED |

## Relationship model behind the rows

```
FILE (path, content, hash, metadata)
  │  path_keyword(path_id, keyword_id, hits)      ← reference keywords_paths
  ├──── KEYWORD (phrase, ≥ 3 words) ──── category_id ──── CATEGORY (one word)
  │  path_word(path_id, word_id, hits)            ← reference words_paths
  ├──── CATEGORY WORD (one word) ──── word_category ────── CATEGORY
  │  path_category(path_id, category_id)          ← reviewer attribution
  └──── CATEGORY
```

* Edges are derived by `RelationshipAnalyzer` from stored content when material is registered and on demand ("Update associations"); re-running is idempotent (`RelationshipModelTest`).
* Every count is `COUNT(DISTINCT path_id)` over the whole case, never over a page.
* Invariants (keyword ≥ 3 words; category and category word exactly 1 word) are enforced in `Terms`, in the facades, in `CorpusDatabase` inserts/updates and in the UI, and tested at each layer (`RelationshipModelTest#storageInvariants`).
* `RelationshipIntegrity` walks File → Keyword → Category → Category Word → Files in both directions and reports count disagreements and orphan edges ("Check relationships" on Search; `docs/RELATIONSHIP_INTEGRITY_REPORT.md`).
