import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.CaseDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Measured baseline for the AEGIS-FDX case database. Everything here drives the
 * real {@link CaseDatabase} class the application uses, not a synthetic schema.
 *
 * Usage: DbBench <rows> [outputTsv]
 */
public final class DbBench {

    static final String[] EXTS = {"pdf","docx","xlsx","msg","eml","jpg","png","txt","zip","pst"};
    static final String[] CUST = {"A. Farouk","B. Nowak","C. Ibrahim","D. Reyes","E. Kovac",
                                  "F. Lindqvist","G. Moreau","H. Tanaka"};
    static final String[] MEDIA = {"application/pdf","application/vnd.openxmlformats","message/rfc822",
                                   "image/jpeg","text/plain","application/zip"};

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        Path out = args.length > 1 ? Path.of(args[1]) : null;
        Path dir = Files.createTempDirectory("aegis-bench");
        Path db = dir.resolve("case.db");
        List<String> tsv = new ArrayList<>();

        System.out.println("== AEGIS-FDX database benchmark: " + rows + " items ==");
        System.out.println("db: " + db);

        try (CaseDatabase cdb = new CaseDatabase(db)) {
            // ---- runtime PRAGMA verification (section 6) ----
            System.out.println("\n-- runtime PRAGMAs --");
            for (String p : new String[]{"journal_mode","synchronous","foreign_keys","busy_timeout",
                                         "cache_size","mmap_size","temp_store","page_size",
                                         "wal_autocheckpoint"}) {
                try (Statement st = cdb.connection().createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA " + p)) {
                    String v = rs.next() ? rs.getString(1) : "?";
                    System.out.printf("   %-20s %s%n", p, v);
                    tsv.add("pragma\t" + p + "\t" + v);
                }
            }

            // ---- insert throughput ----
            Random rnd = new Random(42);
            long t0 = System.nanoTime();
            long lastReport = t0;
            for (int i = 0; i < rows; i++) {
                cdb.save(item(i, rnd));
                if (i > 0 && i % 20_000 == 0) {
                    long now = System.nanoTime();
                    System.out.printf("   %,d rows  (%,.0f rows/s inst)%n", i,
                            20_000 / ((now - lastReport) / 1e9));
                    lastReport = now;
                }
            }
            double insertSec = (System.nanoTime() - t0) / 1e9;
            double rps = rows / insertSec;
            System.out.printf("%n-- insert: %,d rows in %.2fs = %,.0f rows/s%n", rows, insertSec, rps);
            tsv.add("insert_rows\t" + rows + "\t" + rows);
            tsv.add("insert_seconds\ttotal\t" + String.format("%.3f", insertSec));
            tsv.add("insert_rows_per_sec\ttotal\t" + String.format("%.0f", rps));
            tsv.add("insert_items_per_hour\ttotal\t" + String.format("%.0f", rps * 3600));

            // ---- queue ops ----
            t0 = System.nanoTime();
            for (int i = 0; i < 2000; i++) cdb.enqueue("Q-" + i, "/ev/f" + i, null, 0);
            double qs = (System.nanoTime() - t0) / 1e9;
            System.out.printf("-- queue enqueue: %,.0f ops/s%n", 2000 / qs);
            tsv.add("queue_ops_per_sec\tenqueue\t" + String.format("%.0f", 2000 / qs));

            // ---- dashboard aggregate queries (section 9) ----
            System.out.println("\n-- dashboard aggregations (cold then warm, ms) --");
            String[][] queries = {
                {"total_items", "SELECT COUNT(*) FROM item"},
                {"by_status", "SELECT status, COUNT(*) FROM item GROUP BY status"},
                {"by_ext", "SELECT ext, COUNT(*) FROM item GROUP BY ext ORDER BY 2 DESC"},
                {"by_custodian", "SELECT custodian, COUNT(*) FROM item GROUP BY custodian"},
                {"by_media", "SELECT media_type, COUNT(*) FROM item GROUP BY media_type"},
                {"total_bytes", "SELECT SUM(size) FROM item"},
                {"ocr_state", "SELECT ocr_applied, COUNT(*) FROM item GROUP BY ocr_applied"},
                {"error_state", "SELECT COUNT(*) FROM item WHERE error IS NOT NULL"},
                {"dupe_clusters", "SELECT COUNT(*) FROM (SELECT sha256 FROM item WHERE sha256<>''"
                        + " GROUP BY sha256 HAVING COUNT(*)>1)"},
            };
            for (String[] q : queries) {
                double cold = timeQuery(cdb, q[1]);
                double warm = Double.MAX_VALUE;
                for (int i = 0; i < 5; i++) warm = Math.min(warm, timeQuery(cdb, q[1]));
                System.out.printf("   %-16s cold %8.1f   warm %8.1f%n", q[0], cold, warm);
                tsv.add("dash_cold_ms\t" + q[0] + "\t" + String.format("%.1f", cold));
                tsv.add("dash_warm_ms\t" + q[0] + "\t" + String.format("%.1f", warm));
                // query plan
                try (Statement st = cdb.connection().createStatement();
                     ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + q[1])) {
                    StringBuilder sb = new StringBuilder();
                    while (rs.next()) sb.append(rs.getString("detail")).append("; ");
                    tsv.add("plan\t" + q[0] + "\t" + sb);
                }
            }

