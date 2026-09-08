import com.aegis.fdx.model.Item;
import com.aegis.fdx.store.CaseDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Section 7: measures ingest write throughput at candidate commit batch sizes.
 * Batch size 1 is the current per-item-autocommit behaviour.
 *
 * Usage: BatchBench <rowsPerRun> [outputTsv]
 */
public final class BatchBench {

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 50_000;
        Path out = args.length > 1 ? Path.of(args[1]) : null;
        int[] sizes = {1, 100, 500, 1_000, 5_000, 10_000};
        List<String> tsv = new ArrayList<>();
        tsv.add("batch_size\trows\tseconds\trows_per_sec\titems_per_hour\tpeak_heap_mb\tdb_bytes");

        System.out.printf("== commit batch-size sweep: %,d rows per run ==%n%n", rows);
        System.out.printf("%-12s %12s %14s %16s %12s%n",
                "batch", "seconds", "rows/s", "items/hour", "heap MB");

        for (int size : sizes) {
            Path dir = Files.createTempDirectory("aegis-batch");
            Path db = dir.resolve("case.db");
            Random rnd = new Random(42);
            System.gc();
            long heap0 = used();
            long peak = heap0;
            double sec;
            long dbSize;
            try (CaseDatabase cdb = new CaseDatabase(db)) {
                long t0 = System.nanoTime();
                if (size == 1) {
                    for (int i = 0; i < rows; i++) {
                        cdb.save(DbBench.item(i, rnd));
                        if ((i & 8191) == 0) peak = Math.max(peak, used());
                    }
                } else {
                    for (int start = 0; start < rows; start += size) {
                        int end = Math.min(rows, start + size);
                        cdb.begin();
                        try {
                            for (int i = start; i < end; i++) cdb.save(DbBench.item(i, rnd));
                            cdb.commit();
                        } catch (Exception e) {
                            cdb.rollback();
                            throw e;
                        }
                        peak = Math.max(peak, used());
                    }
                }
                sec = (System.nanoTime() - t0) / 1e9;
                dbSize = Files.size(db);
            }
            double rps = rows / sec;
            double heapMb = (peak - heap0) / (1024.0 * 1024.0);
            System.out.printf("%-12d %12.2f %,14.0f %,16.0f %12.1f%n",
                    size, sec, rps, rps * 3600, heapMb);
            tsv.add(size + "\t" + rows + "\t" + String.format("%.3f", sec)
                    + "\t" + String.format("%.0f", rps)
                    + "\t" + String.format("%.0f", rps * 3600)
                    + "\t" + String.format("%.1f", heapMb) + "\t" + dbSize);
            deleteTree(dir);
        }

        if (out != null) {
            Files.write(out, tsv);
            System.out.println("\nwrote " + out);
        }
    }

    static long used() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    static void deleteTree(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
