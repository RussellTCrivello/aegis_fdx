package com.aegis.fdx.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for the five integrated concepts — Sources, Aspects, Categories,
 * Keywords and Contents — plus their supporting tables.
 *
 * <p>This is <strong>not</strong> a second database. It runs on the case's existing
 * JDBC connection ({@link CaseDatabase#connection()}), so these tables sit in the same
 * {@code case.db} file as the forensic schema, share its WAL journal and transaction
 * scope, and can be joined directly against {@code item}. The schema itself is created
 * by {@link CorpusSchema} during {@code CaseDatabase} migration.
 *
 * <p>Lifecycle: this object does not own the connection and therefore does not close
 * it. {@link CaseDatabase#close()} remains the single owner.
 */
public final class CorpusDatabase {

    private final Connection conn;

    /** Binds to a case's existing connection. */
    public CorpusDatabase(CaseDatabase caseDb) {
        this.conn = caseDb.connection();
    }

    /** Binds to a raw connection; used by tests that manage their own. */
    public CorpusDatabase(Connection conn) {
        this.conn = conn;
    }

    // ==================== sources ====================

    /** @return generated id, mirroring Python {@code create_source -> int}. */
    public int insertSource(String name, String country, String job, double importance,
                            String city, String description, String accounts, String note,
                            String attachments, String ownership, String accessStatus,
                            LocalDate entryDate, Integer categoryId) throws SQLException {
        String sql = """
            INSERT INTO source (name, job, importance, country, city, description,
                                 accounts, note, attachments, ownership, access_status,
                                 entry_date, category_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, job);
            ps.setDouble(3, importance);
            ps.setString(4, country);
            ps.setString(5, city);
            ps.setString(6, description);
            ps.setString(7, accounts);
            ps.setString(8, note);
            ps.setString(9, attachments);
            ps.setString(10, ownership);
            ps.setString(11, accessStatus);
            ps.setString(12, entryDate == null ? null : entryDate.toString());
            if (categoryId == null) {
                ps.setNull(13, Types.INTEGER);
            } else {
                ps.setInt(13, categoryId);
            }
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    /** Python {@code get_all_sources -> List[Tuple[int, str]]} (id, name) pairs. */
    public Map<Integer, String> selectAllSources() throws SQLException {
        Map<Integer, String> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM source ORDER BY id")) {
            while (rs.next()) {
                out.put(rs.getInt(1), rs.getString(2));
            }
        }
        return out;
    }

    public Row selectSourceById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM source WHERE id=?")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public boolean deleteSource(int id) throws SQLException {
        return executeUpdate("DELETE FROM source WHERE id=?", id) > 0;
    }

    public boolean sourceNameExists(String name) throws SQLException {
        return exists("SELECT 1 FROM source WHERE name=?", name);
    }

    /** Updates every mutable field of a source. */
    public boolean updateSource(int id, String name, String country, String job,
                                double importance, String city, String description,
                                String accounts, String note, String attachments,
                                String ownership, String accessStatus,
                                LocalDate entryDate, Integer categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE source SET name=?, country=?, job=?, importance=?, city=?,
                       description=?, accounts=?, note=?, attachments=?, ownership=?,
                       access_status=?, entry_date=?, category_id=?
                WHERE id=?""")) {
            ps.setString(1, name);
            ps.setString(2, country);
            ps.setString(3, job);
            ps.setDouble(4, importance);
            ps.setString(5, city);
            ps.setString(6, description);
            ps.setString(7, accounts);
            ps.setString(8, note);
            ps.setString(9, attachments);
            ps.setString(10, ownership);
            ps.setString(11, accessStatus);
            ps.setString(12, entryDate == null ? null : entryDate.toString());
            if (categoryId == null) { ps.setNull(13, Types.INTEGER); } else { ps.setInt(13, categoryId); }
            ps.setInt(14, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** True when another row already uses this source name. */
    public boolean sourceNameTaken(String name, int excludingId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM source WHERE name=? AND id<>?")) {
            ps.setString(1, name);
            ps.setInt(2, excludingId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** Updates every mutable field of an aspect. */
    public boolean updateAspect(int id, String name, double importance,
                                LocalDate dateCreation) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE aspect SET name=?, importance=?, date_creation=? WHERE id=?")) {
            ps.setString(1, name);
            ps.setDouble(2, importance);
            ps.setString(3, dateCreation == null ? null : dateCreation.toString());
            ps.setInt(4, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** True when another row already uses this aspect name. */
    public boolean aspectNameTaken(String name, int excludingId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM aspect WHERE name=? AND id<>?")) {
            ps.setString(1, name);
            ps.setInt(2, excludingId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    // ==================== sides ====================

    public int insertAspect(String name, double importance, LocalDate dateCreation) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO aspect (name, importance, date_creation) VALUES (?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setDouble(2, importance);
            ps.setString(3, dateCreation.toString());
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public Map<Integer, String> selectAllAspects() throws SQLException {
        Map<Integer, String> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM aspect ORDER BY id")) {
            while (rs.next()) {
                out.put(rs.getInt(1), rs.getString(2));
            }
        }
        return out;
    }

    public Row selectAspectById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM aspect WHERE id=?")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public boolean deleteAspect(int id) throws SQLException {
        return executeUpdate("DELETE FROM aspect WHERE id=?", id) > 0;
    }

    public boolean aspectNameExists(String name) throws SQLException {
        return exists("SELECT 1 FROM aspect WHERE name=?", name);
    }

    // ==================== term invariants (enforced below the facade) ====================

    /** Words in a term, on whitespace, ignoring empties. */
    static int termWordCount(String term) {
        if (term == null) {
            return 0;
        }
        int n = 0;
        for (String t : term.trim().split("\\s+")) {
            if (!t.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** A {@code word} row is exactly one word. Categories and category words live here. */
    private static String requireOneWord(String word, String what) throws SQLException {
        int n = termWordCount(word);
        if (n != 1) {
            throw new SQLException(what + " must be exactly one word; \"" + word + "\" has " + n);
        }
        return word.trim();
    }

    /** A keyword phrase has at least three words. */
    private static String requireKeywordPhrase(String phrase) throws SQLException {
        int n = termWordCount(phrase);
        if (n < 3) {
            throw new SQLException("keyword must have at least three words; \"" + phrase
                    + "\" has " + n);
        }
        return phrase.trim().replaceAll("\\s+", " ");
    }

    // ==================== words ====================

    /** Idempotent: Python relies on the UNIQUE constraint and returns the existing id. */
    public int insertWord(String word) throws SQLException {
        word = requireOneWord(word, "word");
        Integer existing = findWordId(word);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO word (word) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, word);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public Integer findWordId(String word) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM word WHERE word=?")) {
            ps.setString(1, word);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    public Row selectWordById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM word WHERE id=?")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public List<Row> searchWords(String term, int limit, int offset) throws SQLException {
        String sql = (term == null || term.isBlank())
                ? "SELECT * FROM word ORDER BY id LIMIT ? OFFSET ?"
                : "SELECT * FROM word WHERE word LIKE ? ORDER BY id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (term != null && !term.isBlank()) {
                ps.setString(i++, "%" + term + "%");
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            return all(ps);
        }
    }

    public int countWords(String term) throws SQLException {
        String sql = (term == null || term.isBlank())
                ? "SELECT COUNT(*) FROM word"
                : "SELECT COUNT(*) FROM word WHERE word LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (term != null && !term.isBlank()) {
                ps.setString(1, "%" + term + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean updateWord(int id, String word) throws SQLException {
        word = requireOneWord(word, "word");
        try (PreparedStatement ps = conn.prepareStatement("UPDATE word SET word=? WHERE id=?")) {
            ps.setString(1, word);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteWord(int id) throws SQLException {
        return executeUpdate("DELETE FROM word WHERE id=?", id) > 0;
    }

    // ==================== categories ====================

    public int insertCategory(int wordId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM category WHERE word_id=?")) {
            ps.setInt(1, wordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO category (word_id) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, wordId);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    /** Joins through {@code words} so the caller gets the naming word too. */
    public List<Row> selectAllCategories(int limit, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, c.word_id AS word_id, w.word AS word
                FROM category c JOIN word w ON w.id = c.word_id
                ORDER BY c.id LIMIT ? OFFSET ?""")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            return all(ps);
        }
    }

    public Row selectCategoryById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, c.word_id AS word_id, w.word AS word
                FROM category c JOIN word w ON w.id = c.word_id
                WHERE c.id = ?""")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public Row selectCategoryByWord(String word) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, c.word_id AS word_id, w.word AS word
                FROM category c JOIN word w ON w.id = c.word_id
                WHERE w.word = ?""")) {
            ps.setString(1, word);
            return single(ps);
        }
    }

    public int countCategories() throws SQLException {
        return scalar("SELECT COUNT(*) FROM category");
    }

    public boolean deleteCategory(int id) throws SQLException {
        return executeUpdate("DELETE FROM category WHERE id=?", id) > 0;
    }

    public boolean linkWordToCategory(int wordId, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO word_category (word_id, category_id) VALUES (?,?)")) {
            ps.setInt(1, wordId);
            ps.setInt(2, categoryId);
            return ps.executeUpdate() > 0;
        }
    }

    public List<Row> selectCategoryWords(int categoryId, int limit, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT w.id AS id, w.word AS word
                FROM word_category wc JOIN word w ON w.id = wc.word_id
                WHERE wc.category_id = ? ORDER BY w.id LIMIT ? OFFSET ?""")) {
            ps.setInt(1, categoryId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return all(ps);
        }
    }

    public boolean unlinkWordFromCategory(int categoryId, int wordId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM word_category WHERE category_id=? AND word_id=?")) {
            ps.setInt(1, categoryId);
            ps.setInt(2, wordId);
            boolean removed = ps.executeUpdate() > 0;
            if (removed) {
                try (PreparedStatement checkPs = conn.prepareStatement("""
                        SELECT 1 FROM word_category WHERE word_id = ?
                        UNION ALL
                        SELECT 1 FROM category WHERE word_id = ?""")) {
                    checkPs.setInt(1, wordId);
                    checkPs.setInt(2, wordId);
                    try (ResultSet rs = checkPs.executeQuery()) {
                        if (!rs.next()) {
                            try (PreparedStatement delPs = conn.prepareStatement(
                                    "DELETE FROM path_word WHERE word_id = ?")) {
                                delPs.setInt(1, wordId);
                                delPs.executeUpdate();
                            }
                        }
                    }
                }
            }
            return removed;
        }
    }

    // ==================== keywords ====================

    public int insertKeyword(String keyword, int categoryId) throws SQLException {
        keyword = requireKeywordPhrase(keyword);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO keyword (phrase, category_id) VALUES (?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, keyword);
            ps.setInt(2, categoryId);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public List<Row> selectAllKeywords(int limit, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT k.id AS id, k.phrase AS keyword, k.category_id AS category_id,
                       w.word AS category_word
                FROM keyword k
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                ORDER BY k.id LIMIT ? OFFSET ?""")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            return all(ps);
        }
    }

    public Row selectKeywordById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT k.id AS id, k.phrase AS keyword, k.category_id AS category_id,
                       w.word AS category_word
                FROM keyword k
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                WHERE k.id = ?""")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public int countKeywords() throws SQLException {
        return scalar("SELECT COUNT(*) FROM keyword");
    }

    /**
     * §15: every keyword with the number of <strong>distinct files</strong> it occurs
     * in and its total occurrence count, case-wide, ordered by file count.
     *
     * <p>One grouped query rather than the loop it replaces. The Comprehensive
     * Dashboard used to ask for the keyword list, then for each keyword ask for its
     * files, then sum the hits in Java: 1 + N queries returning up to 500 rows each,
     * for a figure the database can group in a single pass. At two hundred keywords
     * that was two hundred round trips and up to a hundred thousand rows crossing the
     * JDBC boundary to produce two hundred integers.
     *
     * <p>{@code COUNT(DISTINCT path_id)} is the count that matters: a keyword occurring
     * forty times in one file relates to one file, not forty, and conflating the two is
     * how a relationship count stops meaning anything.
     */
    public List<Row> selectKeywordUsage(int limit, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT k.id AS id, k.phrase AS keyword, w.word AS category_word,
                       COUNT(DISTINCT pk.path_id) AS files,
                       IFNULL(SUM(pk.hits), 0) AS hits
                FROM keyword k
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                LEFT JOIN path_keyword pk ON pk.keyword_id = k.id
                GROUP BY k.id, k.phrase, w.word
                ORDER BY files DESC, hits DESC, k.phrase
                LIMIT ? OFFSET ?""")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            return all(ps);
        }
    }

    /**
     * §15: every category with the number of distinct files related to it, by either
     * route — a direct {@code path_category} assignment or a file containing one of the
     * category's words. Unioned so neither route is invisible, and counted distinctly so
     * a file reached by both is still one file.
     */
    public List<Row> selectCategoryUsage(int limit, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, w.word AS word, COUNT(DISTINCT r.path_id) AS files
                FROM category c
                JOIN word w ON w.id = c.word_id
                LEFT JOIN (
                    -- attributed directly by a reviewer or an analysis run
                    SELECT pc.category_id AS category_id, pc.path_id AS path_id
                      FROM path_category pc
                    UNION
                    -- the file contains one of the category's linked vocabulary words
                    SELECT wc.category_id AS category_id, pw.path_id AS path_id
                      FROM path_word pw JOIN word_category wc ON wc.word_id = pw.word_id
                    UNION
                    -- the file contains the category's own word, which is a word row
                    -- in its own right. Omitting this route made the listing disagree
                    -- with selectCategoriesForPath, which has always counted it.
                    SELECT c2.id AS category_id, pw2.path_id AS path_id
                      FROM path_word pw2 JOIN category c2 ON c2.word_id = pw2.word_id
                ) r ON r.category_id = c.id
                GROUP BY c.id, w.word
                ORDER BY files DESC, w.word
                LIMIT ? OFFSET ?""")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            return all(ps);
        }
    }

    public boolean updateKeyword(int id, String keyword) throws SQLException {
        keyword = requireKeywordPhrase(keyword);
        try (PreparedStatement ps = conn.prepareStatement("UPDATE keyword SET phrase=? WHERE id=?")) {
            ps.setString(1, keyword);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteKeyword(int id) throws SQLException {
        return executeUpdate("DELETE FROM keyword WHERE id=?", id) > 0;
    }

    /**
     * Python {@code find_duplicates} across keyword phrases: the same phrase ignoring case
     * and surrounding space. The UNIQUE constraint only catches byte-identical text.
     */
    public List<Row> findDuplicateKeywords() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT MIN(phrase) AS keyword, LOWER(TRIM(phrase)) AS normalized,
                       COUNT(*) AS occurrences, MIN(id) AS keep_id
                FROM keyword GROUP BY LOWER(TRIM(phrase)) HAVING COUNT(*) > 1
                ORDER BY normalized""")) {
            return all(ps);
        }
    }

    /** Ids of every keyword in a case-insensitive duplicate group, lowest first. */
    public List<Row> selectKeywordsByNormalizedPhrase(String normalized) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, phrase, category_id FROM keyword
                WHERE LOWER(TRIM(phrase)) = ? ORDER BY id""")) {
            ps.setString(1, normalized);
            return all(ps);
        }
    }

    /**
     * Folds {@code fromId} into {@code intoId}: every file edge moves across (keeping the
     * larger hit count where both exist) and the duplicate row is removed. One transaction.
     */
    public int mergeKeyword(int fromId, int intoId) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int moved;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO path_keyword (path_id, keyword_id, hits)
                    SELECT path_id, ?, hits FROM path_keyword WHERE keyword_id = ?
                    ON CONFLICT(path_id, keyword_id) DO UPDATE SET
                        hits = MAX(hits, excluded.hits)""")) {
                ps.setInt(1, intoId);
                ps.setInt(2, fromId);
                moved = ps.executeUpdate();
            }
            executeUpdate("DELETE FROM path_keyword WHERE keyword_id=?", fromId);
            executeUpdate("DELETE FROM keyword WHERE id=?", fromId);
            conn.commit();
            return moved;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    /** Categories whose word is the same ignoring case — the reference's find-duplicates. */
    public List<Row> findDuplicateCategories() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT LOWER(TRIM(w.word)) AS normalized, COUNT(*) AS occurrences,
                       MIN(c.id) AS keep_id, GROUP_CONCAT(c.id) AS ids
                FROM category c JOIN word w ON w.id = c.word_id
                GROUP BY LOWER(TRIM(w.word)) HAVING COUNT(*) > 1 ORDER BY normalized""")) {
            return all(ps);
        }
    }

    /**
     * Folds category {@code fromId} into {@code intoId}: its words, keywords and file
     * attributions move across; the duplicate category (not its word) is removed.
     */
    public void mergeCategory(int fromId, int intoId) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR IGNORE INTO word_category (word_id, category_id)
                    SELECT word_id, ? FROM word_category WHERE category_id = ?""")) {
                ps.setInt(1, intoId);
                ps.setInt(2, fromId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR IGNORE INTO path_category (path_id, category_id)
                    SELECT path_id, ? FROM path_category WHERE category_id = ?""")) {
                ps.setInt(1, intoId);
                ps.setInt(2, fromId);
                ps.executeUpdate();
            }
            executeUpdate("UPDATE keyword SET category_id=? WHERE category_id=?", intoId, fromId);
            executeUpdate("DELETE FROM word_category WHERE category_id=?", fromId);
            executeUpdate("DELETE FROM path_category WHERE category_id=?", fromId);
            executeUpdate("DELETE FROM category WHERE id=?", fromId);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    // ==================== alerts / notifications ====================

    public int insertAlert(String alertType, String priority, String title, String message,
                           String fileId, Instant createdAt, Instant eventDate) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO alert (alert_type, priority, title, message, element_id,
                                    is_read, dismissed, created_at, event_date)
                VALUES (?,?,?,?,?,0,0,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, alertType);
            ps.setString(2, priority);
            ps.setString(3, title);
            ps.setString(4, message);
            ps.setString(5, fileId);
            ps.setLong(6, createdAt.toEpochMilli());
            if (eventDate == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setLong(7, eventDate.toEpochMilli());
            }
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    /**
     * @param unreadOnly    Python {@code get_unread_alerts}
     * @param activeOnly    Python {@code get_active_alerts} (not dismissed)
     */
    public List<Row> selectAlerts(boolean unreadOnly, boolean activeOnly, String alertType,
                                  int limit, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM alert WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (unreadOnly) {
            sql.append(" AND is_read = 0");
        }
        if (activeOnly) {
            sql.append(" AND dismissed = 0");
        }
        if (alertType != null && !alertType.isBlank()) {
            sql.append(" AND alert_type = ?");
            args.add(alertType);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            return all(ps);
        }
    }

    public Row selectAlertById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM alert WHERE id=?")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public boolean markAlertRead(int id) throws SQLException {
        return executeUpdate("UPDATE alert SET is_read=1 WHERE id=?", id) > 0;
    }

    public boolean dismissAlert(int id) throws SQLException {
        return executeUpdate("UPDATE alert SET dismissed=1 WHERE id=?", id) > 0;
    }

    public int countUnreadAlerts() throws SQLException {
        return scalar("SELECT COUNT(*) FROM alert WHERE is_read=0");
    }

    public int countActiveAlerts() throws SQLException {
        return scalar("SELECT COUNT(*) FROM alert WHERE dismissed=0");
    }

    /** Python {@code get_upcoming_events(days_ahead)}. */
    public List<Row> selectUpcomingAlerts(Instant now, Instant until, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT * FROM alert
                WHERE event_date IS NOT NULL AND event_date >= ? AND event_date <= ?
                  AND dismissed = 0
                ORDER BY event_date ASC LIMIT ?""")) {
            ps.setLong(1, now.toEpochMilli());
            ps.setLong(2, until.toEpochMilli());
            ps.setInt(3, limit);
            return all(ps);
        }
    }

    // ==================== search history / saved searches ====================

    public int insertHistory(String query, int resultCount, String userId, Instant at)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO search_history (query, result_count, user_id, searched_at)
                VALUES (?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, query);
            ps.setInt(2, resultCount);
            ps.setString(3, userId);
            ps.setLong(4, at.toEpochMilli());
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public List<Row> selectHistory(int limit, String userId) throws SQLException {
        String sql = userId == null
                ? "SELECT * FROM search_history ORDER BY searched_at DESC LIMIT ?"
                : "SELECT * FROM search_history WHERE user_id=? ORDER BY searched_at DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (userId != null) {
                ps.setString(i++, userId);
            }
            ps.setInt(i, limit);
            return all(ps);
        }
    }

    public int clearHistory(String userId) throws SQLException {
        if (userId == null) {
            try (Statement st = conn.createStatement()) {
                return st.executeUpdate("DELETE FROM search_history");
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM search_history WHERE user_id=?")) {
            ps.setString(1, userId);
            return ps.executeUpdate();
        }
    }

    public int insertSavedSearch(String name, String query, String filters, String userId,
                                 Instant createdAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO saved_search (name, query, filters, user_id, created_at, use_count)
                VALUES (?,?,?,?,?,0)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, query);
            ps.setString(3, filters);
            ps.setString(4, userId);
            ps.setLong(5, createdAt.toEpochMilli());
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public List<Row> selectSavedSearches(String userId) throws SQLException {
        String sql = userId == null
                ? "SELECT * FROM saved_search ORDER BY created_at DESC"
                : "SELECT * FROM saved_search WHERE user_id=? ORDER BY created_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (userId != null) {
                ps.setString(1, userId);
            }
            return all(ps);
        }
    }

    public Row selectSavedSearchById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM saved_search WHERE id=?")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public boolean updateSavedSearch(int id, String name, String query, String filters)
            throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE saved_search SET ");
        List<Object> args = new ArrayList<>();
        List<String> sets = new ArrayList<>();
        if (name != null) {
            sets.add("name=?");
            args.add(name);
        }
        if (query != null) {
            sets.add("query=?");
            args.add(query);
        }
        if (filters != null) {
            sets.add("filters=?");
            args.add(filters);
        }
        if (sets.isEmpty()) {
            return false;
        }
        sql.append(String.join(", ", sets)).append(" WHERE id=?");
        args.add(id);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteSavedSearch(int id) throws SQLException {
        return executeUpdate("DELETE FROM saved_search WHERE id=?", id) > 0;
    }

    public boolean markSavedSearchUsed(int id, Instant when) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE saved_search SET last_used=?, use_count=use_count+1 WHERE id=?")) {
            ps.setLong(1, when.toEpochMilli());
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        }
    }


    // ==================== hashs / paths / contents ====================

    public int insertHash(String hashValue, Integer sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM hash WHERE hash_value=? AND IFNULL(source_id,-1)=IFNULL(?,-1)")) {
            ps.setString(1, hashValue);
            if (sourceId == null) { ps.setNull(2, Types.INTEGER); } else { ps.setInt(2, sourceId); }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO hash (hash_value, source_id) VALUES (?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, hashValue);
            if (sourceId == null) { ps.setNull(2, Types.INTEGER); } else { ps.setInt(2, sourceId); }
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public boolean hashExists(String hashValue, int sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM hash WHERE hash_value=? AND source_id=?")) {
            ps.setString(1, hashValue);
            ps.setInt(2, sourceId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public int insertPath(String fileName, String filePath, long fileSize, String fileType,
                          String fileStatus, LocalDate fileDate, LocalDate dateCreation,
                          Integer hashId, String coordinates, Integer sourceId,
                          Integer sideId, String elementId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO path (file_name, file_path, file_size, file_type, file_status,
                                   file_date, date_creation, hash_id, coordinates,
                                   source_id, aspect_id, element_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, fileName);
            ps.setString(2, filePath);
            ps.setLong(3, fileSize);
            ps.setString(4, fileType);
            ps.setString(5, fileStatus);
            ps.setString(6, fileDate.toString());
            ps.setString(7, dateCreation.toString());
            if (hashId == null) { ps.setNull(8, Types.INTEGER); } else { ps.setInt(8, hashId); }
            ps.setString(9, coordinates);
            if (sourceId == null) { ps.setNull(10, Types.INTEGER); } else { ps.setInt(10, sourceId); }
            if (sideId == null) { ps.setNull(11, Types.INTEGER); } else { ps.setInt(11, sideId); }
            ps.setString(12, elementId);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public Row selectPathById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.*, h.hash_value AS hash_value,
                       s.name AS source_name, d.name AS aspect_name
                FROM path p
                LEFT JOIN hash h ON h.id = p.hash_id
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect d ON d.id = p.aspect_id
                WHERE p.id = ?""")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public List<Row> selectPaths(String fileType, Integer sourceId, Integer sideId,
                                 String status, int limit, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT p.*, h.hash_value AS hash_value,
                       s.name AS source_name, d.name AS aspect_name
                FROM path p
                LEFT JOIN hash h ON h.id = p.hash_id
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect d ON d.id = p.aspect_id
                WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (fileType != null && !fileType.isBlank()) { sql.append(" AND p.file_type = ?"); args.add(fileType); }
        if (sourceId != null) { sql.append(" AND p.source_id = ?"); args.add(sourceId); }
        if (sideId != null) { sql.append(" AND p.aspect_id = ?"); args.add(sideId); }
        if (status != null && !status.isBlank()) { sql.append(" AND p.file_status = ?"); args.add(status); }
        sql.append(" ORDER BY p.id LIMIT ? OFFSET ?");
        args.add(limit); args.add(offset);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            return all(ps);
        }
    }

    public int countPaths(String fileType, Integer sourceId, Integer sideId, String status)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM path WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (fileType != null && !fileType.isBlank()) { sql.append(" AND file_type = ?"); args.add(fileType); }
        if (sourceId != null) { sql.append(" AND source_id = ?"); args.add(sourceId); }
        if (sideId != null) { sql.append(" AND aspect_id = ?"); args.add(sideId); }
        if (status != null && !status.isBlank()) { sql.append(" AND file_status = ?"); args.add(status); }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    public boolean updatePathStatus(int pathId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE path SET file_status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setInt(2, pathId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deletePath(int id) throws SQLException {
        return executeUpdate("DELETE FROM path WHERE id=?", id) > 0;
    }

    public Integer findPathIdByElement(String elementId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM path WHERE element_id=?")) {
            ps.setString(1, elementId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        }
    }

    public int insertContent(String contentData, LocalDate contentDate, int pathId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO content (content_data, content_date, path_id) VALUES (?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, contentData);
            ps.setString(2, contentDate == null ? null : contentDate.toString());
            ps.setInt(3, pathId);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public List<Row> selectContentsByPath(int pathId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM content WHERE path_id=? ORDER BY id")) {
            ps.setInt(1, pathId);
            return all(ps);
        }
    }

    public Row selectContentById(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM content WHERE id=?")) {
            ps.setInt(1, id);
            return single(ps);
        }
    }

    public int countContents() throws SQLException {
        return scalar("SELECT COUNT(*) FROM content");
    }

    public int countPathsAll() throws SQLException {
        return scalar("SELECT COUNT(*) FROM path");
    }

    public long sumPathBytes() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT IFNULL(SUM(file_size),0) FROM path")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public Map<String, Integer> countPathsByType() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT file_type, COUNT(*) FROM path GROUP BY file_type ORDER BY 2 DESC")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    public boolean deleteContent(int id) throws SQLException {
        return executeUpdate("DELETE FROM content WHERE id=?", id) > 0;
    }

    // ============ relationships: path <-> category / keyword ============

    public boolean linkPathToCategory(int pathId, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO path_category (path_id, category_id) VALUES (?,?)")) {
            ps.setInt(1, pathId);
            ps.setInt(2, categoryId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean unlinkPathFromCategory(int pathId, int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM path_category WHERE path_id=? AND category_id=?")) {
            ps.setInt(1, pathId);
            ps.setInt(2, categoryId);
            return ps.executeUpdate() > 0;
        }
    }

    public List<Row> selectPathCategories(int pathId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, c.word_id AS word_id, w.word AS word
                FROM path_category pc
                JOIN category c ON c.id = pc.category_id
                JOIN word w ON w.id = c.word_id
                WHERE pc.path_id = ? ORDER BY w.word""")) {
            ps.setInt(1, pathId);
            return all(ps);
        }
    }

    public boolean linkPathToKeyword(int pathId, int keywordId, int hits) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO path_keyword (path_id, keyword_id, hits) VALUES (?,?,?)
                ON CONFLICT(path_id, keyword_id) DO UPDATE SET hits = excluded.hits""")) {
            ps.setInt(1, pathId);
            ps.setInt(2, keywordId);
            ps.setInt(3, hits);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Removes a keyword association from a file.
     *
     * <p>Present for symmetry with {@link #unlinkPathFromCategory} and
     * {@link #unlinkPathFromWord}: every relation the model can create, it must be able
     * to retract, or a mistaken association becomes permanent and the counts in §15
     * can only ever grow.
     */
    public boolean unlinkPathFromKeyword(int pathId, int keywordId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM path_keyword WHERE path_id=? AND keyword_id=?")) {
            ps.setInt(1, pathId);
            ps.setInt(2, keywordId);
            return ps.executeUpdate() > 0;
        }
    }

    public List<Row> selectPathKeywords(int pathId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT k.id AS id, k.phrase AS keyword, k.category_id AS category_id,
                       w.word AS category_word, pk.hits AS hits
                FROM path_keyword pk
                JOIN keyword k ON k.id = pk.keyword_id
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                WHERE pk.path_id = ? ORDER BY pk.hits DESC""")) {
            ps.setInt(1, pathId);
            return all(ps);
        }
    }

    /** Paths carrying a given keyword — the "where does this phrase appear" view. */
    public List<Row> selectPathsForKeyword(int keywordId, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.*, h.hash_value AS hash_value,
                       s.name AS source_name, a.name AS aspect_name, pk.hits AS hits
                FROM path_keyword pk
                JOIN path p ON p.id = pk.path_id
                LEFT JOIN hash h ON h.id = p.hash_id
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                WHERE pk.keyword_id = ? ORDER BY pk.hits DESC LIMIT ?""")) {
            ps.setInt(1, keywordId);
            ps.setInt(2, limit);
            return all(ps);
        }
    }

    /**
     * Join across the schema boundary: corpus {@code path} rows enriched with their
     * forensic {@code item} status. Only possible because both live in one database.
     */
    public List<Row> selectPathsWithItemStatus(int limit, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.id AS id, p.file_name AS file_name, p.file_type AS file_type,
                       p.element_id AS element_id, i.status AS item_status,
                       i.custodian AS custodian
                FROM path p
                LEFT JOIN item i ON i.id = p.element_id
                ORDER BY p.id LIMIT ? OFFSET ?""")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            return all(ps);
        }
    }

    /** Counts per source, joined through paths — drives the dashboard breakdown. */
    public Map<String, Integer> countPathsBySource() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT IFNULL(s.name,'(unassigned)') AS name, COUNT(*) AS n
                FROM path p LEFT JOIN source s ON s.id = p.source_id
                GROUP BY name ORDER BY n DESC""")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    public Map<String, Integer> countPathsByAspect() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT IFNULL(a.name,'(unassigned)') AS name, COUNT(*) AS n
                FROM path p LEFT JOIN aspect a ON a.id = p.aspect_id
                GROUP BY name ORDER BY n DESC""")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    public Map<String, Integer> countPathsByCategory() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT w.word AS name, COUNT(*) AS n
                FROM path_category pc
                JOIN category c ON c.id = pc.category_id
                JOIN word w ON w.id = c.word_id
                GROUP BY name ORDER BY n DESC""")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    // ============ the relationship graph: file ↔ keyword ↔ category ↔ word ============
    //
    // Every relation below is written once and used in both directions. That is the
    // point: a detail screen and the reverse-lookup screen that disagree is one of the
    // easiest defects to ship and one of the hardest to notice, so "files for this
    // category" and "categories for this file" are two readings of the same join, not
    // two queries that happen to look similar.
    //
    // A file belongs to a category when a reviewer or an analysis run attached it
    // (path_category), or when the file contains one of the category's words
    // (path_word ⨝ word_category) — which is how the reference model relates the two
    // (words_paths ⨝ words_categorys). Both are unioned so neither route is invisible.

    /** The words that make up a category: its own word, plus every word linked to it. */
    private static final String WORDS_OF_CATEGORY = """
            SELECT c.word_id AS word_id FROM category c WHERE c.id = ?
            UNION
            SELECT wc.word_id AS word_id FROM word_category wc WHERE wc.category_id = ?
            """;

    /** Path ids related to a category, by attribution or by vocabulary. */
    private static final String PATHS_OF_CATEGORY = """
            SELECT pc.path_id AS path_id FROM path_category pc WHERE pc.category_id = ?
            UNION
            SELECT pw.path_id AS path_id FROM path_word pw
             WHERE pw.word_id IN (SELECT c.word_id FROM category c WHERE c.id = ?
                                  UNION
                                  SELECT wc.word_id FROM word_category wc WHERE wc.category_id = ?)
            """;

    /** Records that a category word occurs in a file, with how often. */
    public boolean linkPathToWord(int pathId, int wordId, int hits) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO path_word (path_id, word_id, hits) VALUES (?,?,?)
                ON CONFLICT(path_id, word_id) DO UPDATE SET hits = excluded.hits""")) {
            ps.setInt(1, pathId);
            ps.setInt(2, wordId);
            ps.setInt(3, hits);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean unlinkPathFromWord(int pathId, int wordId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM path_word WHERE path_id=? AND word_id=?")) {
            ps.setInt(1, pathId);
            ps.setInt(2, wordId);
            return ps.executeUpdate() > 0;
        }
    }

    // ---- counts -------------------------------------------------------------

    /** How many files carry this keyword, anywhere in the case. */
    public int countFilesForKeyword(int keywordId) throws SQLException {
        return scalar("SELECT COUNT(DISTINCT path_id) FROM path_keyword WHERE keyword_id=?", keywordId);
    }

    /** How many files this category relates to, by attribution or by its words. */
    public int countFilesForCategory(int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM (" + PATHS_OF_CATEGORY + ")")) {
            ps.setInt(1, categoryId);
            ps.setInt(2, categoryId);
            ps.setInt(3, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** How many files this word occurs in. */
    public int countFilesForWord(int wordId) throws SQLException {
        return scalar("SELECT COUNT(DISTINCT path_id) FROM path_word WHERE word_id=?", wordId);
    }

    private int scalar(String sql, int arg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ---- term → files -------------------------------------------------------

    private static final String FILE_COLUMNS = """
            SELECT p.id AS id, p.file_name AS file_name, p.file_path AS file_path,
                   p.file_size AS file_size, p.file_type AS file_type,
                   p.file_status AS file_status, p.file_date AS file_date,
                   p.element_id AS element_id,
                   s.name AS source_name, a.name AS aspect_name
            """;

    /** Files carrying a keyword, most occurrences first. */
    public List<Row> selectFilesForKeyword(int keywordId, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(FILE_COLUMNS + """
                , pk.hits AS hits
                FROM path_keyword pk
                JOIN path p ON p.id = pk.path_id
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                WHERE pk.keyword_id = ?
                ORDER BY pk.hits DESC, p.file_name LIMIT ?""")) {
            ps.setInt(1, keywordId);
            ps.setInt(2, limit);
            return all(ps);
        }
    }

    /** Files related to a category, by attribution or by one of its words. */
    public List<Row> selectFilesForCategory(int categoryId, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(FILE_COLUMNS + """
                , 0 AS hits
                FROM path p
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                WHERE p.id IN (""" + PATHS_OF_CATEGORY + """
                ) ORDER BY p.file_name LIMIT ?""")) {
            ps.setInt(1, categoryId);
            ps.setInt(2, categoryId);
            ps.setInt(3, categoryId);
            ps.setInt(4, limit);
            return all(ps);
        }
    }

    /** Files a category word occurs in, most occurrences first. */
    public List<Row> selectFilesForWord(int wordId, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(FILE_COLUMNS + """
                , pw.hits AS hits
                FROM path_word pw
                JOIN path p ON p.id = pw.path_id
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                WHERE pw.word_id = ?
                ORDER BY pw.hits DESC, p.file_name LIMIT ?""")) {
            ps.setInt(1, wordId);
            ps.setInt(2, limit);
            return all(ps);
        }
    }

    // ---- file → terms -------------------------------------------------------

    /** Categories this file relates to: attributed, or carrying one of its words. */
    public List<Row> selectCategoriesForPath(int pathId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, w.word AS word,
                       MAX(direct) AS attributed
                FROM (
                    SELECT pc.category_id AS category_id, 1 AS direct
                      FROM path_category pc WHERE pc.path_id = ?
                    UNION ALL
                    SELECT wc.category_id AS category_id, 0 AS direct
                      FROM path_word pw JOIN word_category wc ON wc.word_id = pw.word_id
                     WHERE pw.path_id = ?
                    UNION ALL
                    SELECT c2.id AS category_id, 0 AS direct
                      FROM path_word pw2 JOIN category c2 ON c2.word_id = pw2.word_id
                     WHERE pw2.path_id = ?
                ) rel
                JOIN category c ON c.id = rel.category_id
                JOIN word w ON w.id = c.word_id
                GROUP BY c.id, w.word
                ORDER BY w.word""")) {
            ps.setInt(1, pathId);
            ps.setInt(2, pathId);
            ps.setInt(3, pathId);
            return all(ps);
        }
    }

    /** Category words occurring in this file, most occurrences first. */
    public List<Row> selectWordsForPath(int pathId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT w.id AS id, w.word AS word, pw.hits AS hits
                FROM path_word pw
                JOIN word w ON w.id = pw.word_id
                WHERE pw.path_id = ?
                ORDER BY pw.hits DESC, w.word""")) {
            ps.setInt(1, pathId);
            return all(ps);
        }
    }

    // ---- term → term --------------------------------------------------------

    /** Keywords belonging to a category. */
    public List<Row> selectKeywordsOfCategory(int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT k.id AS id, k.phrase AS keyword, k.category_id AS category_id
                FROM keyword k WHERE k.category_id = ? ORDER BY k.phrase""")) {
            ps.setInt(1, categoryId);
            return all(ps);
        }
    }

    /** The category words of a category — its own word first, then the linked ones. */
    public List<Row> selectWordsOfCategory(int categoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT w.id AS id, w.word AS word
                FROM (""" + WORDS_OF_CATEGORY + """
                ) ids JOIN word w ON w.id = ids.word_id
                ORDER BY w.word""")) {
            ps.setInt(1, categoryId);
            ps.setInt(2, categoryId);
            return all(ps);
        }
    }

    /**
     * Keywords whose phrase contains a given word.
     *
     * <p>Matched on the normalised phrase with word boundaries, so "account" finds
     * "annual account statement" but not "accountancy".
     */
    public List<Row> selectKeywordsContainingWord(String word) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT k.id AS id, k.phrase AS keyword, k.category_id AS category_id,
                       w.word AS category_word
                FROM keyword k
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                WHERE ' ' || LOWER(k.phrase) || ' ' LIKE '% ' || LOWER(?) || ' %'
                ORDER BY k.phrase""")) {
            ps.setString(1, word);
            return all(ps);
        }
    }

    /** Files that share a keyword, category or category word with this one. */
    public List<Row> selectRelatedFiles(int pathId, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(FILE_COLUMNS + """
                , shared.shared AS hits
                FROM (
                    SELECT other.path_id AS path_id, COUNT(*) AS shared FROM (
                        SELECT pk2.path_id FROM path_keyword pk1
                          JOIN path_keyword pk2 ON pk2.keyword_id = pk1.keyword_id
                         WHERE pk1.path_id = ? AND pk2.path_id <> ?
                        UNION ALL
                        SELECT pc2.path_id FROM path_category pc1
                          JOIN path_category pc2 ON pc2.category_id = pc1.category_id
                         WHERE pc1.path_id = ? AND pc2.path_id <> ?
                        UNION ALL
                        SELECT pw2.path_id FROM path_word pw1
                          JOIN path_word pw2 ON pw2.word_id = pw1.word_id
                         WHERE pw1.path_id = ? AND pw2.path_id <> ?
                    ) other GROUP BY other.path_id
                ) shared
                JOIN path p ON p.id = shared.path_id
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                ORDER BY shared.shared DESC, p.file_name LIMIT ?""")) {
            for (int i = 1; i <= 6; i++) {
                ps.setInt(i, pathId);
            }
            ps.setInt(7, limit);
            return all(ps);
        }
    }

    // ---- lists with the counts the tables show -------------------------------

    /** Every keyword with the number of files it appears in. */
    public List<Row> selectKeywordsWithFileCounts(String search, int limit, int offset)
            throws SQLException {
        String like = search == null || search.isBlank() ? null : "%" + search.toLowerCase() + "%";
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT k.id AS id, k.phrase AS keyword, k.category_id AS category_id,
                       w.word AS category_word,
                       COUNT(DISTINCT pk.path_id) AS files,
                       IFNULL(SUM(pk.hits),0) AS hits
                FROM keyword k
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                LEFT JOIN path_keyword pk ON pk.keyword_id = k.id
                WHERE (? IS NULL OR LOWER(k.phrase) LIKE ?)
                GROUP BY k.id, k.phrase, k.category_id, w.word
                ORDER BY files DESC, k.phrase
                LIMIT ? OFFSET ?""")) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            return all(ps);
        }
    }

    /** Every category with its file count and how many words belong to it. */
    public List<Row> selectCategoriesWithFileCounts(String search, int limit, int offset)
            throws SQLException {
        String like = search == null || search.isBlank() ? null : "%" + search.toLowerCase() + "%";
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, c.word_id AS word_id, w.word AS word,
                       (SELECT COUNT(*) FROM (
                            SELECT pc.path_id FROM path_category pc WHERE pc.category_id = c.id
                            UNION
                            SELECT pw.path_id FROM path_word pw
                             WHERE pw.word_id IN (SELECT c2.word_id FROM category c2 WHERE c2.id = c.id
                                                  UNION
                                                  SELECT wc.word_id FROM word_category wc
                                                   WHERE wc.category_id = c.id)
                       )) AS files,
                       (SELECT COUNT(*) FROM (
                            SELECT c3.word_id FROM category c3 WHERE c3.id = c.id
                            UNION
                            SELECT wc2.word_id FROM word_category wc2 WHERE wc2.category_id = c.id
                       )) AS words,
                       (SELECT COUNT(*) FROM keyword k WHERE k.category_id = c.id) AS keywords
                FROM category c
                JOIN word w ON w.id = c.word_id
                WHERE (? IS NULL OR LOWER(w.word) LIKE ?)
                ORDER BY files DESC, w.word
                LIMIT ? OFFSET ?""")) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            return all(ps);
        }
    }

    /** Every category word with the number of files it occurs in. */
    public List<Row> selectCategoryWordsWithFileCounts(String search, int limit, int offset)
            throws SQLException {
        String like = search == null || search.isBlank() ? null : "%" + search.toLowerCase() + "%";
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT w.id AS id, w.word AS word,
                       COUNT(DISTINCT pw.path_id) AS files,
                       IFNULL(SUM(pw.hits),0) AS hits,
                       (SELECT COUNT(DISTINCT wc.category_id) FROM word_category wc
                         WHERE wc.word_id = w.id) AS categories
                FROM word w
                LEFT JOIN path_word pw ON pw.word_id = w.id
                WHERE (? IS NULL OR LOWER(w.word) LIKE ?)
                  AND (EXISTS (SELECT 1 FROM word_category wc2 WHERE wc2.word_id = w.id)
                       OR EXISTS (SELECT 1 FROM category c2 WHERE c2.word_id = w.id))
                GROUP BY w.id, w.word
                ORDER BY files DESC, w.word
                LIMIT ? OFFSET ?""")) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            return all(ps);
        }
    }

    /** Lookup by text, for search: the keyword whose phrase matches, if any. */
    public List<Row> selectKeywordsMatching(String text) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT k.id AS id, k.phrase AS keyword, k.category_id AS category_id,
                       w.word AS category_word
                FROM keyword k
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                WHERE LOWER(k.phrase) LIKE '%' || LOWER(?) || '%'
                ORDER BY LENGTH(k.phrase), k.phrase""")) {
            ps.setString(1, text);
            return all(ps);
        }
    }

    /** Lookup by text: categories whose word matches. */
    public List<Row> selectCategoriesMatching(String text) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, w.word AS word
                FROM category c JOIN word w ON w.id = c.word_id
                WHERE LOWER(w.word) LIKE '%' || LOWER(?) || '%'
                ORDER BY w.word""")) {
            ps.setString(1, text);
            return all(ps);
        }
    }

    /** Lookup by text: category words that match. */
    public List<Row> selectCategoryWordsMatching(String text) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT w.id AS id, w.word AS word
                FROM word w
                WHERE LOWER(w.word) LIKE '%' || LOWER(?) || '%'
                  AND (EXISTS (SELECT 1 FROM word_category wc WHERE wc.word_id = w.id)
                       OR EXISTS (SELECT 1 FROM category c WHERE c.word_id = w.id))
                ORDER BY w.word""")) {
            ps.setString(1, text);
            return all(ps);
        }
    }

    // ---- derived edges: what analysis writes and search reads ----------------

    /** The words that are a category or belong to one — the case's vocabulary. */
    public List<Row> selectVocabularyWords() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT w.id AS id, w.word AS word
                FROM word w
                WHERE EXISTS (SELECT 1 FROM word_category wc WHERE wc.word_id = w.id)
                   OR EXISTS (SELECT 1 FROM category c WHERE c.word_id = w.id)
                ORDER BY w.id""")) {
            return all(ps);
        }
    }

    /**
     * Removes the edges analysis derives for one file, so a re-run starts clean.
     *
     * <p>Only {@code path_word} and {@code path_keyword} are derived from text;
     * {@code path_category} is an attribution a reviewer made and is left alone.
     */
    public void clearDerivedRelations(int pathId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM path_word WHERE path_id=?")) {
            ps.setInt(1, pathId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM path_keyword WHERE path_id=?")) {
            ps.setInt(1, pathId);
            ps.executeUpdate();
        }
    }

    /** Removes the derived edges for one keyword across all files. */
    public void clearKeywordEdges(int keywordId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM path_keyword WHERE keyword_id=?")) {
            ps.setInt(1, keywordId);
            ps.executeUpdate();
        }
    }

    /** Removes the derived edges for one category word across all files. */
    public void clearWordEdges(int wordId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM path_word WHERE word_id=?")) {
            ps.setInt(1, wordId);
            ps.executeUpdate();
        }
    }

    /** All non-empty extracted text grouped by path id. */
    public List<Row> selectAllContentData() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT path_id, GROUP_CONCAT(content_data, char(10)) AS content_data " +
                "FROM content WHERE content_data IS NOT NULL AND content_data != '' " +
                "GROUP BY path_id ORDER BY path_id")) {
            return all(ps);
        }
    }

    /** Files whose name or path contains the text, with which of the two matched. */
    public List<Row> selectFilesByNameOrPath(String text, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(FILE_COLUMNS + """
                , CASE WHEN LOWER(p.file_name) LIKE '%' || LOWER(?) || '%' THEN 1 ELSE 0 END AS name_match
                FROM path p
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                WHERE LOWER(p.file_name) LIKE '%' || LOWER(?) || '%'
                   OR LOWER(p.file_path) LIKE '%' || LOWER(?) || '%'
                ORDER BY name_match DESC, p.file_name LIMIT ?""")) {
            ps.setString(1, text);
            ps.setString(2, text);
            ps.setString(3, text);
            ps.setInt(4, limit);
            return all(ps);
        }
    }

    /**
     * Files whose registry metadata contains the text: type, status, hash, coordinates,
     * source name or aspect name. Returns which field matched.
     */
    public List<Row> selectFilesByMetadata(String text, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(FILE_COLUMNS + """
                , h.hash_value AS hash_value, p.coordinates AS coordinates,
                  CASE
                    WHEN LOWER(p.file_type) LIKE '%' || LOWER(?) || '%' THEN 'type'
                    WHEN LOWER(p.file_status) = LOWER(?) THEN 'status'
                    WHEN LOWER(IFNULL(h.hash_value,'')) LIKE '%' || LOWER(?) || '%' THEN 'sha-256'
                    WHEN LOWER(IFNULL(p.coordinates,'')) LIKE '%' || LOWER(?) || '%' THEN 'coordinates'
                    WHEN LOWER(IFNULL(s.name,'')) LIKE '%' || LOWER(?) || '%' THEN 'source'
                    WHEN LOWER(IFNULL(a.name,'')) LIKE '%' || LOWER(?) || '%' THEN 'aspect'
                    ELSE 'metadata' END AS field
                FROM path p
                LEFT JOIN hash h ON h.id = p.hash_id
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                WHERE LOWER(p.file_type) LIKE '%' || LOWER(?) || '%'
                   OR LOWER(p.file_status) = LOWER(?)
                   OR LOWER(IFNULL(h.hash_value,'')) LIKE '%' || LOWER(?) || '%'
                   OR LOWER(IFNULL(p.coordinates,'')) LIKE '%' || LOWER(?) || '%'
                   OR LOWER(IFNULL(s.name,'')) LIKE '%' || LOWER(?) || '%'
                   OR LOWER(IFNULL(a.name,'')) LIKE '%' || LOWER(?) || '%'
                ORDER BY p.file_name LIMIT ?""")) {
            for (int i = 1; i <= 12; i++) {
                ps.setString(i, text);
            }
            ps.setInt(13, limit);
            return all(ps);
        }
    }

    /** Files whose stored content contains the text, case-insensitively. */
    public List<Row> selectFilesByContent(String text, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(FILE_COLUMNS + """
                , 0 AS hits
                FROM path p
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                WHERE EXISTS (SELECT 1 FROM content c WHERE c.path_id = p.id
                              AND LOWER(IFNULL(c.content_data,'')) LIKE '%' || LOWER(?) || '%')
                ORDER BY p.file_name LIMIT ?""")) {
            ps.setString(1, text);
            ps.setInt(2, limit);
            return all(ps);
        }
    }

    /**
     * Edges whose endpoints no longer exist, or keywords whose category is gone.
     * Foreign keys should make this empty; the checker verifies rather than assumes.
     */
    public List<Row> selectOrphanEdges() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 'path_keyword' AS edge_table, pk.path_id AS a, pk.keyword_id AS b,
                       'keyword or path missing' AS problem
                FROM path_keyword pk
                WHERE NOT EXISTS (SELECT 1 FROM keyword k WHERE k.id = pk.keyword_id)
                   OR NOT EXISTS (SELECT 1 FROM path p WHERE p.id = pk.path_id)
                UNION ALL
                SELECT 'path_word', pw.path_id, pw.word_id, 'word or path missing'
                FROM path_word pw
                WHERE NOT EXISTS (SELECT 1 FROM word w WHERE w.id = pw.word_id)
                   OR NOT EXISTS (SELECT 1 FROM path p WHERE p.id = pw.path_id)
                UNION ALL
                SELECT 'path_category', pc.path_id, pc.category_id, 'category or path missing'
                FROM path_category pc
                WHERE NOT EXISTS (SELECT 1 FROM category c WHERE c.id = pc.category_id)
                   OR NOT EXISTS (SELECT 1 FROM path p WHERE p.id = pc.path_id)
                UNION ALL
                SELECT 'word_category', wc.word_id, wc.category_id, 'word or category missing'
                FROM word_category wc
                WHERE NOT EXISTS (SELECT 1 FROM word w WHERE w.id = wc.word_id)
                   OR NOT EXISTS (SELECT 1 FROM category c WHERE c.id = wc.category_id)
                UNION ALL
                SELECT 'keyword', k.id, k.category_id, 'keyword category missing'
                FROM keyword k
                WHERE NOT EXISTS (SELECT 1 FROM category c WHERE c.id = k.category_id)""")) {
            return all(ps);
        }
    }

    /** Whole-case totals for the relationship checker and the overview tiles. */
    public Row selectRelationshipTotals() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT (SELECT COUNT(*) FROM path)                 AS files,
                       (SELECT COUNT(*) FROM keyword)              AS keywords,
                       (SELECT COUNT(*) FROM category)             AS categories,
                       (SELECT COUNT(*) FROM (
                            SELECT word_id FROM word_category
                            UNION SELECT word_id FROM category))  AS category_words,
                       (SELECT COUNT(*) FROM path_keyword)         AS keyword_edges,
                       (SELECT COUNT(*) FROM path_word)            AS word_edges,
                       (SELECT COUNT(*) FROM path_category)        AS category_edges""")) {
            return single(ps);
        }
    }

    // ============ aggregates for the detail and analytics destinations ============

    /** Per-source rollup: file count, total bytes, distinct types, read count. */
    public Row selectSourceStatistics(int sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*)                                   AS files,
                       IFNULL(SUM(p.file_size),0)                 AS bytes,
                       COUNT(DISTINCT p.file_type)                AS types,
                       SUM(CASE WHEN p.file_status='Read' THEN 1 ELSE 0 END) AS read_files,
                       MIN(p.file_date)                           AS earliest,
                       MAX(p.file_date)                           AS latest
                FROM path p WHERE p.source_id = ?""")) {
            ps.setInt(1, sourceId);
            return single(ps);
        }
    }

    /** Per-aspect rollup, same shape as {@link #selectSourceStatistics}. */
    public Row selectAspectStatistics(int aspectId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*)                                   AS files,
                       IFNULL(SUM(p.file_size),0)                 AS bytes,
                       COUNT(DISTINCT p.file_type)                AS types,
                       SUM(CASE WHEN p.file_status='Read' THEN 1 ELSE 0 END) AS read_files,
                       MIN(p.file_date)                           AS earliest,
                       MAX(p.file_date)                           AS latest
                FROM path p WHERE p.aspect_id = ?""")) {
            ps.setInt(1, aspectId);
            return single(ps);
        }
    }

    /** File-type histogram scoped to one source. */
    public Map<String, Integer> countTypesForSource(int sourceId) throws SQLException {
        return countScoped("SELECT file_type, COUNT(*) FROM path WHERE source_id=? "
                + "GROUP BY file_type ORDER BY 2 DESC", sourceId);
    }

    /** File-type histogram scoped to one aspect. */
    public Map<String, Integer> countTypesForAspect(int aspectId) throws SQLException {
        return countScoped("SELECT file_type, COUNT(*) FROM path WHERE aspect_id=? "
                + "GROUP BY file_type ORDER BY 2 DESC", aspectId);
    }

    /** Categories attached to files from one source, with file counts. */
    public List<Row> selectCategoriesForSource(int sourceId) throws SQLException {
        return joinedTerms("""
                SELECT c.id AS id, w.word AS word, COUNT(*) AS files
                FROM path p
                JOIN path_category pc ON pc.path_id = p.id
                JOIN category c ON c.id = pc.category_id
                JOIN word w ON w.id = c.word_id
                WHERE p.source_id = ?
                GROUP BY c.id, w.word ORDER BY files DESC""", sourceId);
    }

    /** Keywords found in files from one source, with total hits. */
    public List<Row> selectKeywordsForSource(int sourceId) throws SQLException {
        return joinedTerms("""
                SELECT k.id AS id, k.phrase AS keyword, w.word AS category_word,
                       SUM(pk.hits) AS hits, COUNT(*) AS files
                FROM path p
                JOIN path_keyword pk ON pk.path_id = p.id
                JOIN keyword k ON k.id = pk.keyword_id
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                WHERE p.source_id = ?
                GROUP BY k.id, k.phrase, w.word ORDER BY hits DESC""", sourceId);
    }

    /** Categories attached to files from one aspect. */
    public List<Row> selectCategoriesForAspect(int aspectId) throws SQLException {
        return joinedTerms("""
                SELECT c.id AS id, w.word AS word, COUNT(*) AS files
                FROM path p
                JOIN path_category pc ON pc.path_id = p.id
                JOIN category c ON c.id = pc.category_id
                JOIN word w ON w.id = c.word_id
                WHERE p.aspect_id = ?
                GROUP BY c.id, w.word ORDER BY files DESC""", aspectId);
    }

    /** Keywords found in files from one aspect. */
    public List<Row> selectKeywordsForAspect(int aspectId) throws SQLException {
        return joinedTerms("""
                SELECT k.id AS id, k.phrase AS keyword, w.word AS category_word,
                       SUM(pk.hits) AS hits, COUNT(*) AS files
                FROM path p
                JOIN path_keyword pk ON pk.path_id = p.id
                JOIN keyword k ON k.id = pk.keyword_id
                JOIN category c ON c.id = k.category_id
                JOIN word w ON w.id = c.word_id
                WHERE p.aspect_id = ?
                GROUP BY k.id, k.phrase, w.word ORDER BY hits DESC""", aspectId);
    }

    /** Files belonging to one category, for the category drill-through. */
    public List<Row> selectPathsForCategory(int categoryId, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.*, s.name AS source_name, a.name AS aspect_name,
                       h.hash_value AS hash_value
                FROM path_category pc
                JOIN path p ON p.id = pc.path_id
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                LEFT JOIN hash h ON h.id = p.hash_id
                WHERE pc.category_id = ? ORDER BY p.file_name LIMIT ?""")) {
            ps.setInt(1, categoryId);
            ps.setInt(2, limit);
            return all(ps);
        }
    }

    /** Categories a word belongs to, for the word detail destination. */
    public List<Row> selectCategoriesForWord(int wordId) throws SQLException {
        // Symmetric with WORDS_OF_CATEGORY: a category's naming word is one of its words.
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.id AS id, w.word AS word
                FROM category c
                JOIN word w ON w.id = c.word_id
                WHERE c.word_id = ?
                   OR EXISTS (SELECT 1 FROM word_category wc
                              WHERE wc.category_id = c.id AND wc.word_id = ?)
                ORDER BY w.word""")) {
            ps.setInt(1, wordId);
            ps.setInt(2, wordId);
            return all(ps);
        }
    }

    /** Every registered path, ordered by file path — input to the directory tree. */
    public List<Row> selectAllPathsForTree(Integer sourceId, Integer aspectId)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id AS id, p.file_name AS file_name, p.file_path AS file_path,
                       p.file_size AS file_size, p.file_type AS file_type,
                       p.file_status AS file_status, p.element_id AS element_id,
                       s.name AS source_name, a.name AS aspect_name
                FROM path p
                LEFT JOIN source s ON s.id = p.source_id
                LEFT JOIN aspect a ON a.id = p.aspect_id
                WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (sourceId != null) { sql.append(" AND p.source_id = ?"); args.add(sourceId); }
        if (aspectId != null) { sql.append(" AND p.aspect_id = ?"); args.add(aspectId); }
        sql.append(" ORDER BY p.file_path");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            return all(ps);
        }
    }

    /**
     * Combined-filter counts for the comprehensive dashboard.
     *
     * <p>Any argument may be null, meaning "no restriction on that dimension".
     */
    public Row countFiltered(Integer sourceId, Integer aspectId, Integer categoryId,
                             String fileType) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT p.id) AS files,
                       IFNULL(SUM(DISTINCT p.file_size),0) AS bytes
                FROM path p""");
        if (categoryId != null) {
            sql.append(" JOIN path_category pc ON pc.path_id = p.id AND pc.category_id = ?");
        }
        sql.append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (categoryId != null) { args.add(categoryId); }
        if (sourceId != null) { sql.append(" AND p.source_id = ?"); args.add(sourceId); }
        if (aspectId != null) { sql.append(" AND p.aspect_id = ?"); args.add(aspectId); }
        if (fileType != null && !fileType.isBlank()) {
            sql.append(" AND p.file_type = ?");
            args.add(fileType);
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            return single(ps);
        }
    }

    /** Read/unread split, for dashboards. */
    public Map<String, Integer> countByReviewState() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT file_status, COUNT(*) FROM path GROUP BY file_status")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    private Map<String, Integer> countScoped(String sql, int id) throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
            }
        }
        return out;
    }

    private List<Row> joinedTerms(String sql, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return all(ps);
        }
    }

    // ==================== batch analysis runs ====================

    public int insertBatchRun(String template, String priority, String errorHandling,
                              Integer sourceId, Integer aspectId, String fileType,
                              int selected, Instant startedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO batch_run (template, priority, error_handling, source_id,
                                       aspect_id, file_type, state, selected, started_at)
                VALUES (?,?,?,?,?,?,'Running',?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, template);
            ps.setString(2, priority);
            ps.setString(3, errorHandling);
            if (sourceId == null) { ps.setNull(4, Types.INTEGER); } else { ps.setInt(4, sourceId); }
            if (aspectId == null) { ps.setNull(5, Types.INTEGER); } else { ps.setInt(5, aspectId); }
            ps.setString(6, fileType);
            ps.setInt(7, selected);
            ps.setLong(8, startedAt.toEpochMilli());
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public boolean finishBatchRun(int runId, String state, int completed, int failed,
                                  Instant finishedAt, long millis, String note)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE batch_run SET state=?, completed=?, failed=?, finished_at=?,
                                     millis=?, note=? WHERE id=?""")) {
            ps.setString(1, state);
            ps.setInt(2, completed);
            ps.setInt(3, failed);
            ps.setLong(4, finishedAt.toEpochMilli());
            ps.setLong(5, millis);
            ps.setString(6, note);
            ps.setInt(7, runId);
            return ps.executeUpdate() > 0;
        }
    }

    public void insertBatchItem(int runId, int pathId, String outcome, String detail,
                                long millis) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO batch_run_item (run_id, path_id, outcome, detail, millis)
                VALUES (?,?,?,?,?)""")) {
            ps.setInt(1, runId);
            ps.setInt(2, pathId);
            ps.setString(3, outcome);
            ps.setString(4, detail);
            ps.setLong(5, millis);
            ps.executeUpdate();
        }
    }

    public List<Row> selectBatchRuns(int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT b.*, s.name AS source_name, a.name AS aspect_name
                FROM batch_run b
                LEFT JOIN source s ON s.id = b.source_id
                LEFT JOIN aspect a ON a.id = b.aspect_id
                ORDER BY b.started_at DESC LIMIT ?""")) {
            ps.setInt(1, limit);
            return all(ps);
        }
    }

    public Row selectBatchRun(int runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT b.*, s.name AS source_name, a.name AS aspect_name
                FROM batch_run b
                LEFT JOIN source s ON s.id = b.source_id
                LEFT JOIN aspect a ON a.id = b.aspect_id
                WHERE b.id = ?""")) {
            ps.setInt(1, runId);
            return single(ps);
        }
    }

    /** Per-file outcomes for one run, worst first so failures surface. */
    public List<Row> selectBatchItems(int runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT bi.*, p.file_name AS file_name, p.file_type AS file_type,
                       p.file_size AS file_size
                FROM batch_run_item bi
                JOIN path p ON p.id = bi.path_id
                WHERE bi.run_id = ?
                ORDER BY CASE bi.outcome WHEN 'Failed' THEN 0 ELSE 1 END, p.file_name""")) {
            ps.setInt(1, runId);
            return all(ps);
        }
    }

    public int countBatchRuns() throws SQLException {
        return scalar("SELECT COUNT(*) FROM batch_run");
    }

    public boolean deleteBatchRun(int runId) throws SQLException {
        return executeUpdate("DELETE FROM batch_run WHERE id=?", runId) > 0;
    }

    // ==================== plumbing ====================

    /**
     * A single result row as a name-keyed map. Chosen so the DAO stays small and the
     * facade owns all DTO construction; nothing outside this package sees {@code Row}
     * in a signature that matters for parity.
     */
    public static final class Row {
        private final Map<String, Object> values;

        Row(Map<String, Object> values) {
            this.values = values;
        }

        public String str(String key) {
            Object v = values.get(key);
            return v == null ? null : String.valueOf(v);
        }

        public int i(String key) {
            Object v = values.get(key);
            return v == null ? 0 : ((Number) v).intValue();
        }

        public Integer boxed(String key) {
            Object v = values.get(key);
            return v == null ? null : ((Number) v).intValue();
        }

        public long l(String key) {
            Object v = values.get(key);
            return v == null ? 0L : ((Number) v).longValue();
        }

        public double d(String key) {
            Object v = values.get(key);
            return v == null ? 0d : ((Number) v).doubleValue();
        }

        public boolean bool(String key) {
            Object v = values.get(key);
            return v != null && ((Number) v).intValue() != 0;
        }

        public Instant instant(String key) {
            Object v = values.get(key);
            return v == null ? null : Instant.ofEpochMilli(((Number) v).longValue());
        }

        public LocalDate date(String key) {
            String s = str(key);
            return (s == null || s.isBlank()) ? null : LocalDate.parse(s);
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        int n = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= n; i++) {
            m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return new Row(m);
    }

    private static Row single(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? row(rs) : null;
        }
    }

    private static List<Row> all(PreparedStatement ps) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(row(rs));
            }
        }
        return out;
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }

    private static int generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            return keys.next() ? keys.getInt(1) : -1;
        }
    }

    private int executeUpdate(String sql, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate();
        }
    }

    private int executeUpdate(String sql, int a, int b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, a);
            ps.setInt(2, b);
            return ps.executeUpdate();
        }
    }

    private boolean exists(String sql, String arg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int scalar(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

}
