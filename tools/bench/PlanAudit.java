import com.aegis.fdx.store.CaseDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * §8 / §9: the query plan and index inventory for every query that matters, taken
 * from a populated database so the planner has real statistics to work with.
 *
 * Usage: PlanAudit [rows] [outputTsv]
 */
public final class PlanAudit {

    /** Queries that matter, as SQL with a bindable parameter marked by ?. */
    static final String[][] QUERIES = {
        {"dashboard: derived counters",
         "SELECT dimension,bucket,items,bytes FROM dashboard_stats WHERE items<>0"},
        {"dashboard: one dimension",
         "SELECT bucket,items FROM dashboard_stats WHERE dimension='ext' ORDER BY items DESC"},
        {"dashboard: status scan (replaced)",
         "SELECT status, COUNT(*) FROM item GROUP BY status"},
        {"dashboard: extension scan (replaced)",
         "SELECT ext, COUNT(*) FROM item GROUP BY ext"},
        {"duplicate clusters",
         "SELECT sha256, id FROM item WHERE sha256 IS NOT NULL AND sha256<>'' AND sha256 IN"
         + " (SELECT sha256 FROM item WHERE sha256 IS NOT NULL AND sha256<>''"
         + " GROUP BY sha256 HAVING COUNT(*)>1) ORDER BY sha256"},
        {"item by id", "SELECT * FROM item WHERE id='E-1'"},
        {"item by sha256", "SELECT id FROM item WHERE sha256='x'"},
        {"item by status", "SELECT id FROM item WHERE status='ERROR'"},
        {"item by custodian", "SELECT id FROM item WHERE custodian='A. Farouk'"},
        {"children of an item", "SELECT id FROM item WHERE parent_id='E-1'"},
        {"queue: pending on resume",
         "SELECT source FROM queue WHERE state IN ('PENDING','PROCESSING') ORDER BY id"},
        {"queue: resume lookup by source",
         "SELECT item_id FROM queue WHERE source='/x' AND state IN"
         + " ('DONE','ERROR','LOCKED','UNSUPPORTED') LIMIT 1"},
        {"queue: count by state", "SELECT COUNT(*) FROM queue WHERE state='PENDING'"},
        {"tags of an item", "SELECT tag FROM item_tag WHERE item_id='E-1'"},
        {"metadata of an item", "SELECT key,value FROM item_meta WHERE item_id='E-1'"},
        {"audit tail", "SELECT ts,user,action FROM audit ORDER BY id DESC LIMIT 50"},
    };

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        Path out = args.length > 1 ? Path.of(args[1]) : null;
        Path dir = Files.createTempDirectory("aegis-plan");
        List<String> tsv = new ArrayList<>();
        tsv.add("query\tplan\tuses_index");

        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Random rnd = new Random(42);
            int batch = CaseDatabase.INGEST_BATCH_SIZE;
            for (int start = 0; start < rows; start += batch) {
                int end = Math.min(rows, start + batch);
                db.begin();
                for (int i = start; i < end; i++) db.save(DbBench.item(i, rnd));
                db.commit();
            }
            for (int i = 0; i < 5000; i++) db.enqueue("E-" + i, "/evidence/f" + i, null, 0);
            try (Statement st = db.connection().createStatement()) {
                st.executeUpdate("ANALYZE");     // give the planner real statistics
            }

            System.out.println("== index inventory ==\n");
            System.out.printf("%-28s %-14s %s%n", "index", "table", "columns");
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name, tbl_name FROM sqlite_master WHERE type='index'"
                                 + " AND name NOT LIKE 'sqlite_%' ORDER BY tbl_name, name")) {
                while (rs.next()) {
                    String idx = rs.getString(1);
                    String tbl = rs.getString(2);
                    StringBuilder cols = new StringBuilder();
                    try (Statement s2 = db.connection().createStatement();
                         ResultSet r2 = s2.executeQuery("PRAGMA index_info(" + idx + ")")) {
                        while (r2.next()) {
                            if (cols.length() > 0) cols.append(", ");
                            cols.append(r2.getString("name"));
                        }
                    }
                    System.out.printf("%-28s %-14s %s%n", idx, tbl, cols);
                    tsv.add("index\t" + idx + "\t" + tbl + "\t" + cols);
                }
            }

            System.out.println("\n== query plans ==\n");
            for (String[] q : QUERIES) {
                StringBuilder plan = new StringBuilder();
                try (Statement st = db.connection().createStatement();
                     ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + q[1])) {
                    while (rs.next()) {
                        if (plan.length() > 0) plan.append(" | ");
                        plan.append(rs.getString("detail"));
                    }
                }
                boolean scan = plan.toString().contains("SCAN") && !plan.toString().contains("USING");
                System.out.printf("%-34s %s%s%n", q[0], scan ? "[FULL SCAN] " : "", plan);
                tsv.add(q[0] + "\t" + plan + "\t" + (scan ? "no" : "yes"));
            }
        }
        if (out != null) { Files.write(out, tsv); System.out.println("\nwrote " + out); }
    }
}
