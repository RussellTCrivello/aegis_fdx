# AEGIS-FDX — User Manual

Version 1.0.0

> **Two interfaces.** This manual documents the review interface (`AegisApp`). The
> application also ships the **File Analysis System** interface (`FasApp`), the
> default, which adds Sources, Aspects, Categories, Keywords and Contents. See
> **[UI_GUIDE.md](UI_GUIDE.md)** for that interface. Both drive the same engine and
> the same case data.

---

## 1. What this application does

AEGIS-FDX processes digital evidence for forensic review and e-discovery. It reads
files of many types, extracts their text and metadata, indexes everything for fast
search, and lets you tag, annotate and export what matters.

Core principle: **evidence is never modified and never silently dropped.** A file
that cannot be read is still hashed, stored, indexed and reported as an exception.

---

## 2. Concepts

| Term | Meaning |
|---|---|
| **Case** | A self-contained folder holding everything about one matter. |
| **Element** | One reviewable object: a file, an email, an attachment, or an archive member. |
| **Custodian** | The person or system evidence came from. |
| **Container** | Anything holding other elements (ZIP, PST, MBOX). |
| **Status** | Where an element ended up: Indexed, Error, Locked, Unsupported. |
| **Source** | Who or what the material came from, with importance, country, job and access status. Mandatory when ingesting. |
| **Aspect** | The party or grouping the material belongs to, with importance. Mandatory when ingesting. |
| **Word** | A single term in the analysed corpus. |
| **Category** | A named grouping of words, identified by its own word. |
| **Keyword** | A phrase belonging to a category. |
| **Content** | The extracted text stored against a registered file path. |

Sources, Aspects, Categories, Keywords and Contents are managed in the File Analysis
System interface and are stored in the case's own database, `db/case.db`, alongside
the engine tables. They relate to evidence through the `path` registry, which links
each ingested element to its source, aspect, hash and extracted content by foreign
key.

One source file can produce many elements: a ZIP holding an email with two
attachments yields the ZIP, the email, and both attachments — each independently
searchable and taggable, with the parent link preserved.

---

## 3. Creating a case

**New case** → choose a name and location.

The case folder is created with this fixed layout:

```
<case>/
  data/     preserved copies of embedded elements
  index/    the Lucene search index
  text/     extracted text (allows rebuilding the index)
  db/       case.db — SQLite metadata, queue and audit log
  logs/     processing logs
  exports/  everything you export or report
  case.json case settings
```

The whole folder is portable — copy it to move the case.

---

## 4. Adding evidence

**Add evidence** → pick a folder or file, assign a custodian, start.

Source evidence is opened **read-only** and never written to.

You can **Pause**, **Resume** or **Cancel** at any time. Results appear in the list
as they are indexed — you can search a case while it is still ingesting.

**If the application stops unexpectedly, just start the same ingest again.**
Completed work is recognised and skipped; the case ends up identical to one
produced by an uninterrupted run.

### What happens to each file

| Stage | Action |
|---|---|
| Intake | Discovered, queued durably |
| Extract | Text + metadata pulled out; containers expanded (nesting to depth 20) |
| Hash | MD5 and SHA-256 computed |
| Dedupe | Identical SHA-256 marked — **never deleted** |
| Index | Made searchable |
| Store | Text, metadata and audit record written |

### Statuses

| Status | Meaning |
|---|---|
| **Indexed** | Processed successfully |
| **Error** | Corrupt or unreadable — retained with the reason recorded |
| **Locked** | Encrypted, no supplied password matched — retained |
| **Unsupported** | No analyzer for this format — retained and hashed |

Error/Locked/Unsupported are **final**: nothing later overwrites them, so an
encrypted file can never masquerade as successfully processed.

---

## 5. Searching

Type in the search box and press Enter.

| You want | Type |
|---|---|
| A word | `settlement` |
| An exact phrase | `"wire transfer"` |
| Starts with | `settle*` |
| One unknown character | `se?tle` |
| Similar spellings | `setlement~` or `setlement~2` |
| Words near each other | `"invoice payment"~5` |
| Both terms | `settlement AND payment` |
| Either term | `settlement OR briefing` |
| Exclude | `type:pdf NOT draft` |
| Grouping | `(settlement OR ledger) AND type:pdf` |
| Pattern matching | `/INV-\d{5}/` |

### Field searches

`from:` `to:` `cc:` `subject:` `type:` `ext:` `path:` `tag:` `status:`
`custodian:` `name:` `md5:` `sha256:` `hasattachments:`

