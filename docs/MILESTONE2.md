# Milestone 2 — Real Engine

The JavaFX prototype from M1 is no longer a mock. `StubEngine` is gone; the UI is
wired to a live Lucene + SQLite backend that ingests real files from disk.

## What changed

| Area | M1 | M2 |
|---|---|---|
| Backend | `StubEngine`, hard-coded rows | `LiveCase` → `IngestPipeline` + `LuceneIndex` + `CaseDatabase` |
| Evidence | fictional | real files parsed by 10 analyzers |
| Search | string matching over a list | Lucene queries over a real index |
| Text | invented snippets | PDFBox / POI / mime4j / libpst extraction |
| Case | in memory | self-contained folder, SQLite WAL, survives restart |
| Screenshots | mock data | captured from an actual indexed case |

## Verification — one command

```bash
./run-tests.sh
```

```
33 passed, 0 failed                                    ← query parser (M1)
processed=55 errors=3 locked=1 unsupported=4 dupes=0   ← pipeline
=== 56 passed, 0 failed ===                            ← acceptance AT-01..AT-10
N-02 PASS · F-18 PASS · N-03 PASS                      ← benchmark
```

89 assertions, no failures.

## Two real bugs found and fixed

These were genuine defects surfaced by the acceptance battery, not test noise.

**1. Regex could never match (F-15).** `RegexpQuery` matches whole *analyzed
terms*, so `StandardAnalyzer` split `INV-88213` into `inv` + `88213` and
`/INV-\d{5}/` returned zero hits forever. Wrapping in `.*…*` does not fix it —
no single token spans the hyphen. Fixed with `StoredTextRegexQuery`, which
evaluates the pattern against **stored** text via `TwoPhaseIterator`
(`matchCost` 5000, optional approximation query to pre-filter). Now returns 2 hits.

**2. Terminal statuses were being overwritten (F-07/AT-03).** The pipeline set
`LOCKED` on the encrypted ZIP entry, then a later stage unconditionally wrote
`INDEXED` over it — an encrypted item silently appeared successfully processed,
which is a defensible-process failure. `IngestPipeline.terminal(...)` now makes
`LOCKED`/`ERROR`/`UNSUPPORTED` sticky, and the run continues past them (N-06).

A third defect was caught while reviewing the screenshots: a slow background
search could overwrite a newer one's results. Fixed with an `AtomicLong`
generation guard; progressive refresh during ingest no longer clobbers a query
the analyst has typed.

## Measured on the real index (55 elements)

| Query | Hits | | Query | Hits |
|---|---|---|---|---|
| `settlement` | 4 | | `/inv-\d{5}/` | 2 |
| `"wire transfer"` | 1 | | `settlement AND payment` | 1 |
| `settle*` | 4 | | `settlement OR briefing` | 5 |
| `setlement~2` | 4 | | `type:pdf NOT corrupt` | 3 |
| `type:pdf` | 3 | | `(settlement OR ledger) AND type:pdf` | 1 |
| `subject:"Wire instructions"` | 1 | | `date:[2020-01-01 TO 2030-12-31]` | 48 |
| `status:Error` | 3 | | `((malformed` | 0 (no crash) |

Arabic 2 · CJK 2 · Cyrillic 1 — Unicode round-trips intact.

## Benchmark

Ran on the dev sandbox (**2 cores, ~1 GB usable heap** — far below the reference
spec), so treat throughput as a lower bound.

| Metric | Result | Target | |
|---|---|---|---|
| Ingest throughput | ~650,000 items/h | ≥ 8,000 | PASS |
| Search p95 | 6.0 ms | < 2,000 ms | PASS |
| Search p99 | 12.2 ms | — | |
| UI event handling max | 6.2 ms | < 500 ms | PASS |
| Peak heap | 46 MB | 4 GB budget | PASS |

**Caveat, stated plainly:** the corpus is small documents, so per-item overhead
dominates and throughput is optimistic. AT-06 (100k items) and N-02 at scale
**cannot be certified on this hardware**. `Benchmark.java` runs unchanged on the
reference machine — `./run-tests.sh 2000` — to produce the certified numbers.

## Known gaps to milestone 3

- **OCR** — `needsOcr` is set by the PDF/image analyzers; the Tesseract stage is
  not wired (needs a native binary).
- **Export writers** — scope/layout/CSV load file are specified and the UI
  collects the options; the native/EML/PDF writers are not implemented.
- **junrar remains licence-blocked** — see `DEPENDENCY_REPORT.md`. Recommend
  dropping RAR for v1; single isolated call site.
- **Advanced features** (ONNX, GPU, carving, mobile backups, live monitoring,
  scripting SPI) remain in scope, not started.
- **Installers** — `jpackage` config written, not built (needs a Windows host).
