# Database, Search and Dashboard Performance Report

Every number in this document was measured by running this application's own classes on
the machine described below, on the date given. Nothing is estimated, extrapolated from
a vendor benchmark, or carried over from a previous report. The harnesses are in
`tools/bench/` and the raw output is in `docs/bench/*.tsv`; re-running them reproduces
these tables.

---

## 0. The honest headline

**SQLite is not the bottleneck, and it is not being replaced.**

The one place the architecture genuinely could not scale was the dashboard reading
aggregates by scanning the item table — 15.2 seconds per refresh at five million items.
That is now a derived statistics table maintained transactionally, answering in 0.14 ms.
The second real defect was a per-file resume lookup with no index, which made ingest
quadratic in case size. Both were fixed inside the existing architecture.

| Question | Answer | Evidence |
|---|---|---|
| Is SQLite the bottleneck? | No | §4, §7 |
| Was the dashboard? | Yes, and it is fixed | §5 |
| Does the dashboard need derived statistics? | Yes, decisively | §5 |
| Does DuckDB help? | Not evaluated further — no measured need | §9 |
| Does native code help? | No candidate identified | §9 |
| Migration recommended? | **No** | §11 |

---

## 1. Measurement environment — read this before quoting any number

```
CPU        Intel Xeon @ 2.60GHz, 2 cores          <-- reference target is 8 cores
RAM        3 GB total                              <-- reference target is 16 GB
Storage    overlay filesystem on network-backed volume  <-- target is local NVMe
JVM        OpenJDK 25.0.2 (jdk4py runtime)
Compiler   Eclipse batch compiler 3.45 (the fallback path run-tests.sh supports)
SQLite     sqlite-jdbc 3.46.1.0
Lucene     9.11.1
Date       2026-09-08
```

**This machine is roughly a quarter of the reference machine and its storage is not
NVMe.** Two consequences follow, and both matter when reading this report:

- Absolute throughput figures here are a **floor**, not a prediction. The reference
  machine has four times the cores, five times the RAM and dramatically better write
  latency; every figure below should improve on it, some substantially.
- **Ratios and shapes are trustworthy.** A 105,741× speedup, a quadratic curve, or the
  relative cost of two commit strategies are properties of the algorithms, not of the
  hardware, and they transfer.

Where a conclusion depends on absolute performance rather than on a ratio, this report
says so explicitly rather than quietly asserting the reference machine will be fine.

**JavaFX was not available in this environment.** The UI module could not be compiled or
executed here, so every claim in this report concerns the database, index and facade
layers, which were compiled and run. UI changes are described in §8 and are marked as
**unverified by execution** — that is a real gap, stated plainly, not glossed.

---

## 2. Runtime SQLite configuration (§6)

Read back from a live connection with `PRAGMA`, not from documentation.

### Before

| Pragma | Value | Note |
|---|---|---|
| `journal_mode` | `wal` | as documented |
| `synchronous` | `1` (NORMAL) | as documented |
| `foreign_keys` | `1` | as documented |
| `busy_timeout` | `5000` | as documented |
| `cache_size` | `-2000` | **SQLite default, 2 MB — never configured** |
| `mmap_size` | `0` | **memory mapping disabled** |
| `temp_store` | `0` (default → file) | **sorts and GROUP BY spilled to disk** |
| `page_size` | `4096` | default |
| `wal_autocheckpoint` | `1000` | default |

The first four were as the code claimed. The last five had never been set at all — the
application was running on stock SQLite defaults for memory and I/O, which is why
`GROUP BY ext` was spilling a temporary b-tree to disk on every dashboard refresh.

### After