            // ---- point lookups ----
            System.out.println("\n-- point lookups (P50/P95/P99 micros) --");
            pct(cdb, tsv, "lookup_by_id", "SELECT * FROM item WHERE id=?", rows, rnd, true);
            pct(cdb, tsv, "lookup_by_sha", "SELECT id FROM item WHERE sha256=?", rows, rnd, false);

            // ---- allItems() full materialisation, what the dashboard facade does today ----
            t0 = System.nanoTime();
            List<Item> all = cdb.allItems(id -> "");
            double allSec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("%n-- allItems() materialised %,d items in %.2fs%n", all.size(), allSec);
            tsv.add("all_items_seconds\ttotal\t" + String.format("%.3f", allSec));

            long dbSize = Files.size(db);
            System.out.printf("-- db size: %,d bytes (%.1f bytes/row)%n", dbSize, (double) dbSize / rows);
            tsv.add("db_size_bytes\ttotal\t" + dbSize);
            tsv.add("db_bytes_per_row\ttotal\t" + String.format("%.0f", (double) dbSize / rows));
        }

        if (out != null) {
            Files.write(out, tsv);
            System.out.println("\nwrote " + out);
        }
    }

    static double timeQuery(CaseDatabase cdb, String sql) throws Exception {
        long t = System.nanoTime();
        try (Statement st = cdb.connection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) { rs.getObject(1); }
        }
        return (System.nanoTime() - t) / 1e6;
    }

    static void pct(CaseDatabase cdb, List<String> tsv, String name, String sql,
                    int rows, Random rnd, boolean byId) throws Exception {
        int n = 2000;
        long[] us = new long[n];
        for (int i = 0; i < n; i++) {
            String key = byId ? "E-" + rnd.nextInt(rows) : sha(rnd.nextInt(rows));
            long t = System.nanoTime();
            try (var ps = cdb.connection().prepareStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rs.getObject(1); }
            }
            us[i] = (System.nanoTime() - t) / 1000;
        }
        java.util.Arrays.sort(us);
        System.out.printf("   %-16s p50 %6d  p95 %6d  p99 %6d%n", name,
                us[n / 2], us[(int) (n * 0.95)], us[(int) (n * 0.99)]);
        tsv.add(name + "_p50_us\tlookup\t" + us[n / 2]);
        tsv.add(name + "_p95_us\tlookup\t" + us[(int) (n * 0.95)]);
        tsv.add(name + "_p99_us\tlookup\t" + us[(int) (n * 0.99)]);
    }

    static String sha(int i) {
        return String.format("%064x", i * 2654435761L);
    }

    /** A realistic item: varied extensions, sizes, custodians, statuses, ~2% duplicates. */
    static Item item(int i, Random rnd) {
        Item it = new Item("E-" + i, "evidence-" + i + "." + EXTS[rnd.nextInt(EXTS.length)]);
        it.extension(EXTS[rnd.nextInt(EXTS.length)]);
        it.mediaType(MEDIA[rnd.nextInt(MEDIA.length)]);
        it.size((long) (Math.abs(rnd.nextGaussian()) * 500_000) + 1024);
        Instant base = Instant.now().minusSeconds(rnd.nextInt(200_000_000));
        it.created(base);
        it.modified(base.plusSeconds(rnd.nextInt(100_000)));
        it.accessed(base.plusSeconds(rnd.nextInt(200_000)));
        it.md5(String.format("%032x", i * 40503L));
        // 2% of rows share a hash with an earlier row, so duplicate detection has work to do
        it.sha256(sha(rnd.nextInt(100) < 2 ? rnd.nextInt(Math.max(1, i)) : i));
        it.sourcePath("/evidence/vol" + (i % 7) + "/dir" + (i % 997) + "/file" + i);
        it.custodian(CUST[rnd.nextInt(CUST.length)]);
        it.depth(rnd.nextInt(100) < 30 ? 1 + rnd.nextInt(3) : 0);
        int r = rnd.nextInt(100);
        it.status(r < 88 ? ItemStatus.INDEXED : r < 93 ? ItemStatus.ERROR
                : r < 96 ? ItemStatus.UNSUPPORTED : r < 98 ? ItemStatus.LOCKED : ItemStatus.PENDING);
        if (it.status() == ItemStatus.ERROR) it.errors().add("extraction failed: truncated stream");
        it.needsOcr(rnd.nextInt(100) < 15);
        it.ocrApplied(it.needsOcr() && rnd.nextInt(100) < 70);
        if (rnd.nextInt(100) < 40) it.tags().add(rnd.nextInt(2) == 0 ? "Relevant" : "Reviewed");
        it.metadata().put("Author", CUST[rnd.nextInt(CUST.length)]);
        it.metadata().put("Application", "producer-" + (i % 23));
        return it;
    }
}
