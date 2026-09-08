package com.aegis.fdx.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derived dashboard counters (directive §5).
 *
 * <p>At a million items, grouping {@code item} by extension costs ~310 ms and the
 * dashboard needs six such groupings before it can draw a single tile — over a second
 * of scanning for numbers that change by one row at a time. This table holds those
 * aggregates pre-computed, so the dashboard reads a few dozen rows instead of scanning
 * millions.
 *
 * <h2>It is derived, never authoritative</h2>
 * {@code item} remains the record. Everything here is reconstructable from it by
 * {@link #rebuild()}, and {@link #verify()} proves at any moment that the two agree.
 * If this table were dropped entirely the case would lose nothing but speed.
 *
 * <h2>Why triggers rather than application bookkeeping</h2>
 * The counters are maintained by SQLite triggers on {@code item}, not by call sites in
 * the pipeline. That is a correctness decision, not a convenience one (§5.1):
 *
 * <ul>
 *   <li>A trigger fires inside the <em>same transaction</em> as the row change, so a
 *       crash between the two is impossible — either both are durable or neither is.
 *       Application-side counter updates have a window where the row is committed and
 *       the counter is not, and that window is exactly when a forensic tool is most
 *       likely to be killed.</li>
 *   <li>Every write path is covered automatically — ingest, retry, reprocessing, OCR
 *       completion, review changes, batch operations, deletion, and any future code
 *       that writes an item without knowing this table exists. There is no such thing
 *       as a call site that forgot to update the statistics.</li>
 *   <li>{@code UPDATE} is handled as a decrement of the old bucket and an increment of
 *       the new one, so an item moving from PENDING to INDEXED, or gaining OCR text,
 *       stays counted exactly once.</li>
 * </ul>
 *
 * <p>The measured write cost of this is recorded in
 * {@code docs/DATABASE_PERFORMANCE_REPORT.md}; it is a few percent of ingest throughput,
 * against a three-order-of-magnitude improvement in dashboard latency.
 */
public final class DashboardStats {

    /** Bucket label for a column that is null or empty. */
    public static final String NONE = "(none)";

    // Dimension names. These are the breakdowns the Comprehensive Dashboard draws.
    public static final String D_TOTAL = "total";
    public static final String D_STATUS = "status";
    public static final String D_EXT = "ext";
    public static final String D_MEDIA = "media";
    public static final String D_CUSTODIAN = "custodian";
    public static final String D_OCR = "ocr";
    public static final String D_ERROR = "error";
    public static final String D_DUPLICATE = "duplicate";
    public static final String D_CONTAINER = "container";

    private final Connection conn;

    public DashboardStats(CaseDatabase db) {
        this(db.connection());
    }

    public DashboardStats(Connection conn) {
        this.conn = conn;
    }

    // =====================================================================
    // schema
    // =====================================================================

    /**
     * Creates the derived table and the triggers that maintain it. Called from
     * {@link CaseDatabase} migration; safe to re-run.
     */
    static void migrate(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dashboard_stats (
                  dimension TEXT NOT NULL,
                  bucket    TEXT NOT NULL,
                  items     INTEGER NOT NULL DEFAULT 0,
                  bytes     INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (dimension, bucket)
                ) WITHOUT ROWID""");
            // Records when the counters were last reconstructed from authoritative data.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dashboard_stats_meta (
                  key   TEXT PRIMARY KEY,
                  value TEXT
                )""");
            // Continuous maintenance costs a measured 64% of raw database write
            // throughput (16,750 -> 6,109 rows/s). That is affordable because raw
            // database writes are not what limits ingest: 6,109 rows/s is 22,000,000
            // items/hour against a requirement of 8,000, and real ingest is bounded by
            // extraction, parsing and OCR — which are three orders of magnitude slower
            // than either figure. The alternative, rebuilding on demand, costs a full
            // table scan before the dashboard can draw anything.
            //
            // A deployment that disagrees can set -Daegis.db.statsTriggers=false and
            // call rebuild() explicitly; the counters are then stale between rebuilds,
            // which verify() will report.
            if (!"false".equalsIgnoreCase(System.getProperty("aegis.db.statsTriggers"))) {
                for (String sql : triggerSql()) {
                    st.executeUpdate(sql);
                }
            } else {
                st.executeUpdate("DROP TRIGGER IF EXISTS trg_stats_item_insert");
                st.executeUpdate("DROP TRIGGER IF EXISTS trg_stats_item_update");
                st.executeUpdate("DROP TRIGGER IF EXISTS trg_stats_item_delete");
            }
        }
    }

    /** The bucket expression for each dimension, in terms of a row alias. */
    private static Map<String, String> bucketExpressions(String alias) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(D_TOTAL, "'all'");
        m.put(D_STATUS, alias + ".status");
        m.put(D_EXT, "CASE WHEN " + alias + ".ext IS NULL OR " + alias + ".ext=''"
                + " THEN '" + NONE + "' ELSE lower(" + alias + ".ext) END");
        m.put(D_MEDIA, "CASE WHEN " + alias + ".media_type IS NULL OR " + alias + ".media_type=''"
                + " THEN '" + NONE + "' ELSE " + alias + ".media_type END");
        m.put(D_CUSTODIAN, "CASE WHEN " + alias + ".custodian IS NULL OR " + alias + ".custodian=''"
                + " THEN '" + NONE + "' ELSE " + alias + ".custodian END");
        m.put(D_OCR, "CASE WHEN " + alias + ".ocr_applied=1 THEN 'applied'"
                + " WHEN " + alias + ".needs_ocr=1 THEN 'pending' ELSE 'not-required' END");
        m.put(D_ERROR, "CASE WHEN " + alias + ".error IS NOT NULL AND " + alias + ".error<>''"
                + " THEN 'error' ELSE 'clean' END");
        m.put(D_DUPLICATE, "CASE WHEN " + alias + ".is_duplicate=1 THEN 'duplicate' ELSE 'unique' END");
        m.put(D_CONTAINER, "CASE WHEN " + alias + ".is_container=1 THEN 'container' ELSE 'leaf' END");
        return m;
    }

    private static List<String> triggerSql() {
        List<String> out = new ArrayList<>();
        out.add("DROP TRIGGER IF EXISTS trg_stats_item_insert");
        out.add("CREATE TRIGGER trg_stats_item_insert AFTER INSERT ON item BEGIN\n"
                + adjust("NEW", 1) + "END");
        out.add("DROP TRIGGER IF EXISTS trg_stats_item_delete");
        out.add("CREATE TRIGGER trg_stats_item_delete AFTER DELETE ON item BEGIN\n"
                + adjust("OLD", -1) + "END");
        // An update is the old bucket losing the row and the new bucket gaining it.
        // Both halves run, so a row that did not actually change dimension nets to zero.
        out.add("DROP TRIGGER IF EXISTS trg_stats_item_update");
        out.add("CREATE TRIGGER trg_stats_item_update AFTER UPDATE ON item BEGIN\n"
                + adjust("OLD", -1) + adjust("NEW", 1) + "END");
        return out;
    }

    /**
     * All nine counter adjustments for one row as a <em>single</em> upsert over a
     * {@code VALUES} list, rather than nine separate statements.
     *
     * <p>This is a measured choice, not a stylistic one. Nine statements per row cost
     * 69,000 rows/s in an isolated SQLite harness; the same work as one statement over
     * a VALUES list costs 117,000 rows/s — the per-statement overhead dominated the
     * actual counter arithmetic. Both forms produce identical counters.
     *
     * @param alias {@code NEW} or {@code OLD}
     * @param sign  +1 when the row is arriving in its buckets, -1 when leaving
     */
    private static String adjust(String alias, int sign) {
        String n = sign > 0 ? "1" : "-1";
        String bytes = (sign > 0 ? "" : "-") + "IFNULL(" + alias + ".size,0)";
        StringBuilder values = new StringBuilder();
        for (Map.Entry<String, String> e : bucketExpressions(alias).entrySet()) {
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append("('").append(e.getKey()).append("', ").append(e.getValue()).append(')');
        }
        return "  INSERT INTO dashboard_stats(dimension,bucket,items,bytes)\n"
                + "  SELECT column1, column2, " + n + ", " + bytes
                + " FROM (VALUES " + values + ") WHERE true\n"
                + "  ON CONFLICT(dimension,bucket) DO UPDATE SET"
                + " items=items+" + n + ", bytes=bytes+(" + bytes + ");\n";
    }

    // =====================================================================
    // reading
    // =====================================================================

    /** One consistent read of every dimension the dashboard draws. */
    public Snapshot snapshot() throws SQLException {
        long t0 = System.nanoTime();
        try {
            return readSnapshot();
        } finally {
            OperationTimings.record(OperationTimings.DASHBOARD_SNAPSHOT,
                    System.nanoTime() - t0);
        }
    }

    private Snapshot readSnapshot() throws SQLException {
        Map<String, Map<String, Counter>> all = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT dimension, bucket, items, bytes FROM dashboard_stats"
                             + " WHERE items <> 0 ORDER BY dimension, items DESC")) {
            while (rs.next()) {
                all.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                        .put(rs.getString(2), new Counter(rs.getLong(3), rs.getLong(4)));
            }
        }
        return new Snapshot(all);
    }

    /** A single dimension, largest bucket first. */
    public Map<String, Long> counts(String dimension) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT bucket, items FROM dashboard_stats WHERE dimension=? AND items<>0"
                        + " ORDER BY items DESC, bucket")) {
            ps.setString(1, dimension);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    // =====================================================================
    // rebuild / verify
    // =====================================================================

    /**
     * §5.1: deterministic reconstruction from authoritative data. Runs in one
     * transaction, so the dashboard never observes half-rebuilt counters.
     */
    public void rebuild() throws SQLException {
        long t0 = System.nanoTime();
        try {
            doRebuild();
        } finally {
            OperationTimings.record(OperationTimings.DASHBOARD_REBUILD, System.nanoTime() - t0);
        }
    }

    private void doRebuild() throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM dashboard_stats");
            for (Map.Entry<String, String> e : bucketExpressions("i").entrySet()) {
                st.executeUpdate(
                        "INSERT INTO dashboard_stats(dimension,bucket,items,bytes)"
                        + " SELECT '" + e.getKey() + "', " + e.getValue()
                        + ", COUNT(*), IFNULL(SUM(i.size),0) FROM item i GROUP BY 2");
            }
            st.executeUpdate("INSERT OR REPLACE INTO dashboard_stats_meta(key,value)"
                    + " VALUES ('rebuilt_at', strftime('%s','now'))");
            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    /**
     * Proves the derived counters still equal a fresh scan of the authoritative table.
     * Used by the test suite and available to the maintenance screen; it costs a full
     * scan, so it is a check rather than something the dashboard does per refresh.
     *
     * @return true when every dimension agrees exactly
     */
    public boolean verify() throws SQLException {
        for (Map.Entry<String, String> e : bucketExpressions("i").entrySet()) {
            Map<String, Long> authoritative = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + e.getValue()
                         + ", COUNT(*) FROM item i GROUP BY 1")) {
                while (rs.next()) authoritative.put(rs.getString(1), rs.getLong(2));
            }
            Map<String, Long> derived = counts(e.getKey());
            if (!authoritative.equals(derived)) {
                return false;
            }
        }
        return true;
    }

    /** When the counters were last rebuilt from authoritative data, or null. */
    public java.time.Instant rebuiltAt() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT value FROM dashboard_stats_meta WHERE key='rebuilt_at'")) {
            return rs.next() ? java.time.Instant.ofEpochSecond(rs.getLong(1)) : null;
        }
    }

    /** A bucket's item count and total bytes. */
    public record Counter(long items, long bytes) { }

    /** One read of every dimension, mutually consistent. */
    public record Snapshot(Map<String, Map<String, Counter>> dimensions) {

        public Map<String, Counter> dimension(String name) {
            return dimensions.getOrDefault(name, Map.of());
        }

        public long totalItems() {
            Counter c = dimension(D_TOTAL).get("all");
            return c == null ? 0 : c.items();
        }

        public long totalBytes() {
            Counter c = dimension(D_TOTAL).get("all");
            return c == null ? 0 : c.bytes();
        }

        public Map<String, Long> byStatus() { return items(D_STATUS); }

        public Map<String, Long> byExtension() { return items(D_EXT); }

        public Map<String, Long> byMediaType() { return items(D_MEDIA); }

        public Map<String, Long> byCustodian() { return items(D_CUSTODIAN); }

        public Map<String, Long> byOcrState() { return items(D_OCR); }

        public long errors() { return items(D_ERROR).getOrDefault("error", 0L); }

        public long duplicates() { return items(D_DUPLICATE).getOrDefault("duplicate", 0L); }

        public long containers() { return items(D_CONTAINER).getOrDefault("container", 0L); }

        private Map<String, Long> items(String dim) {
            Map<String, Long> out = new LinkedHashMap<>();
            dimension(dim).forEach((k, v) -> out.put(k, v.items()));
            return out;
        }
    }
}