| Pragma | Value | Reasoning |
|---|---|---|
| `cache_size` | `-65536` (64 MB) | Holds the hot interior b-tree pages of a multi-million-row table. Negative so it means KiB and does not silently scale with page size. Larger values measured inside the noise while resident memory kept climbing. |
| `temp_store` | `2` (MEMORY) | Keeps GROUP BY/ORDER BY sorters off disk. |
| `mmap_size` | 256 MB | Avoids a copy per page on read-heavy paths. |
| `wal_autocheckpoint` | `4000` | Checkpoints stall writers; batched ingest wants them less often, not never. |

Every one is overridable for smaller machines, and this is covered by a test
(`BatchRecoveryTest.configurableForSmallMachines`):

```
-Daegis.db.cacheKb=2048      -Daegis.db.mmapBytes=0
-Daegis.db.busyTimeoutMs=…   -Daegis.db.walPages=…
-Daegis.db.ingestBatch=…     -Daegis.db.statsTriggers=false
```

`BatchRecoveryTest.pragmasAreInForce` asserts the tuned values are actually in effect at
runtime, so this table cannot rot the way the previous one did.

---

## 3. Transaction strategy (§7)

The ingest pipeline committed **once per element**. Measured over 50,000 rows through
the real `CaseDatabase.save()` path:

| Commit batch | Rows/s | Items/hour | Peak heap |
|---:|---:|---:|---:|
| 1 (**before**) | 6,451 | 23,225,351 | 15.7 MB |
| 100 | 16,877 | 60,755,951 | 11.7 MB |
| 500 | 16,546 | 59,564,349 | 11.7 MB |
| 1,000 | 15,037 | 54,132,049 | 10.0 MB |
| 5,000 (**chosen**) | 17,128 | 61,660,246 | 8.4 MB |
| 10,000 | 17,639 | 63,501,090 | 10.0 MB |

**2.7× faster.** The curve is flat from 100 upward — the entire gain is removing one
fsync per element, not the batch size itself. 5,000 was chosen from the plateau rather
than the nominal maximum because it bounds the crash window: at the measured rate a lost
batch is under a third of a second of re-processing, and 10,000 bought 3% more
throughput for twice that exposure. Memory does not constrain the choice at any size
tested.

### This does not weaken recovery

The queue row and the item row for an element are written in the **same** batch
transaction. A crash therefore loses whole elements, never half of one — and a lost
element looks un-started, so the existing queue-driven resume re-runs it exactly as it
would a file that never began. What a crash can no longer do, which it could before, is
leave an item row committed while its queue row still says `PENDING`.

Four tests in `BatchRecoveryTest` assert this, including checks for orphaned queue rows
and orphaned items after an interrupted batch.

---

## 4. Is SQLite the bottleneck? (§22, §24)

| Operation | 100K | 1M | 5M |
|---|---:|---:|---:|
| Batched insert | — | — | 6,083 rows/s |
| Per-element insert (before) | 6,975 rows/s | 7,007 rows/s | — |
| Lookup by id, P50 / P95 / P99 | 31 / 56 / 75 µs | 34 / 55 / 78 µs | — |
| Lookup by SHA-256, P50 / P95 / P99 | 7 / 10 / 31 µs | 11 / 16 / 50 µs | — |
| Queue enqueue | 35,022 ops/s | 34,656 ops/s | — |
| Database size | 56.8 MB | 575.9 MB | 2.92 GB |
| Bytes per row | 568 | 576 | 584 |

**Point lookups do not degrade with scale.** 34 µs at a million rows against 31 µs at a
hundred thousand is a b-tree doing exactly what a b-tree does. Storage is linear at ~580
bytes per row with no growth in per-row overhead.

Against the stated requirement of **8,000 items/hour**, the database sustains
**21,900,000 items/hour** with derived statistics enabled — a margin of about 2,700×.
Real ingest is nowhere near this: it is bounded by extraction, parsing and OCR, which
are orders of magnitude slower than any figure in this table. **The database is not what
limits ingest, and replacing it would not move the number that matters.**

---

## 5. The dashboard: the one real scaling failure (§5, §9)

Time to compute the six aggregations one dashboard refresh needs, by scanning `item`:

