# Reference relationship model — traced, not assumed

**Reference:** `RussellTCrivello/file_analysis@main`, read in full (205 Python files, 107
JavaScript files, 39 templates) rather than sampled. Every statement below was taken
from the code named beside it.

This document is the evidence behind the Java relationship model. It records what the
reference *actually does* — the SQL it runs, the counts it computes, the rules it applies
when a term is entered — so that the Java implementation can reproduce the behaviour
without copying the implementation. It is the first half of the interface-correlation
work; the element-by-element matrix (`docs/INTERFACE_FUNCTION_MATRIX.md`) is the second.

---

## 1. The reference schema (PostgreSQL, `database/createsTables.py`)

```
words(id, word)                                   one row per single word
categorys(id, word_id UNIQUE → words.id)          a category IS a word
words_categorys(word_id, category_id)             the words that belong to a category
keywords(id, keyword BYTEA UNIQUE, category_id)   a keyword phrase, owned by a category
keywords_paths(path_id, keyword_id, word_count)   which files carry which keyword
words_paths(path_id, word_id, word_count, positions) which files carry which word
paths(id, file_name, file_path, file_size, file_type, file_status, file_date, hash_id)
contents(id, content_data, content_date, path_id) extracted text, chunked
hashs(id, hash, side_id, source_id)               attribution of a file
```

Two things follow directly from the schema, and they are the vocabulary rules:

- **A category is one word.** `categorys` has no name column; it points at a single
  `words` row. There is no way to store a two-word category.
- **A keyword is a phrase.** `keywords.keyword` is a pickled *list of word ids*
  (`archives_api.py:api_archives_keywords` unpickles it and joins `words` to render the
  text), so a keyword is by construction a sequence of words.
- **Category words are their own relation.** `words_categorys` is a many-to-many between
  `words` and `categorys`; a category word is not a synonym for a category, it is a word
  attached to one.

## 2. How a typed term is classified (`database/operations.py::process_term`)

```python
words = term.strip().lower().split()
if len(words) == 1:   → insert word, link to category   (category word)
elif len(words) >= 2: → insert keyword phrase           (keyword)
```

**Deviation, deliberate and stated.** This project's rule is stricter: a keyword is a
phrase of **three or more** words, so a two-word phrase is neither a category word nor a
keyword and is refused with an explanation. See `Terms` in the Java sources — the rule is
enforced at the facade, not documented and forgotten.

## 3. The counts every row shows

| Row | Reference SQL | Java equivalent |
|---|---|---|
| Keyword → files | `SELECT COUNT(DISTINCT kp.path_id) FROM keywords_paths kp WHERE kp.keyword_id = ?` (`database/__init__.py::get_keyword_stats`, `Api/utils/utils.py::get_keywords_with_usage`) | `CorpusDatabase#countFilesForKeyword` |
| Category → files | `SELECT wc.category_id, COUNT(DISTINCT wp.path_id) FROM words_categorys wc LEFT JOIN words_paths wp ON wc.word_id = wp.word_id GROUP BY wc.category_id` (`archives_api.py:api_archives_categories`) | `CorpusDatabase#countFilesForCategory` — the same join over `word_category ⨝ path_word`, unioned with files attributed directly through `path_category` |
| Category → words | `SELECT category_id, COUNT(DISTINCT word_id) FROM words_categorys GROUP BY category_id` | `selectCategoriesWithFileCounts` (`words` column) |
| Category word → files | `SELECT COUNT(DISTINCT wp.path_id) FROM words_paths wp WHERE wp.word_id = ?` (`Api/utils/utils.py::get_word_detail`) | `CorpusDatabase#countFilesForWord` |

The count is over the whole case in both systems: it is a `COUNT(DISTINCT path_id)` on
the relation, never a count of the rows currently on screen.

## 4. Term → files (`Api/routes/archives.py::api_archives_files`)

```sql
-- section=category
SELECT DISTINCT p.* FROM paths p
  JOIN words_paths wp     ON wp.path_id = p.id
  JOIN words_categorys wc ON wc.word_id = wp.word_id
 WHERE wc.category_id = ?

-- section=keywords
SELECT DISTINCT p.* FROM paths p
  JOIN keywords_paths kp ON kp.path_id = p.id
 WHERE kp.keyword_id = ?
```

So in the reference a file belongs to a category **because it contains one of that
category's words**. The Java model keeps that route and adds the one the Java engine
already had — a reviewer or an analysis run attributing a category to a file
(`path_category`) — and takes the union, so neither route is invisible in a count.

## 5. Search (`Api/services/search_service.py`)

The reference searches file names and words:

```sql
p.file_name ILIKE %q%
OR EXISTS (SELECT 1 FROM words_paths wp JOIN words w ON w.id = wp.word_id
            WHERE wp.path_id = p.id AND w.word ILIKE %q%)
```

with an optional category filter (`words_paths ⨝ words_categorys`), relevance boosts for
a file-name hit, and phrase/term expansion. What it does **not** do is tell the operator
*where* the match came from — every row looks the same whether the file matched by name,
by content or through a category. The Java implementation reports the match type per row
(`MatchRow.MatchType`), which is an addition, recorded as such.

## 6. What the reference's detail pages actually show

| Destination | Reference content | Source |
|---|---|---|
| `/keywords` | text, usage count (= file count), category name, duplicate badge, row actions view/edit/delete | `templates/Keyword/keywords_list.html`, `Api/routes/keywords.py::keywords_list` |
| `/keywords/<id>` | id, text, usage count, distinct file types, last used. **No file list, no related terms.** | `Api/routes/keywords.py::keyword_detail` |
| `/categories/<id>/words` | the words of the category | `Api/routes/categories.py::category_words` |
| `/words/<id>` | word, usage count | `Api/utils/utils.py::get_word_detail` |
| `/file/<id>` | metadata, paginated content, content statistics, classification percentages, word frequencies. **No keyword or category list for the file.** | `Api/blueprints/files.py` |

The richer detail views this project asks for — a keyword that lists its files, its
category and its category words; a file that lists its keywords, categories and category
words, all clickable — are **reverse readings of relations the reference does establish**,
not invented ones. That distinction is why they are being built: the edges exist in the
data; the reference simply never renders them in that direction.

## 7. Java mapping decided from the above

```
FILE (path)
 ├── path_keyword(hits) ─────▶ KEYWORD (phrase, ≥3 words) ──▶ CATEGORY (1 word)
 ├── path_word(hits) ────────▶ CATEGORY WORD (1 word) ──word_category──▶ CATEGORY
 ├── path_category ──────────▶ CATEGORY
 └── content / text store ───▶ Lucene index (derived)
```

`path_word` is new in the Java schema and is the equivalent of the reference's
`words_paths`, restricted to vocabulary the case actually holds — the words that belong
to a category — rather than every token of every document. It is populated by analysis
over the stored text, so a count there always means the word genuinely occurs in that
file.

Normalisation, applied to comparison only and never to the stored display form:
lower case, accents folded, punctuation that separates words turned into spaces,
apostrophes and hyphens kept inside a word (`no-such-category` is one word,
`director's report` is two), runs of whitespace collapsed.
