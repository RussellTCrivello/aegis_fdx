# Supported Format Matrix

Version 1.0.0

**Nothing is ever ignored.** Every file is hashed, stored, indexed and given a
status. A format with no analyzer is marked `Unsupported` — retained and searchable
by name and metadata, just not by content.

Legend: ✅ full · ⚠️ partial · ❌ not in v1

---

## Documents

| Format | Ext | Text | Metadata | Notes |
|---|---|:--:|:--:|---|
| PDF | `.pdf` | ✅ | ✅ | Encrypted PDFs attempt the password list, else `Locked`. Scanned PDFs go to OCR. |
| Word | `.docx` | ✅ | ✅ | |
| Word 97-2003 | `.doc` | ✅ | ⚠️ | Via POI scratchpad. |
| Excel | `.xlsx` | ✅ | ✅ | Cell values incl. formula results. |
| Excel 97-2003 | `.xls` | ✅ | ⚠️ | |
| PowerPoint | `.pptx` | ✅ | ✅ | Slide text and notes. |
| PowerPoint 97-2003 | `.ppt` | ✅ | ⚠️ | |
| Rich Text | `.rtf` | ✅ | ⚠️ | |
| OpenDocument | `.odt` `.ods` `.odp` | ⚠️ | ⚠️ | Text via the ZIP/XML path. |

## Text and structured data

| Format | Ext | Text | Notes |
|---|---|:--:|---|
| Plain text | `.txt` `.log` `.md` | ✅ | Encoding auto-detected; UTF-8/16, Latin-1. |
| CSV / TSV | `.csv` `.tsv` | ✅ | |
| HTML / XHTML | `.html` `.htm` | ✅ | Tags stripped. |
| XML | `.xml` | ✅ | |
| JSON | `.json` | ✅ | |
| Source code | `.java` `.py` `.js` … | ✅ | Treated as text. |

Unicode is preserved end to end — Arabic, CJK and Cyrillic are covered by the test
suite.

## Email

| Format | Ext | Text | Attachments | Notes |
|---|---|:--:|:--:|---|
| Outlook PST | `.pst` | ✅ | ✅ | Folder structure preserved. |
| Outlook OST | `.ost` | ✅ | ✅ | |
| Outlook message | `.msg` | ✅ | ✅ | Incl. embedded messages. |
| MIME message | `.eml` | ✅ | ✅ | |
| Mailbox | `.mbox` | ✅ | ✅ | Split into messages. |

Headers (`From`, `To`, `Cc`, `Subject`, `Date`, `Message-ID`) become searchable
fields. **Attachments become independent linked elements** with their own hashes and
tags, linked to the parent message.

## Archives and containers

| Format | Ext | Extract | Notes |
|---|---|:--:|---|
| ZIP | `.zip` | ✅ | Encrypted members → `Locked` after the password list. |
| 7-Zip | `.7z` | ✅ | |
| TAR | `.tar` | ✅ | |
| GZIP | `.gz` `.tgz` | ✅ | |
| BZIP2 | `.bz2` | ✅ | |
| XZ | `.xz` | ✅ | |
| **RAR** | `.rar` | ❌ | **See below.** |

Nesting is expanded to **depth 20** (configurable) with parent links preserved at
every level. Archive bombs are bounded by the depth limit and per-element error
containment.

### RAR — deliberately excluded from v1

RAR archives are **retained, hashed, indexed and marked `Unsupported`**, with a
metadata note explaining why and what to do.

The only mature Java RAR library (`junrar`) inherits the UnRar licence restriction,
which is not OSI-approved and falls outside the mandated Apache/MIT/BSD/EPL set.
Rather than ship a non-compliant dependency, RAR extraction is omitted.

**Workaround:** extract externally and ingest the contents.
**Future:** a compliant provider can be added through the analyzer SPI without any
change to the core engine.

## Images

| Format | Ext | Metadata | OCR | Notes |
|---|---|:--:|:--:|---|
| JPEG | `.jpg` `.jpeg` | ✅ | ✅ | EXIF incl. GPS. |
| PNG | `.png` | ✅ | ✅ | |
| TIFF | `.tif` `.tiff` | ✅ | ✅ | |
| BMP | `.bmp` | ⚠️ | ✅ | |
| GIF | `.gif` | ⚠️ | ✅ | |

OCR must be enabled and Tesseract installed. Geolocation is extracted where present.

## Media and other

| Format | Ext | Support | Notes |
|---|---|:--:|---|
| MP3 | `.mp3` | ⚠️ | ID3 metadata only; no transcription. |
| MP4 / MOV | `.mp4` `.mov` | ⚠️ | Container metadata only. |
| WAV | `.wav` | ⚠️ | Metadata only. |
| SQLite DB | `.db` `.sqlite` | ⚠️ | Detected; table parsing is roadmap. |
| Exchange EDB | `.edb` | ❌ | Roadmap. |
| CAD | `.dwg` | ❌ | Metadata only. |
| Any other | — | ⚠️ | Hashed, stored, indexed by name/metadata, marked `Unsupported`. |

---

## Encrypted content

| Situation | Behaviour |
|---|---|
| Encrypted ZIP member | Password list attempted, then `Locked`; the run continues |
| Password-protected PDF | Password list attempted, then `Locked` |
| Encrypted Office document | `Locked` |
| Encrypted RAR | `Unsupported` (not extracted in v1) |

Locked elements are always retained and hashed. `Locked` is terminal — no later
stage can overwrite it, so encrypted material can never appear as successfully
processed.

## Corrupt content

Truncated, malformed and zero-byte files are marked `Error` with the reason
recorded, and appear in the processing report's exceptions list. **One bad file
never aborts a run** (N-05).

---

## Extending

Formats are added by implementing the single-method `Analyzer` SPI and dropping the
jar on the classpath — no core changes. See `docs/BUILD.md`.