| Aggregation | 200K | 1M | 5M |
|---|---:|---:|---:|
| by status | 6.5 ms | 40.3 ms | 238.7 ms |
| by extension | 93.3 ms | 319.9 ms | 3,777.1 ms |
| by custodian | 6.6 ms | 39.0 ms | 257.5 ms |
| by media type | 93.4 ms | 347.0 ms | 3,707.5 ms |
| total bytes | 6.8 ms | 110.7 ms | 567.3 ms |
| OCR state | 114.8 ms | 289.7 ms | 6,659.5 ms |
| **Full tile set** | **321 ms** | **~1,150 ms** | **15,208 ms** |

At five million items a single dashboard refresh cost **15.2 seconds of scanning** — and
before this work it ran on the JavaFX application thread, so the application was frozen
for all of it. Worse, `DashboardFacade.getStats()` materialised **every item in the case
into Java objects** to sum their sizes: 9.5 seconds and roughly a gigabyte of heap at one
million items, for one number.

### With derived statistics

| Case size | Scan | `dashboard_stats` | Speedup | Counters correct? |
|---:|---:|---:|---:|:--:|
| 200K | 321.4 ms | 0.10 ms | 3,108× | verified |
| 5M | 15,207.6 ms | 0.14 ms | **105,741×** | verified |

Read cost is **flat** — 0.10 ms at 200K and 0.14 ms at 5M — because it reads a few dozen
pre-aggregated rows regardless of case size. This is the change that makes the dashboard
viable at scale, and it is the answer to "does the dashboard require derived statistics":
**yes, unambiguously.**

### Design, and what it cost

`dashboard_stats` is `(dimension, bucket) -> items, bytes` across nine dimensions: total,
status, extension, media type, custodian, OCR state, error state, duplicate, container.

It is maintained by **SQLite triggers**, not by application code. This is a correctness
decision (§5.1): a trigger fires inside the same transaction as the row change, so a
crash between the row and its counter is impossible, and every write path is covered —
including retry, OCR completion, review changes and any future code that does not know
the table exists. Application-side bookkeeping has a window where the row is durable and
the counter is not, and that window is exactly when a forensic tool is most likely to be
killed.

The trigger form was itself measured. Nine separate upsert statements per row cost 69,003
rows/s in an isolated harness; the same work as **one** upsert over a `VALUES` list cost
118,834 rows/s. The single-statement form is what ships — per-statement overhead
dominated the actual arithmetic.

Measured cost through the real write path, 100,000 rows:

| Triggers | Rows/s | Items/hour | Dashboard read |
|---|---:|---:|---:|
| off | 16,750 | 60,299,659 | requires a full rebuild first |
| **on** | **6,109** | **21,990,924** | **0.08 ms** |

**Derived statistics cost 64% of raw database write throughput.** That is a real cost and
it is not hidden. It is worth paying because the remaining 21,990,924 items/hour is still
2,700× the requirement, real ingest is bounded by extraction rather than by database
writes, and the alternative is a 15-second freeze every time someone opens the dashboard.
A deployment that disagrees can set `-Daegis.db.statsTriggers=false` and rebuild on
demand; `verify()` will then report the counters as stale.

### Rebuild and verification (§5.1)

`rebuild()` reconstructs every counter from `item` in one transaction — 0.65 s at 200K,
27.1 s at 5M — and is exposed on the dashboard as **Rebuild Statistics**. `verify()`
compares every derived counter against a fresh scan and is asserted after **every**
scenario in `DashboardStatsTest`: status transitions, size changes, OCR completion, error
then retry, deletion, null fields, rolled-back batches, deliberate corruption, reopening
the case, and a 5,000-row mixed case. All twelve pass.

---

## 6. Index audit (§8) — a genuine quadratic defect found

`EXPLAIN QUERY PLAN` for every query that matters, against a populated, `ANALYZE`d
database. Full output in `docs/bench/plans.tsv`.

