# Relationship Integrity Report

Evidence that the relationship graph File ↔ Keyword ↔ Category ↔ Category Word can be
traversed in either direction with agreeing counts, and that the checker that proves it
catches damage rather than hiding it.

Checker: `com.aegis.fdx.facade.RelationshipIntegrity` (`AegisFacades#relationshipIntegrity`).
User entry point: Search → **Check relationships** (renders `RelationshipIntegrity.render`).
Tests: `RelationshipModelTest#integrityConsistent`, `#integrityDetectsDamage`,
`#storageInvariants`, `#mergeDuplicates`.

## What is traversed

| Direction | Start | Path walked | Agreement asserted |
|---|---|---|---|
| Forward | every keyword | keyword → files (`path_keyword`) → each file's `FileRelationships.keywords()` | list count = detail count = files listed; every file lists the keyword back; keyword has exactly one category |
| Forward | every category | category → files (via `word_category ⨝ path_word`, `path_category`, keyword membership) → each file's `categories()`; category → words → word's `categories()`; category → keywords → keyword's `categories()` | list count = detail count; every reached file/word/keyword lists the category back |
| Forward | every category word | word → files (`path_word`) → each file's `categoryWords()` | list count = detail count; every file lists the word back |
| Reverse | every file | file → keywords → `filesForKeyword`; file → categories → `filesForCategory`; file → category words → `filesForCategoryWord` | each term's file set contains the file; no term id is dangling |
| Storage | every edge table | `path_keyword`, `path_word`, `path_category`, `word_category` | no edge references a missing path/term (`selectOrphanEdges`) |
| Invariants | every stored term | keyword ≥ 3 words; category = 1 word; category word = 1 word | `Terms.wordCount` on stored display text |

Finding rules: `count-agreement`, `bidirectional`, `dangling-edge`, `orphan-edge`,
`keyword-invariant`, `category-invariant`, `word-invariant`, `keyword-category`.

Counts compared are the same `COUNT(DISTINCT path_id)` queries every screen uses
(`CorpusDatabase#selectKeywordsWithFileCounts`, `#selectCategoriesWithFileCounts`,
`#selectCategoryWordsWithFileCounts`) against the per-term detail
(`RelationshipFacade#keyword/#category/#categoryWord`) and the per-file view
(`RelationshipFacade#forFile`), so a disagreement between any two screens is a finding.

## Executed output (2026-09-08, `RelationshipModelTest`, JUnit 5 via `JUnitRunner`)

Fixture: 4 files, 1 keyword ("payment due date", category *finance*), 2 categories
(*finance*, *legal*), 5 category words, derived by `RelationshipAnalyzer` from stored content.

`integrityConsistent` — the healthy case, then `analyzeAll` run three more times (idempotence):

```
Relationship integrity
  4 files, 1 keywords, 2 categories, 5 category words; 2 keyword edges, 5 word edges, 0 category edges; 28 traversals checked; consistent
```

Assertions: `consistent()`, files = 4, keywords = 1, categories = 2, categoryWords = 5,
keywordEdges = 2, traversalsChecked > 10, and edge counts unchanged after three re-analyses.

`integrityDetectsDamage` — a `path_keyword(4, 9999)` row is inserted directly into case.db,
bypassing the facades:

```
Relationship integrity
  4 files, 1 keywords, 2 categories, 5 category words; 3 keyword edges, 5 word edges, 0 category edges; 28 traversals checked; 1 finding(s)
  orphan-edge (1)
    path_keyword(4,9999): keyword or path missing
```

Assertions: `!consistent()` and a finding with rule `orphan-edge`.

Result: `=== 16 tests, 16 passed, 0 failed, 0 skipped ===` (full class run, see
`VERIFICATION_REPORT.md` for the whole battery).

## Defect found by the checker

While first running the checker against the fixture it reported
`bidirectional category#… ↔ word#…: category lists the word but the word does not list the category`.
Cause: `CorpusDatabase#selectCategoriesForWord` joined `word_category` only, whereas the
category → words direction also counted the category's own name-word. The two directions were
made symmetric (a word reaches a category through `word_category` **or** by being the
category's name; the same rule in both queries). This is exactly the class of asymmetry the
directive asked to be proven absent, and it is now covered by `integrityConsistent`.

## Invariants at every layer (`storageInvariants`)

| Layer | Keyword ≥ 3 words | Category = 1 word | Category word = 1 word |
|---|---|---|---|
| UI | `KeywordsScreen` add/edit dialog refuses and explains | `CategoriesScreen` | `CategoriesScreen` word entry |
| Facade | `KeywordFacade#createKeyword/#updateKeyword` → `Terms#requireKeyword` | `CategoryFacade#createCategory/#updateCategory` → `Terms#requireCategory` | `CategoryFacade#linkWordToCategory`, `WordFacade` → `Terms#requireSingleWord` |
| Database | `CorpusDatabase#insertKeyword/#updateKeyword` throw `SQLException` | `#insertCategory/#updateCategory` | `#insertWord/#updateWord` |
| Test | `assertThrows` at facade and DAO level for 1- and 2-word phrases | `assertThrows` for "two words" | `assertThrows` for "two words" |

## Duplicates (`mergeDuplicates`)

`KeywordFacade#findDuplicates/#mergeDuplicates` and `CategoryFacade#findDuplicates/#mergeDuplicates`
group by normalised text, keep the lowest id, re-point `path_keyword` / `word_category` /
`path_category` / keyword `category_id` at the survivor with `INSERT OR IGNORE` (so a file
related to both copies ends with one edge) and delete the rest. The integrity check is
consistent afterwards and whole-case counts are preserved (asserted in the test).

## Limits

* The checker loads term detail with the same `FILE_LIMIT` the UI uses; for a term with more
  files than the limit it compares counts but not the listed subset (stated in code).
* The checker is read-only; it reports and never repairs. Repair is the explicit
  "Update associations" (re-derive) or the merge actions.
