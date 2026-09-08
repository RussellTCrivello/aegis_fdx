package com.aegis.fdx.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema for the five integrated concepts: Sources, Aspects, Categories, Keywords and
 * Contents (plus the supporting hashes, paths, notifications and search tables).
 *
 * <p>These tables live in the <strong>same</strong> SQLite file as the forensic schema
 * ({@code <case>/db/case.db}), created on the same connection. That is deliberate: it
 * means a single transaction can span both halves, and {@code paths.element_id} is a
 * real foreign key onto {@code item(id)} rather than a loose string reference — so the
 * new concepts participate in the existing workflows instead of sitting in an isolated
 * store.
 *
 * <p>Kept in its own class purely so the forensic DDL in {@link CaseDatabase} stays
 * readable; it is not a separate database.
 */
final class CorpusSchema {

    private CorpusSchema() {
    }

    static void migrate(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {

            // ---- Sources -------------------------------------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS source (
                  id            INTEGER PRIMARY KEY AUTOINCREMENT,
                  name          TEXT    NOT NULL UNIQUE,
                  job           TEXT    NOT NULL DEFAULT '',
                  importance    REAL    NOT NULL DEFAULT 0.5,
                  country       TEXT    NOT NULL DEFAULT '',
                  city          TEXT,
                  description   TEXT,
                  accounts      TEXT,
                  note          TEXT,
                  attachments   TEXT,
                  ownership     TEXT,
                  access_status TEXT,
                  entry_date    TEXT,
                  category_id   INTEGER,
                  FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE SET NULL
                )""");

            // ---- Aspects -------------------------------------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS aspect (
                  id            INTEGER PRIMARY KEY AUTOINCREMENT,
                  name          TEXT    NOT NULL UNIQUE,
                  importance    REAL    NOT NULL DEFAULT 0.5,
                  date_creation TEXT    NOT NULL
                )""");