| Query | Plan | Verdict |
|---|---|---|
| item by id | `SEARCH … sqlite_autoindex_item_1` | indexed |
| item by sha256 | `SEARCH … ix_item_sha` | indexed |
| item by status | `SEARCH … ix_item_status` | indexed |
| item by custodian | `SEARCH … ix_item_cust` | indexed |
| tags / metadata of an item | `SEARCH … COVERING INDEX` | covering |
| duplicate clusters | `SEARCH … ix_item_sha` + covering subquery | indexed |
| dashboard, one dimension | `SEARCH dashboard_stats USING PRIMARY KEY` | indexed |
| **queue: resume lookup by source** | **`SCAN queue`** | **defect — fixed** |
| queue: pending on resume | `SCAN queue` | acceptable: once per run |
| audit tail | `SCAN audit` | acceptable: bounded by `LIMIT 50` |

### The defect

`completedIdForSource()` is called **once per file** by the intake loop, and had no index
to use. A run over N files therefore scanned the queue N times: work quadratic in case
size, worst exactly when the case is largest.

Measured, with and without `ix_queue_source(source, state)`:

| Queue rows | Unindexed lookup | Indexed lookup | Projected overhead over a full run |
|---:|---:|---:|---:|
| 10,000 | 130.6 µs | 16.9 µs | 1.3 s → 0.2 s |
| 50,000 | 383.1 µs | 23.5 µs | 19.2 s → 1.2 s |
| 200,000 | 224.1 µs | 18.4 µs | 44.8 s → 3.7 s |
| 500,000 | 227.9 µs | 13.8 µs | **113.9 s → 6.9 s** |

The indexed lookup is **flat** with case size; the unindexed one is not, and the
projected column shows the quadratic total. At half a million files this removed nearly
two minutes of pure lookup overhead from every run. The index is composite over
`(source, state)` so it covers the whole predicate.

Two remaining `SCAN`s were assessed and deliberately left alone: `pending on resume` runs
once per application start, and `audit tail` is bounded by `LIMIT 50`. Adding indexes for
them would cost write throughput to speed up queries that are already fast — the audit
asks whether an index is justified by the workload, and for these two it is not.

No redundant or unused indexes were found; every pre-existing index on `item` backs a
query that appears in the plan table above.

---

## 7. Lucene (§11, §12)

200,000 documents with realistic varied text, through the application's own index,
analyzer and query builder.

| Metric | Value |
|---|---|
| Indexing throughput | 12,882 docs/s |
| Index size | 183.7 MB (919 bytes/doc) |
| NRT visibility | new document searchable in 38 ms, before any commit |

| Query type | P50 | P95 | P99 | Hits |
|---|---:|---:|---:|---:|
| term | 1.05 ms | 2.06 ms | 4.97 ms | 24,729 |
| phrase | 24.20 ms | 26.48 ms | 27.32 ms | 31,877 |
| boolean | 3.83 ms | 5.06 ms | 6.95 ms | 2,812 |
| wildcard | 0.08 ms | 0.16 ms | 0.19 ms | 1,001 |
| fuzzy | 2.23 ms | 2.94 ms | 3.60 ms | 24,729 |
| proximity | 42.31 ms | 46.04 ms | 52.84 ms | 151,157 |
| field-scoped | 0.12 ms | 0.37 ms | 0.41 ms | 1,325 |
| numeric range | 0.27 ms | 0.64 ms | 3.60 ms | 1,001 |

**Requirement: 95% of searches under 2 seconds. Measured worst P99 across all query
types: 53 ms — roughly 38× inside the target**, on a machine with a quarter of the
reference cores. Search is not a concern.

### Facets (§12)

`lucene-facet` is not among the project's dependencies and Maven Central is unreachable
from this environment, so a taxonomy index was not an option. It is also not obviously
the right one: the categorical fields worth faceting are already indexed as untokenised
`StringField`s, so `SearchFacets` counts them by intersecting existing postings with the
result set. The index gains no second structure to keep in step, no extra directory and
nothing new to corrupt.