Dates: `date:[2023-01-01 TO 2023-12-31]`

Examples:
```
from:j.kowalski@example.com AND subject:"wire instructions"
type:pdf AND date:[2024-01-01 TO 2024-06-30] NOT tag:Hidden
custodian:"A. Farouk" AND tag:Responsive
```

**Regular expressions** run against the full stored text, so they match across word
boundaries — `/INV-\d{5}/` finds `INV-88213` even though the indexer splits that
into two tokens. Regex is slower than other queries; narrow it with a filter where
possible.

An unbalanced query such as `((malformed` returns no results rather than an error.

### Filters, sorting, saved searches

The left panel filters by type, custodian, status, tag, attachments and date. Sort
by relevance, date, name or size. **Search within results** narrows the current set.
**Save** stores a query for reuse. **Export list (CSV)** writes the current results.

---

## 6. Reviewing

Select an element to see **Preview**, **Metadata**, **Tags & notes** and **Audit**.

### Tags

Five built-in tags with keyboard shortcuts:

| Tag | Shortcut | Purpose |
|---|---|---|
| Responsive | Ctrl+1 | Relevant to the matter |
| Featured | Ctrl+2 | Key document |
| Trending | Ctrl+3 | Emerging theme |
| Needs Review | Ctrl+4 | Requires a second look |
| **Hidden** | Ctrl+5 | **Excluded from all searches and exports** |

Elements can carry several tags. Tags apply to multi-selections.

> **Hidden is a hard exclusion.** Hidden elements do not appear in search results
> (unless you tick *Include hidden*) and are never exported. Use it for privileged
> or out-of-scope material.

### Notes

Free text per element. **Notes are indexed** — searching `privileged` finds
elements whose notes contain it.

### Duplicates and threads

Identical content is grouped by SHA-256 and marked in every location. Nothing is
deleted; you decide what to do. Emails are grouped into conversations.

---

## 7. Exporting

**Export** → choose scope, format, layout and destination.

| Scope | |
|---|---|
| Selected | Only highlighted elements |
| All results | Everything currently listed |
| By tag | Everything carrying one tag |

| Format | |
|---|---|
| Native | Byte-for-byte original files |
| EML | Emails as RFC-822 messages |
| PDF | Rendered documents |
| Text | Extracted text only |

| Layout | |
|---|---|
| Flat | One directory |
| By type | Grouped by extension |
| By custodian | Grouped by custodian |
| Hierarchy | Mirrors the original container structure |

Every export produces:

- `loadfile.csv` — UTF-8 with BOM (opens correctly in Excel), 31 fixed columns
  including MD5, SHA-256, tags, notes and parent/attachment/duplicate links.
- `MANIFEST-SHA256.txt` — hash of every written file.
- Extracted-text sidecars.

**Exports are verified, not assumed.** Every native copy is re-hashed after writing
and compared to the value recorded at ingest. A mismatch is reported as a failure —
if you see one, do not produce that set.

Hidden elements are always excluded, and every export is written to the audit log.

---

## 8. Reports

**Reports** → *Write reports to /exports*. Three reports, each as Markdown and CSV:

- **Processing** — volumes, status breakdown, OCR results, breakdowns by type and
  custodian, and an **exceptions list** naming every failed, locked and unsupported
  element with its reason.
- **Duplicates** — every duplicate group and the reviewable volume saved.
- **Search** — the query, when it ran, and exactly what it returned.

---

## 9. OCR

For scanned documents and images with no text layer.

Settings → **OCR enabled**, plus a language list (e.g. `eng`, `eng,deu`). The
settings panel shows the detected engine and which languages are actually installed.

- Runs **after** the main pass, in the background — never blocks ingestion.
- **Digital PDFs are skipped.** Only PDFs with essentially no text layer are OCR'd,
  because OCR would be slower and worse than the text already extracted.
- Recognised text is added to the element and becomes searchable.
- If no OCR engine is installed, processing continues and says so in the report.

---

## 10. Verifying evidence integrity

**Verify** re-hashes every original and compares it to the value recorded at ingest.

Reports: elements verified, embedded elements covered by their container, and
anything **modified**, **missing** or **unreadable**.

This is read-only. It never repairs anything — silently "fixing" evidence would
destroy the provenance it exists to demonstrate. Investigate any finding.

---

## 11. Settings

| Setting | Default | Notes |
|---|---|---|
| OCR enabled | On | Per case |
| OCR languages | `eng` | Must be installed |
| Max archive depth | 20 | Nesting limit |
| Index memory | 4096 MB | Higher = faster indexing |
| Worker threads | cores − 1 | |
| Dedupe scope | Off | Off / Per custodian / Global |
| Encrypt case folder | On | AES-256 |

