# Performance Benchmark Instructions — Reference Hardware

**Purpose:** produce the certified performance figures for AT-06, F-18, N-02 and N-03.
**This is the only way to certify performance.** Development-class runs are indicators, never certifications.

---

## 1. Required hardware

| Spec | Requirement |
|---|---|
| CPU | 8 physical cores |
| RAM | 16 GB |
| Storage | NVMe SSD |
| OS | Windows 11 x64 (Windows 10 x64 acceptable) |

The benchmark **self-classifies** the host and stamps the result `REFERENCE` or
`DEVELOPMENT`. Certification is valid only when it reports **`Class = REFERENCE`**
(requires ≥ 8 cores and ≥ 8 GB heap). This guard exists so a laptop run can never be
mistaken for a certified figure.

## 2. Before running

Windows-specific factors that materially change the numbers — set them deliberately and
record what you did:

- **Windows Defender:** real-time scanning inspects every file the ingester touches.
  Exclude the evidence folder and the case folder, or expect a large throughput penalty.
  **Record whether exclusions were applied** — a certified number without this noted is ambiguous.
- **Power plan:** set to High performance. Balanced down-clocks under sustained load.
- **Indexing service:** exclude the case folder from Windows Search.
- **Other load:** close everything else; the benchmark measures process CPU load.
- **Storage:** the corpus and the case folder should both be on NVMe. Measuring across a
  network share measures the network.

## 3. Run

```bat
cd C:\aegis-fdx
set AEGIS_JDK=C:\Program Files\Eclipse Adoptium\jdk-21\bin
powershell -ExecutionPolicy Bypass -File run-tests.ps1 -Multiplier 2000
```

The multiplier scales the generated corpus. `2000` produces a corpus large enough for a
meaningful throughput measurement; increase it if the run finishes in under a few minutes.

Heap must be at least 8 GB for REFERENCE classification. If the runner does not already
set it:

```bat
set AEGIS_HEAP=-Xmx8g
```

## 4. Output

Results are written to `<work>/benchmark-result.csv` with the schema
`Metric,Value,Unit,Target,Result`:

| Metric | Target |
|---|---|
| Class | must read `REFERENCE` |
| Cores | ≥ 8 |
| MaxHeap | ≥ 8 GB |
| CorpusFiles | as generated |
| ElementsProcessed | — |
| **IngestThroughput** | **≥ 8000 items/h** (OCR off) |
| SearchMedian | — |
| **SearchP95** | **< 2000 ms** |
| SearchP99 | — |
| **UiMaxLatency** | **< 500 ms** |
| **PeakHeap** | **< 4096 MB** at default settings |
| ProcessCpuLoad | — |
| IngestWallTime | — |

## 5. Additional Windows checks

| Check | Expected |
|---|---|
| Heap setting honoured | `-Xmx` in `AEGIS-FDX.cfg` takes effect; default 4 GB (N-03) |
| Large-file streaming | files > 100 MB stream, not fully loaded |
| Worker count | cores − 1 |
| UI during ingest | remains responsive; pause/resume/cancel react immediately (N-04) |
| Scale target | 5M items / 1 TB (N-02) — run separately if a corpus of that size is available |

## 6. Reporting

Attach `benchmark-result.csv` to the handoff record together with:

- Exact machine specification (CPU model, RAM, disk model)
- Windows version and build number
- Corpus description (multiplier used, resulting file and element counts)
- Total execution duration
- Whether Defender exclusions were applied
- Pass/fail against each target above

## 7. Reference indicator (not a certification)

For comparison only — measured on the 2-core / 800 MB development environment:

| Metric | Development-class value |
|---|---|
| Ingest throughput | ~320k–456k items/h |
| Search p95 | 5.6–7.6 ms |
| UI max latency | ~4–5.7 ms |
| Peak heap | 53 MB |

These are far above target, which is expected: the corpus is small and fits in page
cache. They demonstrate no gross regression, and nothing more. **Only a `REFERENCE`-class
run certifies performance.**
