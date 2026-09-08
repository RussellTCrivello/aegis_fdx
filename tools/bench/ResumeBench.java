import com.aegis.fdx.store.CaseDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * §8: the cost of the per-file resume lookup, with and without ix_queue_source.
 *
 * <p>This is the query {@code IngestPipeline} runs once for every file it intakes.
 * Without an index it is a full scan of the queue table, so the total work over a run
 * grows with the square of the case size. The point of this benchmark is to show that
 * shape, not just a single number.
 *
 * Usage: ResumeBench [outputTsv]
 */
public final class ResumeBench {

    public static void main(String[] args) throws Exception {
        Path out = args.length > 0 ? Path.of(args[0]) : null;
        List<String> tsv = new ArrayList<>();
        tsv.add("queue_rows\tindexed\tlookup_us\tprojected_run_seconds");

        System.out.println("== per-file resume lookup (IngestPipeline, once per file) ==\n");
        System.out.printf("%12s %10s %14s %22s%n",
                "queue rows", "indexed", "lookup (us)", "projected run (s)");

        for (int rows : new int[]{10_000, 50_000, 200_000, 500_000}) {
            for (boolean indexed : new boolean[]{false, true}) {
                Path dir = Files.createTempDirectory("aegis-resume");
                double us;
                try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
                    if (!indexed) {
                        try (Statement st = db.connection().createStatement()) {
                            st.executeUpdate("DROP INDEX IF EXISTS ix_queue_source");
                        }
                    }
                    db.begin();
                    for (int i = 0; i < rows; i++) {
                        db.enqueue("E-" + i, "/evidence/vol/dir/file-" + i + ".pdf", null, 0);
                    }
                    db.commit();
                    for (int i = 0; i < rows; i += Math.max(1, rows / 500)) {
                        db.setQueueState("E-" + i, "DONE");
                    }
                    try (Statement st = db.connection().createStatement()) {
                        st.executeUpdate("ANALYZE");
                    }

                    int probes = indexed ? 2000 : 200;   // unindexed is far too slow for 2000
                    long t0 = System.nanoTime();
                    for (int i = 0; i < probes; i++) {
                        db.completedIdForSource("/evidence/vol/dir/file-" + (i % rows) + ".pdf");
                    }
                    us = (System.nanoTime() - t0) / 1000.0 / probes;
                }
                // One lookup per file: a run over `rows` files pays this `rows` times.
                double projected = us * rows / 1e6;
                System.out.printf("%,12d %10s %14.1f %22.1f%n", rows, indexed, us, projected);
                tsv.add(rows + "\t" + indexed + "\t" + String.format("%.1f", us)
                        + "\t" + String.format("%.1f", projected));
                deleteTree(dir);
            }
        }
        if (out != null) { Files.write(out, tsv); System.out.println("\nwrote " + out); }
    }

    static void deleteTree(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
