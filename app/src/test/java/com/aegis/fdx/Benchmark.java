package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.EngineEvent;
import com.aegis.fdx.engine.Filters;
import com.aegis.fdx.engine.IngestPipeline;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.index.LuceneQueryBuilder;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.CaseFolder;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Milestone-2 benchmark harness (deliverable 8).
 *
 * <p>Designed to be executed unchanged on the reference machine (8 cores, 16 GB,
 * NVMe) to produce the N-02 / F-18 / AT-06 numbers. In a constrained environment
 * it still runs and reports honestly — the scale factor is a CLI argument.
 *
 * <pre>
 *   java -cp ... com.aegis.fdx.Benchmark &lt;workdir&gt; &lt;corpusMultiplier&gt;
 * </pre>
 *
 * Measures: ingest throughput (items/hour), peak heap, CPU load, search latency
 * percentiles over a fixed query battery, and UI-thread responsiveness while the
 * pipeline is saturated.
 */
public final class Benchmark {

    private static final String[] QUERY_BATTERY = {
            "settlement", "\"wire transfer\"", "settle*", "setlement~2",
            "type:pdf", "type:docx", "custodian:\"Bench Custodian\"", "status:Indexed",
            "settlement AND payment", "settlement OR briefing", "type:pdf NOT corrupt",
            "(settlement OR ledger) AND type:pdf", "/INV-\\d{5}/",
            "date:[2020-01-01 TO 2030-12-31]", "subject:\"Wire instructions\"",
            "from:j.kowalski@northwind-legal.example", "beneficiary", "invoice",
            "nordwind", "canary"
    };

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "/tmp/aegis-bench");
        int multiplier = args.length > 1 ? Integer.parseInt(args[1]) : 1;

        deleteTree(work);
        Path corpus = work.resolve("corpus");
        Path caseDir = work.resolve("case");

        System.out.println("=== AEGIS-FDX Benchmark ===");
        System.out.println("JVM:      " + System.getProperty("java.version"));
        System.out.println("Cores:    " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max heap: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        System.out.println("Corpus:   " + multiplier + "× base dataset\n");

        // ---- build corpus ----
        long tGen = System.currentTimeMillis();
        int files = 0;
        for (int i = 0; i < multiplier; i++) {
            Path sub = corpus.resolve("batch_" + i);
            files += TestDataset.build(sub).size();
        }
        System.out.printf("Corpus:   %d files generated in %.1f s%n%n",
                files, (System.currentTimeMillis() - tGen) / 1000.0);

        CaseFolder folder = CaseFolder.createOrOpen(caseDir, "BENCH");
        CaseSettings settings = new CaseSettings();
        settings.dedupeScope(CaseSettings.DedupeScope.GLOBAL);

        HeapSampler sampler = new HeapSampler();
        sampler.start();

        long resultProcessed = 0;
        double throughput = 0;
        double uiMax = 0;
        long peakHeap = 0;
        double cpuLoad = 0;
        long wallMs = 0;

        long t0 = System.currentTimeMillis();
        IngestPipeline.Result result;
        List<Double> searchMs = new ArrayList<>();
        List<Double> uiLatency = new ArrayList<>();

        try (CaseDatabase db = new CaseDatabase(folder.database());
             LuceneIndex index = new LuceneIndex(folder.index(), settings.indexMemoryMb())) {

            // Simulated UI thread: measures the cost of handling engine events while
            // the pipeline is saturated (N-03 / AT-06).
            IngestPipeline pipe = new IngestPipeline(folder, db, index, settings, ev -> {
                long e0 = System.nanoTime();
                if (ev instanceof EngineEvent.ItemIndexed) {
                    // representative UI work: format a row
                    @SuppressWarnings("unused")
                    String row = ev.toString();
                }
                uiLatency.add((System.nanoTime() - e0) / 1e6);
            });

            result = pipe.ingest(corpus, "Bench Custodian");
            index.commit();

            long ingestMs = System.currentTimeMillis() - t0;
            sampler.stop();

            double itemsPerHour = result.processed() / (ingestMs / 3_600_000.0);
            resultProcessed = result.processed();
            throughput = itemsPerHour;
            wallMs = ingestMs;
            peakHeap = sampler.peakMb();
            cpuLoad = sampler.avgCpu();
            System.out.println("---- Ingest ----");
            System.out.printf("  elements processed : %,d%n", result.processed());
            System.out.printf("  wall time          : %.2f s%n", ingestMs / 1000.0);
            System.out.printf("  throughput         : %,.0f items/hour%n", itemsPerHour);
            System.out.printf("  errors/locked/unsup: %d / %d / %d%n",
                    result.errors(), result.locked(), result.unsupported());
            System.out.printf("  duplicates marked  : %,d%n", result.duplicates());
            System.out.printf("  peak heap          : %,d MB%n", sampler.peakMb());
            System.out.printf("  process CPU load   : %.0f%%%n", sampler.avgCpu() * 100);
            System.out.printf("  N-02 target        : %s (>= 8,000 items/hour)%n",
                    itemsPerHour >= 8000 ? "PASS" : "BELOW TARGET");

            // ---- search latency ----
            System.out.println("\n---- Search (F-18: p95 < 2000 ms) ----");
            for (int round = 0; round < 5; round++) {
                for (String q : QUERY_BATTERY) {
                    long s0 = System.nanoTime();
                    var lq = LuceneQueryBuilder.build(q, index.analyzer());
                    int n = index.search(lq, 100, null).size();
                    double ms = (System.nanoTime() - s0) / 1e6;
                    if (round > 0) searchMs.add(ms);       // discard warm-up round
                    if (round == 1) System.out.printf("  %-46s %5.1f ms  (%d hits)%n", q, ms, n);
                }
            }
            Collections.sort(searchMs);
            System.out.printf("%n  queries executed   : %d%n", searchMs.size());
            System.out.printf("  median             : %.1f ms%n", pct(searchMs, 50));
            System.out.printf("  p95                : %.1f ms%n", pct(searchMs, 95));
            System.out.printf("  p99                : %.1f ms%n", pct(searchMs, 99));
            System.out.printf("  max                : %.1f ms%n", searchMs.get(searchMs.size() - 1));
            System.out.printf("  F-18 target        : %s%n",
                    pct(searchMs, 95) < 2000 ? "PASS" : "FAIL");

            // ---- UI responsiveness ----
            Collections.sort(uiLatency);
            System.out.println("\n---- UI event handling during ingest (N-03: < 500 ms) ----");
            System.out.printf("  events             : %,d%n", uiLatency.size());
            System.out.printf("  median             : %.3f ms%n", pct(uiLatency, 50));
            System.out.printf("  p99                : %.3f ms%n", pct(uiLatency, 99));
            uiMax = uiLatency.isEmpty() ? 0 : uiLatency.get(uiLatency.size() - 1);
            System.out.printf("  max                : %.3f ms%n", uiMax);
            System.out.printf("  N-03 target        : %s%n",
                    (uiLatency.isEmpty() ? 0 : uiLatency.get(uiLatency.size() - 1)) < 500
                            ? "PASS" : "FAIL");

            // ---- storage ----
            System.out.println("\n---- Storage ----");
            System.out.printf("  index              : %,d KB%n", dirSize(folder.index()) / 1024);
            System.out.printf("  text store         : %,d KB%n", dirSize(folder.text()) / 1024);
            System.out.printf("  database           : %,d KB%n",
                    Files.exists(folder.database()) ? Files.size(folder.database()) / 1024 : 0);
            System.out.printf("  source corpus      : %,d KB%n", dirSize(corpus) / 1024);
        }

        // ---- machine classification -------------------------------------
        int cores = Runtime.getRuntime().availableProcessors();
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        boolean referenceClass = cores >= 8 && heapMb >= 8192;

        System.out.println("\n=== Environment classification ===");
        System.out.printf("  cores=%d  maxHeap=%,d MB  corpus=%,d files%n",
                cores, heapMb, files);
        if (referenceClass) {
            System.out.println("  CLASS: REFERENCE HARDWARE — results are certification-grade.");
            System.out.println("  Record these figures in docs/PERFORMANCE.md as certified.");
        } else {
            System.out.println("  CLASS: DEVELOPMENT ENVIRONMENT — engineering indicator only.");
            System.out.println("  Below the 8-core/16GB reference spec; these numbers are NOT");
            System.out.println("  certification-grade. Small documents make per-item overhead");
            System.out.println("  dominate, so throughput reads optimistically.");
            System.out.println("  Certify with: ./run-tests.sh 2000  on 8-core/16GB/NVMe.");
        }

        // ---- machine-readable result for the performance report ----------
        Path csv = work.resolve("benchmark-result.csv");
        StringBuilder sb = new StringBuilder();
        sb.append("Metric,Value,Unit,Target,Result\n");
        sb.append("Class,").append(referenceClass ? "REFERENCE" : "DEVELOPMENT")
          .append(",,,\n");
        sb.append("Cores,").append(cores).append(",count,8,")
          .append(cores >= 8 ? "MET" : "BELOW").append('\n');
        sb.append("MaxHeap,").append(heapMb).append(",MB,16384,")
          .append(heapMb >= 16384 ? "MET" : "BELOW").append('\n');
        sb.append("CorpusFiles,").append(files).append(",count,,\n");
        sb.append("ElementsProcessed,").append(resultProcessed).append(",count,,\n");
        sb.append("IngestThroughput,").append(Math.round(throughput))
          .append(",items/hour,8000,").append(throughput >= 8000 ? "PASS" : "FAIL").append('\n');
        sb.append("SearchMedian,").append(String.format("%.2f", pct(searchMs, 50)))
          .append(",ms,,\n");
        sb.append("SearchP95,").append(String.format("%.2f", pct(searchMs, 95)))
          .append(",ms,2000,").append(pct(searchMs, 95) < 2000 ? "PASS" : "FAIL").append('\n');
        sb.append("SearchP99,").append(String.format("%.2f", pct(searchMs, 99)))
          .append(",ms,,\n");
        sb.append("UiMaxLatency,").append(String.format("%.3f", uiMax))
          .append(",ms,500,").append(uiMax < 500 ? "PASS" : "FAIL").append('\n');
        sb.append("PeakHeap,").append(peakHeap).append(",MB,4096,")
          .append(peakHeap <= 4096 ? "PASS" : "OVER").append('\n');
        sb.append("ProcessCpuLoad,").append(Math.round(cpuLoad * 100)).append(",percent,,\n");
        sb.append("IngestWallTime,").append(wallMs).append(",ms,,\n");
        Files.writeString(csv, sb.toString());
        System.out.println("\n  Machine-readable result: " + csv);
    }

    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static long dirSize(Path dir) throws Exception {
        if (!Files.exists(dir)) return 0;
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { return 0; }
            }).sum();
        }
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (Exception ignored) { }
            });
        }
    }

    /** Samples heap and process CPU on a background thread. */
    private static final class HeapSampler {
        private volatile boolean running = true;
        private long peak;
        private double cpuSum;
        private int cpuN;
        private Thread thread;

        void start() {
            thread = new Thread(() -> {
                var os = ManagementFactory.getOperatingSystemMXBean();
                while (running) {
                    Runtime rt = Runtime.getRuntime();
                    long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    peak = Math.max(peak, used);
                    if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                        double l = sun.getProcessCpuLoad();
                        if (l >= 0) { cpuSum += l; cpuN++; }
                    }
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                }
            }, "bench-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            if (thread != null) thread.interrupt();
        }

        long peakMb() { return peak; }
        double avgCpu() { return cpuN == 0 ? 0 : cpuSum / cpuN; }
    }
}
