# AEGIS-FDX — Performance Report

Version 1.0.0

---

## 1. Status of this report

This report separates two kinds of measurement, as required:

| Class | Meaning |
|---|---|
| **Development environment** | Engineering indicator. **Not** certification-grade. |
| **Reference hardware** | Certification-grade. 8 cores / 16 GB / NVMe. |

**Current status: development results only.** Reference-hardware certification has
**not** been performed — that hardware was not available in the build environment.
Section 5 is the procedure to complete it; the harness runs unchanged.

The benchmark self-classifies its environment and labels its own output, so a
development run can never be mistaken for a certified one:

```
=== Environment classification ===
  cores=2  maxHeap=800 MB  corpus=132 files
  CLASS: DEVELOPMENT ENVIRONMENT — engineering indicator only.
```

---

## 2. Development environment results

**Environment:** 2 cores, ~1 GB usable heap, container filesystem, Linux x64,
Temurin 21.0.12. This is **far below** the reference spec.

| Metric | Result | Target | Verdict |
|---|---:|---:|---|
| Ingest throughput | ~456,000 items/h | ≥ 8,000 | PASS |
| Search median | 1.05 ms | — | |
| Search p95 | 6.06 ms | < 2,000 ms | PASS |
| Search p99 | 9.31 ms | — | |
| UI event handling (max) | 5.57 ms | < 500 ms | PASS |
| Peak heap | 53 MB | 4,096 MB budget | PASS |
| Process CPU load | 86% | — | |

Machine-readable output: `benchmark-result.csv` in the benchmark work directory.

### Dataset characteristics — why throughput reads high

**This is the single most important caveat in this report.**

| Property | Value |
|---|---|
| Files | 132 (44-file dataset × 3) |
| Total volume | 274 KB |
| Mean file size | ~2 KB |
| Composition | PDF, DOCX, XLSX, PPTX, EML, MBOX, PST, ZIP/7Z/TAR/GZ, images, text, corrupt and encrypted fixtures |
| Nesting | to depth 3 |
| OCR | disabled for the throughput figure |

The corpus is **small documents**, so per-item overhead dominates and almost no time
is spent on parsing or I/O. Real evidence — multi-megabyte PDFs, multi-gigabyte PSTs —
will be **substantially slower per item**. The ~456k items/h figure demonstrates the
pipeline has no pathological overhead; it is **not** a prediction of production
throughput.

Search latency is more transferable, but was measured against a 174-document index.
Lucene latency grows roughly with the log of index size, so p95 will rise on
millions of documents — the 2 s target has large headroom, but this must be
confirmed at scale.

---

## 3. What has and has not been demonstrated

| Requirement | Target | Status |
|---|---|---|
| N-02 throughput | ≥ 8,000 items/h, OCR off | Indicated, **not certified** |
| N-02 scale | ≥ 5M items, ≥ 1 TB | **Not demonstrated** |
| F-18 search | 95% < 2 s on 10M items | Indicated at small scale, **not certified** |
| N-03 UI latency | < 500 ms, pausable | **Demonstrated** (max 5.57 ms) |
| N-04 heap | 4 GB default, stream > 100 MB | Configured and **demonstrated** |
| N-05 fault isolation | No single failure aborts a run | **Demonstrated** |
| AT-06 | 100k mixed items < 2.5 h | **Not run** |

Honest summary: **correctness and fault-isolation properties are demonstrated.
Scale properties are not.**

---

## 4. Architectural basis for scale

Why the targets are expected to hold, pending certification:

- **Streaming over buffering.** Files above 100 MB are hashed and parsed as streams,
  so peak memory tracks the worker count, not evidence size. Peak heap was 53 MB
  against a 4 GB budget.
- **Durable queue.** All state lives in SQLite (WAL) and Lucene, not in memory, so
  case size is not bounded by RAM and any run is resumable.
- **NRT indexing.** The index is searchable during ingest; search does not wait.
- **Bounded parallelism.** Workers default to cores − 1; OCR runs on its own pool
  after intake and never blocks it.
- **Per-element isolation.** Every failure is contained to its element.

Known scaling risks to watch during certification:

1. **Lucene merge pressure** at tens of millions of documents — may need a tuned
   merge policy.
2. **SQLite write contention** at high worker counts — batched transactions exist;
   may need enlarging.
3. **Regex queries** scan stored text and are O(corpus). They are the most likely
   query type to breach the 2 s target at 10M documents; combine with filters.
4. **Very large single files** (50 GB+ PSTs) are single-threaded per container.

---

## 5. Reference-hardware certification procedure

**Hardware:** 8 cores, 16 GB RAM, NVMe SSD, Windows 10/11 x64.

### 5.1 Prepare
```bash
git clone <repo> && cd aegis-fdx
./tools/fetch-deps.sh
./run-tests.sh                    # confirm 179/179 before benchmarking
```

### 5.2 Scaled throughput
```bash
./run-tests.sh 2000               # ~88,000 synthetic files
```
The benchmark will self-report `CLASS: REFERENCE HARDWARE`. Record
`benchmark-result.csv`.

### 5.3 AT-06 — 100k mixed items
Assemble a real mixed corpus of ~100,000 items including multi-GB PSTs, large PDFs
and deep archives. Ingest with OCR off; record wall time. **Target: < 2.5 hours.**

### 5.4 Large-case validation
Build toward ≥ 5M items / ≥ 1 TB incrementally (100k → 1M → 5M), recording at each
step: throughput, index size, peak heap, search p95, and time to first result.

### 5.5 Search at scale
On the largest index, run the 20-query battery (terms, phrases, wildcard, fuzzy,
boolean, fielded, date-range, **regex**) five times, discarding the warm-up.
**Target: p95 < 2 s.** Record regex separately — it is the expected outlier.

### 5.6 UI responsiveness under load
During a saturating ingest, confirm the window stays interactive, Pause/Cancel
respond immediately, and searches return. **Target: no blocking > 500 ms.**

### 5.7 Resource profile
Record peak/steady heap, CPU utilisation across cores, disk throughput, and final
index/text/database sizes as a percentage of evidence volume.

### 5.8 Record results
Replace section 2 with a **Reference hardware results** section, keeping the
development figures for comparison, and state dataset characteristics for both.

---

## 6. Tuning guidance

| Situation | Change |
|---|---|
| Large cases (>1M items) | `-Xmx8g` or higher; raise index memory to 8192 MB |
| Slow ingestion | Confirm evidence and case folder are on local SSD, not a network share |
| OCR-heavy cases | Expect 0.5–3 s per page; OCR is the dominant cost — plan overnight runs |
| Slow regex queries | Combine with type/custodian/date filters to shrink the candidate set |
| Memory pressure | Lower worker count and index memory; the pipeline streams regardless |