            // ---- Words / Categories / Keywords ---------------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS word (
                  id   INTEGER PRIMARY KEY AUTOINCREMENT,
                  word TEXT NOT NULL UNIQUE
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS category (
                  id      INTEGER PRIMARY KEY AUTOINCREMENT,
                  word_id INTEGER NOT NULL UNIQUE,
                  FOREIGN KEY (word_id) REFERENCES word(id) ON DELETE CASCADE
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS keyword (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  phrase      TEXT    NOT NULL UNIQUE,
                  category_id INTEGER NOT NULL,
                  FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS word_category (
                  word_id     INTEGER NOT NULL,
                  category_id INTEGER NOT NULL,
                  PRIMARY KEY (word_id, category_id),
                  FOREIGN KEY (word_id)     REFERENCES word(id)     ON DELETE CASCADE,
                  FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE
                )""");

            // ---- Hashes --------------------------------------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS hash (
                  id         INTEGER PRIMARY KEY AUTOINCREMENT,
                  hash_value TEXT    NOT NULL,
                  source_id  INTEGER,
                  UNIQUE (hash_value, source_id),
                  FOREIGN KEY (source_id) REFERENCES source(id) ON DELETE SET NULL
                )""");

            // ---- Paths: the join between the new concepts and the engine --
            // element_id is a genuine FK onto the forensic item table, which is only
            // possible because both schemas share one database file.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS path (
                  id            INTEGER PRIMARY KEY AUTOINCREMENT,
                  file_name     TEXT    NOT NULL,
                  file_path     TEXT    NOT NULL,
                  file_size     INTEGER NOT NULL CHECK (file_size >= 0),
                  file_type     TEXT    NOT NULL,
                  file_status   TEXT    NOT NULL DEFAULT 'Unread'
                                  CHECK (file_status IN ('Read','Unread')),
                  file_date     TEXT    NOT NULL,
                  date_creation TEXT    NOT NULL,
                  hash_id       INTEGER,
                  coordinates   TEXT,
                  source_id     INTEGER,
                  aspect_id     INTEGER,
                  element_id    TEXT    UNIQUE,
                  FOREIGN KEY (hash_id)    REFERENCES hash(id)   ON DELETE SET NULL,
                  FOREIGN KEY (source_id)  REFERENCES source(id) ON DELETE SET NULL,
                  FOREIGN KEY (aspect_id)  REFERENCES aspect(id) ON DELETE SET NULL,
                  FOREIGN KEY (element_id) REFERENCES item(id)   ON DELETE CASCADE
                )""");

            // ---- Contents ------------------------------------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS content (
                  id           INTEGER PRIMARY KEY AUTOINCREMENT,
                  content_data TEXT,
                  content_date TEXT,
                  path_id      INTEGER NOT NULL,
                  FOREIGN KEY (path_id) REFERENCES path(id) ON DELETE CASCADE
                )""");

            // ---- Category/keyword attribution of a path -------------------
            // Lets a reviewer classify an ingested element directly.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS path_category (
                  path_id     INTEGER NOT NULL,
                  category_id INTEGER NOT NULL,
                  PRIMARY KEY (path_id, category_id),
                  FOREIGN KEY (path_id)     REFERENCES path(id)     ON DELETE CASCADE,
                  FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS path_keyword (
                  path_id    INTEGER NOT NULL,
                  keyword_id INTEGER NOT NULL,
                  hits       INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (path_id, keyword_id),
                  FOREIGN KEY (path_id)    REFERENCES path(id)    ON DELETE CASCADE,
                  FOREIGN KEY (keyword_id) REFERENCES keyword(id) ON DELETE CASCADE
                )""");

            // ---- Where a category word occurs -----------------------------
            // The reference model relates a file to a category through the
            // category's *words*: words_paths ⨝ words_categorys. This table is the
            // Java equivalent of words_paths, restricted to vocabulary the case
            // actually cares about — the words that belong to a category — rather
            // than every token of every document. Populated by analysis over the
            // stored text, so a count here always means "this word really occurs in
            // this file".
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS path_word (
                  path_id INTEGER NOT NULL,
                  word_id INTEGER NOT NULL,
                  hits    INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (path_id, word_id),
                  FOREIGN KEY (path_id) REFERENCES path(id) ON DELETE CASCADE,
                  FOREIGN KEY (word_id) REFERENCES word(id) ON DELETE CASCADE
                )""");

            // ---- Notifications & saved searches --------------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alert (
                  id         INTEGER PRIMARY KEY AUTOINCREMENT,
                  alert_type TEXT    NOT NULL,
                  priority   TEXT    NOT NULL DEFAULT 'normal',
                  title      TEXT    NOT NULL,
                  message    TEXT,
                  element_id TEXT,
                  is_read    INTEGER NOT NULL DEFAULT 0,
                  dismissed  INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  event_date INTEGER
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS search_history (
                  id           INTEGER PRIMARY KEY AUTOINCREMENT,
                  query        TEXT    NOT NULL,
                  result_count INTEGER NOT NULL DEFAULT 0,
                  user_id      TEXT,
                  searched_at  INTEGER NOT NULL
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS saved_search (
                  id         INTEGER PRIMARY KEY AUTOINCREMENT,
                  name       TEXT    NOT NULL,
                  query      TEXT    NOT NULL,
                  filters    TEXT,
                  user_id    TEXT,
                  created_at INTEGER NOT NULL,
                  last_used  INTEGER,
                  use_count  INTEGER NOT NULL DEFAULT 0
                )""");

            // ---- batch analysis runs and templates -----------------------
            // The reference keeps a history of analysis runs with per-run outcomes;
            // this is the persisted equivalent, in the same case database.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS batch_run (
                  id             INTEGER PRIMARY KEY AUTOINCREMENT,
                  template       TEXT    NOT NULL,
                  priority       TEXT    NOT NULL DEFAULT 'Medium',
                  error_handling TEXT    NOT NULL DEFAULT 'Skip Failed',
                  source_id      INTEGER,
                  aspect_id      INTEGER,
                  file_type      TEXT,
                  state          TEXT    NOT NULL DEFAULT 'Queued'
                                   CHECK (state IN ('Queued','Running','Completed',
                                                    'Failed','Cancelled')),
                  selected       INTEGER NOT NULL DEFAULT 0,
                  completed      INTEGER NOT NULL DEFAULT 0,
                  failed         INTEGER NOT NULL DEFAULT 0,
                  started_at     INTEGER NOT NULL,
                  finished_at    INTEGER,
                  millis         INTEGER NOT NULL DEFAULT 0,
                  note           TEXT,
                  FOREIGN KEY (source_id) REFERENCES source(id) ON DELETE SET NULL,
                  FOREIGN KEY (aspect_id) REFERENCES aspect(id) ON DELETE SET NULL
                )""");

            // Per-file outcome within a run, so a failed file can be found later.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS batch_run_item (
                  run_id     INTEGER NOT NULL,
                  path_id    INTEGER NOT NULL,
                  outcome    TEXT    NOT NULL,
                  detail     TEXT,
                  millis     INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (run_id, path_id),
                  FOREIGN KEY (run_id)  REFERENCES batch_run(id) ON DELETE CASCADE,
                  FOREIGN KEY (path_id) REFERENCES path(id)      ON DELETE CASCADE
                )""");

            // ---- indexes -------------------------------------------------
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_source_name ON source(name)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_aspect_name ON aspect(name)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_word_word ON word(word)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_path_source ON path(source_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_path_aspect ON path(aspect_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_path_element ON path(element_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_path_type ON path(file_type)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_content_path ON content(path_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_alert_read ON alert(is_read)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_batch_state ON batch_run(state)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_batch_started ON batch_run(started_at DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_path_word_word ON path_word(word_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_path_word_path ON path_word(path_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_path_keyword_kw ON path_keyword(keyword_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_path_category_cat ON path_category(category_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_word_category_cat ON word_category(category_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_keyword_category ON keyword(category_id)");
        }
    }
}