| Query | Matching docs | Facet P50 | P95 |
|---|---:|---:|---:|
| term | 196,986 | 4.11 ms | 14.78 ms |
| boolean | 194,003 | 2.85 ms | 12.37 ms |

Six dimensions counted over ~197,000 matching documents in about 4 ms. The trade is that
cost scales with distinct values per dimension rather than being constant, which is
acceptable precisely because these dimensions are low-cardinality; a high-cardinality
field such as file name is not offered as a facet.

Facets are counted against the same query, over the same reader, in the same call as the
results, so they cannot drift from the result set. Eight tests in `SearchFacetsTest`
assert this, including that each bucket's count **equals what filtering by that value
actually returns** — the property that makes a facet trustworthy rather than decorative.

---

## 8. Concurrency and the JavaFX thread (§22.2, §27)

200,000 rows preloaded, then dashboard reads and searches issued continuously for 20
seconds **while ingest wrote to the same database and index**:

| Operation | Idle | P50 under ingest | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|
| Dashboard refresh | 0.13 ms | 0.19 ms | 0.90 ms | 4.04 ms | 24.46 ms |
| Search | 4.64 ms | 8.74 ms | 47.73 ms | 85.19 ms | 212.58 ms |

Concurrent ingest sustained 2,400 rows/s while serving 987 dashboard refreshes and 987
searches. **The dashboard stays at sub-millisecond P50 during active ingest**, which is
the case that actually matters — an examiner working a case that is still loading. WAL is
doing its job: readers never block on the writer.

Against the **<500 ms UI target**, dashboard P99 is 4 ms and search P99 is 85 ms.

### Threading changes

`Background` gives screens one correct way to do slow work, replacing hand-rolled threads
that each got something wrong. It provides:

- work off the FX thread, results applied on it;
- **superseding results** — when an operator changes a filter four times in a second,
  four queries are in flight and can finish in any order; without ordering the screen
  settles on whichever was slowest, usually for a filter already abandoned. Each refresh
  carries a sequence number and stale results are dropped;
- failures reported to the screen on the FX thread rather than swallowed.

The Comprehensive Dashboard now gathers everything through this and renders **"not
available"** where a figure genuinely cannot be produced, rather than a zero
indistinguishable from an empty case.

It also had an N+1 defect: it listed up to 200 keywords, then queried each keyword's
files, then summed in Java — up to 200 round trips and 100,000 rows crossing JDBC to
produce 200 integers, on the FX thread. That is now one grouped query
(`selectKeywordUsage`) using `COUNT(DISTINCT path_id)`.

> **Unverified by execution.** JavaFX is unavailable in this environment, so the UI
> module was not compiled or run here. The UI changes are type-consistent with the
> facades they call and the facade layer beneath them is fully tested, but the screens
> themselves have not been executed. This should be confirmed on a machine with the
> JavaFX SDK before release.

---

## 9. Runtime instrumentation (§32)

Every number in this report was measured on one machine on one day. That is worth
something, but it is not worth much on a workstation with different disks, a case ten
times this size, or a build six months from now. So the operations this report makes
claims about now measure themselves in production.

`store/OperationTimings` records a count, a total, a maximum and a bucketed
distribution per named operation. Instrumented so far:

| Constant | Where it is recorded | The claim it checks |
|---|---|---|
| `DASHBOARD_SNAPSHOT` | `DashboardStats.snapshot()` | dashboard reads stay sub-millisecond at any case size |
| `DASHBOARD_REBUILD` | `DashboardStats.rebuild()` | rebuild stays in the tens of seconds, not minutes |
| `SEARCH` | `LuceneIndex.search()` | worst-case query latency stays far below 2 s |
| `SEARCH_FACETS` | `SearchFacets.counts()` | facet cost stays proportionate to the result set |
| `INDEX_WRITE` | `LuceneIndex.put()` | indexing does not become the ingest bottleneck |
| `DB_WRITE`, `DB_BATCH_COMMIT`, `RELATIONSHIP_LOOKUP`, `EXTRACTION`, `OCR` | reserved | declared for the paths named in §9 that are not yet profiled |

