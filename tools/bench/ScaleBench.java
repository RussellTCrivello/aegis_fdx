import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.DashboardStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sections 22.1 / 9: dashboard aggregation cost as the case grows, measured both
 * as a direct scan of {@code item} and through the derived {@code dashboard_stats}
 * table, at each dataset size.
 *
 * Usage: ScaleBench <rows> [outputTsv]
 */
public final class ScaleBench {

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        Path out = args.length > 1 ? Path.of(args[1]) : null;
        Path dir = Files.createTempDirectory("aegis-scale");
        Path db = dir.resolve("case.db");
        List<String> tsv = new ArrayList<>();
        Random rnd = new Random(42);

        System.out.printf("== scale benchmark: %,d items ==%n", rows);

        try (CaseDatabase cdb = new CaseDatabase(db)) {
            // Load with the measured-optimal batch size.
            long t0 = System.nanoTime();
            int batch = 5000;
            for (int start = 0; start < rows; start += batch) {
                int end = Math.min(rows, start + batch);
                cdb.begin();
                try {
                    for (int i = start; i < end; i++) cdb.save(DbBench.item(i, rnd));
                    cdb.commit();
                } catch (Exception e) { cdb.rollback(); throw e; }
                if (start % 500_000 == 0 && start > 0) System.out.printf("   loaded %,d%n", start);
            }
            double loadSec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("-- batched load: %,d rows in %.1fs = %,.0f rows/s%n",
                    rows, loadSec, rows / loadSec);
            tsv.add(rows + "\tbatched_load_rows_per_sec\t" + String.format("%.0f", rows / loadSec));

            // ---- direct scan aggregation (what the dashboard used to do) ----
            String[][] q = {
                {"by_status", "SELECT status, COUNT(*) FROM item GROUP BY status"},
                {"by_ext", "SELECT ext, COUNT(*) FROM item GROUP BY ext ORDER BY 2 DESC"},
                {"by_custodian", "SELECT custodian, COUNT(*) FROM item GROUP BY custodian"},
                {"by_media", "SELECT media_type, COUNT(*) FROM item GROUP BY media_type"},
                {"total_bytes", "SELECT SUM(size) FROM item"},
                {"ocr_state", "SELECT ocr_applied, COUNT(*) FROM item GROUP BY ocr_applied"},
            };
            double scanTotal = 0;
            System.out.println("\n-- direct scan (ms) --");
            for (String[] p : q) {
                double ms = Double.MAX_VALUE;
                for (int i = 0; i < 3; i++) ms = Math.min(ms, DbBench.timeQuery(cdb, p[1]));
                scanTotal += ms;
                System.out.printf("   %-14s %8.1f%n", p[0], ms);
                tsv.add(rows + "\tscan_ms_" + p[0] + "\t" + String.format("%.1f", ms));
            }
            System.out.printf("   %-14s %8.1f  <= full dashboard tile set%n", "TOTAL", scanTotal);
            tsv.add(rows + "\tscan_ms_dashboard_total\t" + String.format("%.1f", scanTotal));

            // ---- derived statistics ----
            DashboardStats stats = new DashboardStats(cdb);
            t0 = System.nanoTime();
            stats.rebuild();
            double rebuildSec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("%n-- dashboard_stats rebuild: %.2fs%n", rebuildSec);
            tsv.add(rows + "\tstats_rebuild_seconds\t" + String.format("%.3f", rebuildSec));

            double readMs = Double.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                long t = System.nanoTime();
                var snap = stats.snapshot();
                readMs = Math.min(readMs, (System.nanoTime() - t) / 1e6);
                if (i == 0) {
                    System.out.printf("   total=%,d  statuses=%d  extensions=%d  custodians=%d%n",
                            snap.totalItems(), snap.byStatus().size(),
                            snap.byExtension().size(), snap.byCustodian().size());
                }
            }
            System.out.printf("-- dashboard_stats read: %.2f ms (was %.1f ms)  speedup %.0fx%n",
                    readMs, scanTotal, scanTotal / readMs);
            tsv.add(rows + "\tstats_read_ms\t" + String.format("%.2f", readMs));
            tsv.add(rows + "\tstats_speedup\t" + String.format("%.0f", scanTotal / readMs));

            // ---- correctness: derived vs authoritative ----
            boolean ok = stats.verify();
            System.out.println("-- derived counters match authoritative scan: " + ok);
            tsv.add(rows + "\tstats_correct\t" + ok);

            long dbSize = Files.size(db);
            tsv.add(rows + "\tdb_size_bytes\t" + dbSize);
            System.out.printf("-- db size %,d bytes%n", dbSize);

            // index sizes
            try (Statement st = cdb.connection().createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='item'")) {
                while (rs.next()) tsv.add(rows + "\tindex\t" + rs.getString(1));
            }
        }
        if (out != null) { Files.write(out, tsv); System.out.println("wrote " + out); }
    }
}
