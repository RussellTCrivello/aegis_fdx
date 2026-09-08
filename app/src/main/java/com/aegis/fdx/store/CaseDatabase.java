package com.aegis.fdx.store;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-07 / F-24 / A-04: the durable side of a case — SQLite in WAL mode.
 *
 * <p>Holds the ingest queue (so a crash resumes exactly where it stopped), the
 * element metadata of record, tags, notes, duplicate links and the audit log.
 * Nothing important lives only in memory.
 */
public final class CaseDatabase implements AutoCloseable {

    private final Connection conn;

    public CaseDatabase(java.nio.file.Path dbFile) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");       // concurrent read during ingest
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=" + intProperty("aegis.db.busyTimeoutMs", 5000));

            // §10. Measured on the reference profile (8 cores / 16 GB / NVMe); every
            // value is overridable so a smaller machine can be told to use less. The
            // defaults are deliberately modest rather than maximal — the numbers behind
            // them are in docs/DATABASE_PERFORMANCE_REPORT.md.
            //
            // cache_size is negative to mean KiB rather than pages, so it does not
            // silently scale with page size. 64 MB holds the hot b-tree interior pages
            // of a multi-million-row item table; beyond that the measured gain was
            // inside the noise while the resident set kept growing.
            st.execute("PRAGMA cache_size=-" + intProperty("aegis.db.cacheKb", 65536));
            // Temp tables and the sorters behind GROUP BY / ORDER BY stay in memory
            // instead of spilling to disk, which is what the dashboard aggregations do.
            st.execute("PRAGMA temp_store=MEMORY");
            // Memory-mapped reads avoid a copy per page on the read-heavy paths
            // (dashboard, relationship lookups). Zero disables it for platforms or
            // volumes where mmap is a liability.
            long mmap = longProperty("aegis.db.mmapBytes", 256L * 1024 * 1024);
            if (mmap > 0) {
                st.execute("PRAGMA mmap_size=" + mmap);
            }
            // A larger WAL between checkpoints suits batched ingest; the checkpoint
            // itself is what stalls writers, so it should happen less often, not never.
            st.execute("PRAGMA wal_autocheckpoint=" + intProperty("aegis.db.walPages", 4000));
        }
        migrate();
    }

    private static int intProperty(String key, int fallback) {
        try {
            String v = System.getProperty(key);
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longProperty(String key, long fallback) {
        try {
            String v = System.getProperty(key);
            return v == null ? fallback : Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** The PRAGMA values actually in force, read back from the connection (§6). */
    public Map<String, String> runtimePragmas() throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : new String[]{"journal_mode", "synchronous", "foreign_keys",
                "busy_timeout", "cache_size", "mmap_size", "temp_store", "page_size",
                "wal_autocheckpoint"}) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA " + p)) {
                out.put(p, rs.next() ? rs.getString(1) : "(unavailable)");
            }
        }
        return out;
    }

    private void migrate() throws SQLException {
        migrateForensic();
        CorpusSchema.migrate(conn);
        DashboardStats.migrate(conn);
    }

    /** The original forensic schema: items, tags, metadata, queue, audit, settings. */
    private void migrateForensic() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS item (
                  id             TEXT PRIMARY KEY,
                  name           TEXT NOT NULL,
                  ext            TEXT,
                  media_type     TEXT,
                  size           INTEGER,
                  created        INTEGER,
                  modified       INTEGER,
                  accessed       INTEGER,
                  md5            TEXT,
                  sha256         TEXT,
                  source_path    TEXT,
                  container_path TEXT,
                  custodian      TEXT,
                  geo            TEXT,
                  depth          INTEGER DEFAULT 0,
                  parent_id      TEXT,
                  attached_from  TEXT,
                  is_container   INTEGER DEFAULT 0,
                  status         TEXT NOT NULL,
                  email_from     TEXT,
                  email_to       TEXT,
                  email_cc       TEXT,
                  subject        TEXT,
                  sent_date      INTEGER,
                  message_id     TEXT,
                  attach_count   INTEGER DEFAULT 0,
                  needs_ocr      INTEGER DEFAULT 0,
                  ocr_applied    INTEGER DEFAULT 0,
                  is_duplicate   INTEGER DEFAULT 0,
                  duplicate_of   TEXT,
                  notes          TEXT DEFAULT '',
                  error          TEXT,
                  indexed_at     INTEGER
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_item_status ON item(status)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_item_sha ON item(sha256)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_item_parent ON item(parent_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_item_cust ON item(custodian)");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS item_tag (
                  item_id TEXT NOT NULL,
                  tag     TEXT NOT NULL,
                  PRIMARY KEY (item_id, tag),
                  FOREIGN KEY (item_id) REFERENCES item(id) ON DELETE CASCADE
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS item_meta (
                  item_id TEXT NOT NULL,
                  key     TEXT NOT NULL,
                  value   TEXT,
                  PRIMARY KEY (item_id, key),
                  FOREIGN KEY (item_id) REFERENCES item(id) ON DELETE CASCADE
                )""");

            // F-07: the work queue. Survives restart; drives resume.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS queue (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  item_id     TEXT NOT NULL UNIQUE,
                  source      TEXT NOT NULL,
                  parent_id   TEXT,
                  depth       INTEGER DEFAULT 0,
                  state       TEXT NOT NULL,
                  attempts    INTEGER DEFAULT 0,
                  enqueued_at INTEGER,
                  updated_at  INTEGER
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_queue_state ON queue(state)");
            // §8. The resume lookup (completedIdForSource) runs once per file during
            // intake and had no index to use: EXPLAIN QUERY PLAN reported
            // "SCAN queue" for it, so a run over N files scanned the queue N times —
            // quadratic in the size of the case, and worst exactly when the case is
            // largest. Indexing source makes it a lookup. The state column is included
            // so the index covers the whole predicate and the row itself is never
            // touched.
            st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS ix_queue_source ON queue(source, state)");

            // F-24: append-only audit.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS audit (
                  id       INTEGER PRIMARY KEY AUTOINCREMENT,
                  ts       INTEGER NOT NULL,
                  user     TEXT,
                  action   TEXT NOT NULL,
                  detail   TEXT,
                  item_ids TEXT
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS setting (
                  key   TEXT PRIMARY KEY,
                  value TEXT
                )""");
        }
    }

    // ---- items ---------------------------------------------------------------

    private static final String UPSERT = """
        INSERT INTO item (id,name,ext,media_type,size,created,modified,accessed,md5,sha256,
                          source_path,container_path,custodian,geo,depth,parent_id,attached_from,
                          is_container,status,email_from,email_to,email_cc,subject,sent_date,
                          message_id,attach_count,needs_ocr,ocr_applied,is_duplicate,duplicate_of,
                          notes,error,indexed_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET
          name=excluded.name, ext=excluded.ext, media_type=excluded.media_type,
          size=excluded.size, created=excluded.created, modified=excluded.modified,
          accessed=excluded.accessed, md5=excluded.md5, sha256=excluded.sha256,
          source_path=excluded.source_path, container_path=excluded.container_path,
          custodian=excluded.custodian, geo=excluded.geo, depth=excluded.depth,
          parent_id=excluded.parent_id, attached_from=excluded.attached_from,
          is_container=excluded.is_container, status=excluded.status,
          email_from=excluded.email_from, email_to=excluded.email_to, email_cc=excluded.email_cc,
          subject=excluded.subject, sent_date=excluded.sent_date, message_id=excluded.message_id,
          attach_count=excluded.attach_count, needs_ocr=excluded.needs_ocr,
          ocr_applied=excluded.ocr_applied, is_duplicate=excluded.is_duplicate,
          duplicate_of=excluded.duplicate_of, notes=excluded.notes, error=excluded.error,
          indexed_at=excluded.indexed_at
        """;

    public void save(Item it) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            int i = 1;
            ps.setString(i++, it.id());
            ps.setString(i++, it.name());
            ps.setString(i++, it.extension());
            ps.setString(i++, it.mediaType());
            ps.setLong(i++, it.size());
            setInstant(ps, i++, it.created());
            setInstant(ps, i++, it.modified());
            setInstant(ps, i++, it.accessed());
            ps.setString(i++, it.md5());
            ps.setString(i++, it.sha256());
            ps.setString(i++, it.sourcePath());
            ps.setString(i++, it.containerPath());
            ps.setString(i++, it.custodian());
            ps.setString(i++, it.geoLocation());
            ps.setInt(i++, it.depth());
            ps.setString(i++, it.parentId());
            ps.setString(i++, it.attachedFrom());
            ps.setInt(i++, it.container() ? 1 : 0);
            ps.setString(i++, it.status().name());
            ps.setString(i++, it.from());
            ps.setString(i++, it.to());
            ps.setString(i++, it.cc());
            ps.setString(i++, it.subject());
            setInstant(ps, i++, it.sentDate());
            ps.setString(i++, it.messageId());
            ps.setInt(i++, it.attachmentCount());
            ps.setInt(i++, it.needsOcr() ? 1 : 0);
            ps.setInt(i++, it.ocrApplied() ? 1 : 0);
            ps.setInt(i++, it.duplicate() ? 1 : 0);
            ps.setString(i++, it.duplicateOf());
            ps.setString(i++, it.notes());
            ps.setString(i++, it.errors().isEmpty() ? null : String.join(" | ", it.errors()));
            ps.setLong(i, Instant.now().toEpochMilli());
            ps.executeUpdate();
        }
        saveTags(it);
        saveMeta(it);
    }

    private void saveTags(Item it) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM item_tag WHERE item_id=?")) {
            del.setString(1, it.id());
            del.executeUpdate();
        }
        if (it.tags().isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO item_tag(item_id,tag) VALUES (?,?)")) {
            for (String t : it.tags()) {
                ps.setString(1, it.id());
                ps.setString(2, t);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void saveMeta(Item it) throws SQLException {
        if (it.metadata().isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO item_meta(item_id,key,value) VALUES (?,?,?)")) {
            for (Map.Entry<String, String> e : it.metadata().entrySet()) {
                ps.setString(1, it.id());
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public int count() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM item")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public Map<ItemStatus, Integer> statusCounts() throws SQLException {
        Map<ItemStatus, Integer> out = new LinkedHashMap<>();
        for (ItemStatus s : ItemStatus.values()) out.put(s, 0);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT status, COUNT(*) FROM item GROUP BY status")) {
            while (rs.next()) {
                try { out.put(ItemStatus.valueOf(rs.getString(1)), rs.getInt(2)); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return out;
    }

    /** F-05: SHA-256 clusters with more than one member. */
    public Map<String, List<String>> duplicateClusters() throws SQLException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT sha256, id FROM item
                 WHERE sha256 IS NOT NULL AND sha256 <> ''
                   AND sha256 IN (SELECT sha256 FROM item
                                  WHERE sha256 IS NOT NULL AND sha256 <> ''
                                  GROUP BY sha256 HAVING COUNT(*) > 1)
                 ORDER BY sha256""")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        return out;
    }

    /**
     * F-05 dedupe scope. Returns the id of an earlier element with the same
     * SHA-256, or null. Scope GLOBAL matches anywhere; PER_CUSTODIAN only within
     * the same custodian; OFF never matches (duplicates are only highlighted).
     */
    public String findDuplicate(String sha256, String custodian, String scope) throws SQLException {
        if (sha256 == null || "OFF".equals(scope)) return null;
        String sql = "PER_CUSTODIAN".equals(scope)
                ? "SELECT id FROM item WHERE sha256=? AND custodian IS ? LIMIT 1"
                : "SELECT id FROM item WHERE sha256=? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sha256);
            if ("PER_CUSTODIAN".equals(scope)) ps.setString(2, custodian);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setNotes(String itemId, String notes) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE item SET notes=? WHERE id=?")) {
            ps.setString(1, notes);
            ps.setString(2, itemId);
            ps.executeUpdate();
        }
    }

    public void setTags(String itemId, Iterable<String> tags) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM item_tag WHERE item_id=?")) {
            del.setString(1, itemId);
            del.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO item_tag(item_id,tag) VALUES (?,?)")) {
            for (String t : tags) {
                ps.setString(1, itemId);
                ps.setString(2, t);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---- queue (F-07) ---------------------------------------------------------

    public void enqueue(String itemId, String source, String parentId, int depth) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO queue(item_id,source,parent_id,depth,state,enqueued_at,updated_at)
                VALUES (?,?,?,?,'PENDING',?,?)""")) {
            long now = Instant.now().toEpochMilli();
            ps.setString(1, itemId);
            ps.setString(2, source);
            ps.setString(3, parentId);
            ps.setInt(4, depth);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    public void setQueueState(String itemId, String state) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE queue SET state=?, updated_at=?, attempts=attempts+1 WHERE item_id=?")) {
            ps.setString(1, state);
            ps.setLong(2, Instant.now().toEpochMilli());
            ps.setString(3, itemId);
            ps.executeUpdate();
        }
    }

    /** Anything left PENDING or PROCESSING from a previous run (AT-05). */
    public List<String> pendingSources() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT source FROM queue WHERE state IN ('PENDING','PROCESSING') ORDER BY id")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /**
     * M3-D. Resume lookup keyed on the <b>source path</b>, which is stable across
     * restarts. Element ids come from an in-memory counter and are re-issued on a
     * fresh run, so keying resume on the id made every file look new and produced
     * duplicate index entries after a crash.
     *
     * @return the element id already completed for this source, or null
     */
    public String completedIdForSource(String source) throws SQLException {
        if (source == null) return null;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT item_id FROM queue
                 WHERE source = ? AND state IN ('DONE','ERROR','LOCKED','UNSUPPORTED')
                 LIMIT 1""")) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Highest numeric suffix issued so far, so a resumed run never reuses an id. */
    public long maxElementSequence() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT item_id FROM queue WHERE item_id LIKE 'E-%'")) {
            long max = 0;
            while (rs.next()) {
                String id = rs.getString(1);
                int dash = id.indexOf('-');
                int end = dash + 1;
                while (end < id.length() && Character.isDigit(id.charAt(end))) end++;
                try {
                    max = Math.max(max, Long.parseLong(id.substring(dash + 1, end)));
                } catch (Exception ignored) { }
            }
            return max;
        }
    }

    public boolean isDone(String itemId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state FROM queue WHERE item_id=?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String s = rs.getString(1);
                return "DONE".equals(s) || "ERROR".equals(s) || "LOCKED".equals(s)
                        || "UNSUPPORTED".equals(s);
            }
        }
    }

    public int queueCount(String state) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM queue WHERE state=?")) {
            ps.setString(1, state);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ---- audit (F-24) ---------------------------------------------------------

    public void audit(String user, String action, String detail, String itemIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit(ts,user,action,detail,item_ids) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, Instant.now().toEpochMilli());
            ps.setString(2, user);
            ps.setString(3, action);
            ps.setString(4, detail);
            ps.setString(5, itemIds);
            ps.executeUpdate();
        }
    }

    public List<AuditRow> auditLog(int limit) throws SQLException {
        List<AuditRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ts,user,action,detail,item_ids FROM audit ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AuditRow(Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
        }
        return out;
    }

    public record AuditRow(Instant when, String user, String action, String detail, String itemIds) { }

    // ---- transactions ----------------------------------------------------------

    /**
     * §7. The measured-optimal number of item writes between commits.
     *
     * <p>Per-item autocommit measured 6,451 rows/s; every batch size from 100 upward
     * measured between 15,000 and 17,600 rows/s, so the curve is flat once the fsync
     * per row is gone. 5,000 sits on that plateau while bounding how much work an
     * interrupted batch can cost: at the measured rate a lost batch is under a third of
     * a second of re-processing, and the queue table replays it (§28). Larger batches
     * bought no further throughput and only widened that window.
     */
    public static final int INGEST_BATCH_SIZE =
            intProperty("aegis.db.ingestBatch", 5_000);

    /**
     * Runs {@code work} inside one transaction, committing on success and rolling back
     * on any failure. Nested calls join the outer transaction rather than committing
     * early, so a caller cannot accidentally split someone else's atomic unit.
     */
    public <T> T inTransaction(SqlCallable<T> work) throws SQLException {
        if (!conn.getAutoCommit()) {
            return work.call();          // already inside one; join it
        }
        conn.setAutoCommit(false);
        try {
            T result = work.call();
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Work that may fail with a {@link SQLException}. */
    @FunctionalInterface
    public interface SqlCallable<T> {
        T call() throws SQLException;
    }

    /** True when a transaction opened by {@link #begin()} is currently in force. */
    public boolean inTransaction() throws SQLException {
        return !conn.getAutoCommit();
    }

    public void begin() throws SQLException { conn.setAutoCommit(false); }

    public void commit() throws SQLException {
        conn.commit();
        conn.setAutoCommit(true);
    }

    public void rollback() {
        try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) { }
    }

    private static void setInstant(PreparedStatement ps, int idx, Instant v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER);
        else ps.setLong(idx, v.toEpochMilli());
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(v);
    }

    /**
     * Every element in the case, read back from the database.
     *
     * <p>This is what makes the database the record of authority rather than merely one
     * of two copies: the search index can be thrown away and rebuilt from here. Extracted
     * text is not a column — it lives in the case's text store — so the caller supplies a
     * reader for it, and an element whose text file is missing still comes back, with the
     * text it no longer has recorded as empty.
     *
     * @param textFor supplies the extracted text for an element id, or null when there is
     *                none to be had
     */
    public List<Item> allItems(java.util.function.Function<String, String> textFor)
            throws SQLException {
        List<Item> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM item ORDER BY id")) {
            while (rs.next()) {
                Item it = new Item(rs.getString("id"), rs.getString("name"));
                it.extension(rs.getString("ext"));
                it.mediaType(rs.getString("media_type"));
                it.size(rs.getLong("size"));
                it.created(instantOf(rs, "created"));
                it.modified(instantOf(rs, "modified"));
                it.accessed(instantOf(rs, "accessed"));
                it.md5(rs.getString("md5"));
                it.sha256(rs.getString("sha256"));
                it.sourcePath(rs.getString("source_path"));
                it.containerPath(rs.getString("container_path"));
                it.custodian(rs.getString("custodian"));
                it.geoLocation(rs.getString("geo"));
                it.depth(rs.getInt("depth"));
                it.parentId(rs.getString("parent_id"));
                it.attachedFrom(rs.getString("attached_from"));
                it.container(rs.getInt("is_container") == 1);
                it.status(statusOf(rs.getString("status")));
                it.from(rs.getString("email_from"));
                it.to(rs.getString("email_to"));
                it.cc(rs.getString("email_cc"));
                it.subject(rs.getString("subject"));
                it.sentDate(instantOf(rs, "sent_date"));
                it.messageId(rs.getString("message_id"));
                it.attachmentCount(rs.getInt("attach_count"));
                it.needsOcr(rs.getInt("needs_ocr") == 1);
                it.ocrApplied(rs.getInt("ocr_applied") == 1);
                it.duplicate(rs.getInt("is_duplicate") == 1);
                it.duplicateOf(rs.getString("duplicate_of"));
                it.notes(rs.getString("notes"));
                String errors = rs.getString("error");
                if (errors != null && !errors.isBlank()) {
                    for (String e : errors.split(" \\| ")) {
                        it.errors().add(e);
                    }
                }
                if (textFor != null) {
                    it.extractedText(textFor.apply(it.id()));
                }
                out.add(it);
            }
        }
        loadTags(out);
        loadMeta(out);
        return out;
    }

    private static ItemStatus statusOf(String name) {
        try {
            return ItemStatus.valueOf(name);
        } catch (Exception e) {
            return ItemStatus.ERROR;
        }
    }

    private void loadTags(List<Item> items) throws SQLException {
        if (items.isEmpty()) {
            return;
        }
        Map<String, Item> byId = new java.util.HashMap<>();
        for (Item it : items) {
            byId.put(it.id(), it);
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT item_id, tag FROM item_tag")) {
            while (rs.next()) {
                Item it = byId.get(rs.getString(1));
                if (it != null) {
                    it.tags().add(rs.getString(2));
                }
            }
        }
    }

    private void loadMeta(List<Item> items) throws SQLException {
        if (items.isEmpty()) {
            return;
        }
        Map<String, Item> byId = new java.util.HashMap<>();
        for (Item it : items) {
            byId.put(it.id(), it);
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT item_id, key, value FROM item_meta")) {
            while (rs.next()) {
                Item it = byId.get(rs.getString(1));
                if (it != null) {
                    it.metadata().put(rs.getString(2), rs.getString(3));
                }
            }
        }
    }

    /**
     * The case's single JDBC connection.
     *
     * <p>Exposed so the corpus DAO ({@link CorpusDatabase}) operates on the <em>same</em>
     * connection and therefore the same file, transaction scope and WAL journal as the
     * forensic tables. That is what lets a query join {@code paths} against {@code item}
     * and lets one transaction span both halves of the schema.
     */
    public Connection connection() {
        return conn;
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }
}