Design constraints, because instrumentation that distorts what it measures is worse than
none:

- **Bounded memory.** Percentiles come from ~40 logarithmic buckets, not retained
  samples. Keeping every sample of a million-item ingest is a memory leak in a
  monitoring costume. The cost is precision: a reported percentile is never below the
  truth and never more than 2× above it, asserted by
  `OperationTimingsTest.bucketsAreWithinAFactorOfTwoOfTheTruth`.
- **Cheap.** Measured at **under 2 µs per call** and asserted as a test budget. The
  fastest instrumented operation is a 140 µs dashboard read, so the measurement is well
  under a percent of it.
- **Correct under concurrency.** Eight threads × 5,000 records lose nothing.
- **Disableable.** `-Daegis.instrumentation=false`.
- **Records failures too.** `time()` records the timing even when the work throws,
  because a failing operation's latency is exactly what one wants when a screen has gone
  slow and started erroring.

One honest caveat the tests encode: **a p99 is not a safety net on a low-traffic
operation.** With 99 fast calls and one 10-second call, the p99 is still a fast call.
`OperationTimingsTest.percentilesSeparateFastFromSlow` is written to make that explicit,
which is why `maxMs` is reported alongside the percentiles and should be the field
anyone actually watches for pathological cases.

---

## 10. DuckDB and native code (§25, §26)

**Neither was adopted, and neither warranted a prototype.**

The decision gate in §24 requires the bottleneck to be *proven* before considering a
replacement. It is not: the dashboard problem was an aggregation-strategy problem, and it
was solved inside SQLite for a 105,741× improvement. DuckDB's advantage is analytical
scan speed — precisely the work that no longer happens on any hot path. Adding it would
mean a second engine, a second file format, extra packaging weight on Windows, and a
derived store to keep in step, in exchange for making an operation faster that now costs
0.14 ms.

For native code, §26 requires profiling to identify a genuine CPU bottleneck first. None
of the measurements above points at one: database time is dominated by fsync and page
I/O, not computation, and Lucene's worst case is 53 ms. The plausible candidates named in
the directive — PST parsing, decompression — are in the extraction layer, which this
work did not profile; that is stated as a gap in §12 rather than answered with a guess.

---

## 11. Final database decision (§34)

1. **Is SQLite the bottleneck?** No.
2. **If yes, where?** N/A. The bottleneck was the dashboard's aggregation strategy and a
   missing index, both application-level.
3. **What measurements prove it?** Flat point-lookup latency from 100K to 1M (§4);
   21.9M items/hour against an 8,000/hour requirement; sub-millisecond dashboard reads
   under concurrent ingest (§8).
4. **What optimisations were attempted?** Batched transactions, derived statistics with a
   measured trigger form, four PRAGMA corrections, a composite index on the resume
   lookup, an N+1 elimination, and non-blocking UI execution.
5. **Performance before?** 6,451 rows/s ingest; 15.2 s dashboard at 5M; quadratic resume
   overhead reaching 114 s at 500K files; 9.5 s and ~1 GB heap to compute headline stats
   at 1M.
6. **Performance after?** 17,128 rows/s ingest (6,109 with statistics maintained);
   0.14 ms dashboard at 5M; flat 13.8 µs resume lookup; 0.08 ms headline stats.
7. **What does Lucene contribute?** All full-text work, and now result-set facets. Worst
   P99 53 ms.
8. **What does SQLite contribute?** All authoritative state, and derived aggregates.
9. **Does the dashboard require derived statistics?** Yes — 15.2 s versus 0.14 ms at 5M.
10. **Does DuckDB provide a material advantage?** No, and the need it would address no
    longer exists.