---

## 12. Known limitations

Stated plainly so nothing surprises you mid-matter:

| Limitation | Detail |
|---|---|
| **RAR is not extracted** | Retained, hashed, indexed and marked Unsupported. The only mature Java RAR library has licence terms outside our approved set. Extract externally and re-ingest. |
| **OCR needs Tesseract** | Not bundled in all builds. Without it OCR is skipped and reported. |
| **OCR caps at 50 pages** | Per PDF, to stop one huge scan monopolising the queue. Noted in the element's metadata. |
| **PDF export of non-Latin text** | Arabic/CJK/Cyrillic glyphs render as `?` in exported PDFs (standard PDF fonts are Western-only). The text is fully preserved in the CSV and text sidecars. Use Native or Text format for these. |
| **Regex is slower** | It scans stored text. Combine with filters on large cases. |
| **Deep nesting stops at 20** | Configurable; the element is marked at the limit. |
| **Not yet included** | AI classification, GPU acceleration, disk carving, mobile backups, live monitoring and scripting plugins are roadmap items. |

---

## 13. Troubleshooting

**The application will not start.**
Needs a bundled Java 21 runtime — reinstall rather than relying on a system Java.
Run `packaging/verify-install.sh` to check the installation.

**Ingestion seems stuck.**
Check the log panel. Large PSTs and deep archives take time. Progress shows
elements per second; Pause and Cancel remain responsive.

**A file shows as Error.**
It is corrupt or truncated. The reason is in the Metadata tab and the processing
report. The element is still hashed and preserved.

**A file shows as Locked.**
It is encrypted and no supplied password matched. Add passwords in Settings and
re-ingest.

**A search returns nothing.**
Check *Include hidden*, clear filters, and confirm the element is Indexed rather
than Error/Locked/Unsupported. Try a broader term or a fuzzy search (`term~`).

**OCR is not finding text.**
Confirm Settings shows a detected engine and that your language is installed. Very
low-resolution or heavily skewed scans may not be recognisable.

**Export reported a hash mismatch.**
The source file changed after ingest. **Do not produce that set.** Run **Verify** to
identify what changed.

**Out of memory on a large case.**
Raise the heap in the launcher configuration (default 4 GB) and lower Index memory.

## Query validation — what the search bar refuses

Search **rejects** any query it cannot represent exactly, showing the reason instead of
running a different search. This is deliberate: a query that quietly changes meaning is
far more dangerous in review than one that visibly fails.

Previously a mistyped field such as `custodain:Smith` silently became a **full-text
search for "smith"**. The reviewer saw a plausible result set with no indication that
their custodian filter had been discarded, and could reasonably have reported it as that
custodian's complete documents. That fallback has been removed.

### Supported fields

`content` · `text` · `name` · `path` · `source` · `type` · `ext` · `container` ·
`from` · `to` · `cc` · `bcc` · `subject` · `messageid` ·
`tag` · `status` · `custodian` · `notes` · `meta` ·
`md5` · `sha256` · `hash` · `id` · `parent` · `duplicate` · `hasattachments` ·
`date` · `sent` · `created`

Anything else is rejected with a near-miss suggestion:

```
custodain:Smith   →  Unknown search field 'custodain'. Did you mean: custodian?
tags:Responsive   →  Unknown search field 'tags'. Did you mean: tag?
```

Two aliases are worth knowing: `hash:` is a synonym for `sha256:`, and `bcc:` searches
the CC field because BCC is not separately indexed — the query is honest about what it
searches rather than silently returning nothing.

### Rejected inputs

| Input | Reason |
|---|---|
| `date:[NOTADATE TO 2023-12-31]` | Invalid date. Previously became an **unbounded** range matching everything before the end date. |
| `date:[2023-12-31 TO 2023-01-01]` | Start after end — can never match. Previously returned 0 hits, indistinguishable from "no responsive documents". |
| `date:[2023-01-01]` | Missing `TO` clause |
| `term~99` | Fuzzy distance must be 0-2. Previously clamped to 2 silently. |
| `*` / `?` / `**` | Wildcard with no literal characters; would enumerate the whole index |
| `/unclosed[0-9/` | Malformed regex. Previously matched nothing, indistinguishable from a valid pattern with no hits. |
| `"unterminated` | Missing closing quote |
| `from:` | Field with no value |

Use `*` for an open-ended date bound: `date:[2023-01-01 TO *]`.
