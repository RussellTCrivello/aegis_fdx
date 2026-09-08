import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.index.LuceneQueryBuilder;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.DashboardStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §22.2 concurrency: what a dashboard refresh and a search cost while ingest is
 * running, which is the case that actually matters — an examiner searching a case
 * that is still loading.
 *
 * Usage: ConcurrencyBench <preloadRows> <seconds> [outputTsv]
 */
public final class ConcurrencyBench {

    public static void main(String[] args) throws Exception {
        int preload = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        Path out = args.length > 2 ? Path.of(args[2]) : null;
        Path dir = Files.createTempDirectory("aegis-conc");
        List<String> tsv = new ArrayList<>();

        System.out.printf("== concurrency: %,d preloaded, %ds of contention ==%n%n",
                preload, seconds);

        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"));
             LuceneIndex index = new LuceneIndex(dir.resolve("index"), 256)) {

            Random rnd = new Random(42);
            int batch = CaseDatabase.INGEST_BATCH_SIZE;
            for (int start = 0; start < preload; start += batch) {
                int end = Math.min(preload, start + batch);
                db.begin();
                for (int i = start; i < end; i++) db.save(DbBench.item(i, rnd));
                db.commit();
            }
            for (int i = 0; i < Math.min(preload, 100_000); i++) index.put(SearchBench.doc(i, rnd));
            index.commit();
            System.out.printf("preloaded %,d rows, %,d documents%n%n", preload, index.count());

            // ---- baseline: dashboard and search with nothing else running ----
            DashboardStats stats = new DashboardStats(db);
            double idleDash = median(() -> { stats.snapshot(); return null; }, 50);
            double idleSearch = median(() -> {
                index.searcher().search(
                        LuceneQueryBuilder.build("invoice AND payment", index.analyzer()), 50);
                return null;
            }, 50);
            System.out.printf("idle:      dashboard %6.2f ms   search %6.2f ms%n",
                    idleDash, idleSearch);
            tsv.add("idle_dashboard_ms\tconcurrency\t" + String.format("%.2f", idleDash));
            tsv.add("idle_search_ms\tconcurrency\t" + String.format("%.2f", idleSearch));

            // ---- now the same while ingest hammers the same database and index ----
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong written = new AtomicLong();
            Thread ingest = new Thread(() -> {
                Random r = new Random(7);
                int i = preload;
                try {
                    while (!stop.get()) {
                        db.begin();
                        for (int k = 0; k < 1000 && !stop.get(); k++, i++) {
                            db.save(DbBench.item(i, r));
                            index.put(SearchBench.doc(i, r));
                        }
                        db.commit();
                        index.commit();
                        written.addAndGet(1000);
                    }
                } catch (Exception e) {
                    System.out.println("ingest thread: " + e);
                }
            }, "bench-ingest");
            ingest.setDaemon(true);

            List<Double> dash = new ArrayList<>();
            List<Double> search = new ArrayList<>();
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            ingest.start();
            while (System.currentTimeMillis() < deadline) {
                long t = System.nanoTime();
                stats.snapshot();
                dash.add((System.nanoTime() - t) / 1e6);

                t = System.nanoTime();
                index.searcher().search(
                        LuceneQueryBuilder.build("invoice AND payment", index.analyzer()), 50);
                search.add((System.nanoTime() - t) / 1e6);
                Thread.sleep(5);
            }
            stop.set(true);
            ingest.join(30_000);

            report("dashboard under ingest", dash, tsv, "conc_dashboard");
            report("search under ingest", search, tsv, "conc_search");
            double rate = written.get() / (double) seconds;
            System.out.printf("%nconcurrent ingest sustained %,.0f rows/s while serving "
                    + "%d dashboard and %d search calls%n", rate, dash.size(), search.size());
            tsv.add("conc_ingest_rows_per_sec\tconcurrency\t" + String.format("%.0f", rate));
        }
        if (out != null) { Files.write(out, tsv); System.out.println("wrote " + out); }
    }

    static void report(String label, List<Double> v, List<String> tsv, String key) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        System.out.printf("%-24s p50 %6.2f  p95 %6.2f  p99 %6.2f  max %6.2f ms%n", label,
                a[a.length / 2], a[(int) (a.length * .95)], a[(int) (a.length * .99)], a[a.length - 1]);
        tsv.add(key + "_p50_ms\tconcurrency\t" + String.format("%.2f", a[a.length / 2]));
        tsv.add(key + "_p95_ms\tconcurrency\t" + String.format("%.2f", a[(int) (a.length * .95)]));
        tsv.add(key + "_p99_ms\tconcurrency\t" + String.format("%.2f", a[(int) (a.length * .99)]));
    }

    interface Op { Object run() throws Exception; }

    static double median(Op op, int n) throws Exception {
        double[] ms = new double[n];
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            op.run();
            ms[i] = (System.nanoTime() - t) / 1e6;
        }
        Arrays.sort(ms);
        return ms[n / 2];
    }
}