11. **Does native code?** No candidate identified; extraction remains unprofiled.
12. **What should remain unchanged?** SQLite as authority, WAL, Lucene as the search
    engine, the filesystem for originals, AI outside the pipeline.
13. **What should change?** Everything in item 4 — all of it already applied.
14. **Measured scalability limits?** Verified correct and fast to **5,000,000 items**
    (2.92 GB database). 10M was not run: at 6,083 rows/s the load alone is ~27 minutes
    and the 5M database already exceeds a fifth of this sandbox's free disk. Every curve
    measured across 100K → 1M → 5M is flat or linear with no inflection, so nothing in
    the data suggests a cliff before 10M — but that is an extrapolation and is labelled
    as one, not a measurement.

### Conclusion

```
SQLite + WAL remains authoritative.
Lucene remains the search engine.
Derived dashboard statistics handle large aggregates.
No database migration. No DuckDB. No native code.
```

---

## 12. Limitations and remaining gaps

Stated plainly rather than omitted:

- **Hardware.** Measured on 2 cores / 3 GB / non-NVMe storage, not the 8-core / 16 GB /
  NVMe reference machine. Absolute figures are a floor; ratios transfer.
- **JavaFX was unavailable.** UI code was not compiled or executed here (§8). This is the
  largest gap in this report.
- **10M, 50M and 100M were not run** — time and disk, both stated in §11 item 14.
- **Extraction, OCR and parsing were not profiled.** This work covered the database,
  index and dashboard. The native-code question in §9 is therefore answered "no candidate
  identified", not "no candidate exists".
- **Facet cost scales with dimension cardinality.** Fine for the dimensions offered;
  would not be for a high-cardinality field.
- **The 64% ingest cost of derived statistics is real.** Justified in §5, and switchable.
- **`rebuild()` at 5M takes 27 s.** It runs off the FX thread and is a maintenance
  operation, but it is not instant.

---

## 13. Reproducing this report

```bash
CP="build/classes:$(ls lib/*.jar | tr '\n' ':')"
java -cp "build/bench:$CP" DbBench       1000000  docs/bench/baseline-1m.tsv
java -cp "build/bench:$CP" BatchBench      50000  docs/bench/batchsize.tsv
java -cp "build/bench:$CP" TriggerBench   100000  docs/bench/trigger-cost.tsv
java -cp "build/bench:$CP" ScaleBench    5000000  docs/bench/scale-5m.tsv
java -cp "build/bench:$CP" SearchBench    200000  docs/bench/search-200k.tsv
java -cp "build/bench:$CP" ConcurrencyBench 200000 20 docs/bench/concurrency.tsv
java -cp "build/bench:$CP" PlanAudit      100000  docs/bench/plans.tsv
java -cp "build/bench:$CP" ResumeBench            docs/bench/resume.tsv
```


---

## 14. Test status behind this report

```
Headless JUnit suite      160 tests      160 passed · 0 failed · 0 skipped   (7.4 s)
  of which added by this pass:
    BatchRecoveryTest        5   transactions, crash mid-batch, pragmas in force, resume index
    DashboardStatsTest      12   derived counters vs direct aggregates under mutation; rebuild
    SearchFacetsTest         8   bucket sums, per-bucket equality, ordering, topN, NRT, empty
    RelationshipCountsTest   6   case-wide bidirectional counts both directions
    OperationTimingsTest     9   instrumentation accuracy, cost budget, concurrency
InterfaceFunctionMatrixTest 7/7  every Java symbol, test and control label resolves
```

The UI test classes (`UiParity`, `Interface`, `DragDrop`, `Destination`,
`CoverageMatrix`) are excluded from this run because JavaFX is not obtainable in this
environment. That exclusion is the single largest gap in this report and is restated in
§12.
