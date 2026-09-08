import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.DashboardStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * §5/§7: what maintaining the derived dashboard counters actually costs at ingest
 * time, measured through the real CaseDatabase write path.
 *
 *   off  - triggers dropped; counters would need a rebuild before use (ceiling)
 *   on   - triggers active, counters correct continuously (shipped behaviour)
 *
 * Usage: TriggerBench <rows> [outputTsv]
 */
public final class TriggerBench {

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        Path out = args.length > 1 ? Path.of(args[1]) : null;
        List<String> tsv = new ArrayList<>();
        tsv.add("triggers\trows\tseconds\trows_per_sec\titems_per_hour\tdash_read_ms\tcorrect");

        System.out.printf("== derived-statistics ingest cost: %,d rows ==%n%n", rows);
        System.out.printf("%-10s %10s %14s %16s %14s %9s%n",
                "triggers", "seconds", "rows/s", "items/hour", "dash read ms", "correct");

        double base = 0;
        for (String mode : new String[]{"off", "on"}) {
            Path dir = Files.createTempDirectory("aegis-trg");
            Random rnd = new Random(42);
            double sec;
            double readMs;
            boolean correct;

            try (CaseDatabase cdb = new CaseDatabase(dir.resolve("case.db"))) {
                DashboardStats stats = new DashboardStats(cdb);
                if ("off".equals(mode)) {
                    try (Statement st = cdb.connection().createStatement()) {
                        st.executeUpdate("DROP TRIGGER IF EXISTS trg_stats_item_insert");
                        st.executeUpdate("DROP TRIGGER IF EXISTS trg_stats_item_update");
                        st.executeUpdate("DROP TRIGGER IF EXISTS trg_stats_item_delete");
                    }
                }
                long t0 = System.nanoTime();
                int batch = CaseDatabase.INGEST_BATCH_SIZE;
                for (int start = 0; start < rows; start += batch) {
                    int end = Math.min(rows, start + batch);
                    cdb.begin();
                    try {
                        for (int i = start; i < end; i++) cdb.save(DbBench.item(i, rnd));
                        cdb.commit();
                    } catch (Exception e) { cdb.rollback(); throw e; }
                }
                sec = (System.nanoTime() - t0) / 1e9;

                if ("off".equals(mode)) {
                    stats.rebuild();
                }
                readMs = Double.MAX_VALUE;
                for (int i = 0; i < 5; i++) {
                    long t = System.nanoTime();
                    stats.snapshot();
                    readMs = Math.min(readMs, (System.nanoTime() - t) / 1e6);
                }
                correct = stats.verify();
            }
            double rps = rows / sec;
            if ("off".equals(mode)) base = rps;
            System.out.printf("%-10s %10.2f %,14.0f %,16.0f %14.2f %9s%n",
                    mode, sec, rps, rps * 3600, readMs, correct);
            tsv.add(mode + "\t" + rows + "\t" + String.format("%.3f", sec) + "\t"
                    + String.format("%.0f", rps) + "\t" + String.format("%.0f", rps * 3600)
                    + "\t" + String.format("%.2f", readMs) + "\t" + correct);
            if ("on".equals(mode)) {
                double pct = 100.0 * (1 - rps / base);
                System.out.printf("%nderived statistics cost %.0f%% of ingest throughput%n", pct);
                tsv.add("overhead_percent\t" + rows + "\t" + String.format("%.1f", pct));
            }
            deleteTree(dir);
        }
        if (out != null) { Files.write(out, tsv); System.out.println("wrote " + out); }
    }

    static void deleteTree(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
