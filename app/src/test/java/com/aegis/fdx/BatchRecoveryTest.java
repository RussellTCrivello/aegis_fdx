package com.aegis.fdx;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.DashboardStats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §28: batching database writes must not weaken crash recovery.
 *
 * <p>The property that matters is not "no work is ever lost" — a crash always loses the
 * batch in flight — but that what survives is <em>consistent</em>: an item row and the
 * queue row that describes its progress are committed together, so recovery never sees
 * an item the queue thinks was never started, nor a queue entry marked done for an item
 * that was never written.
 */
final class BatchRecoveryTest {

    private static Item item(String id, ItemStatus status) {
        Item it = new Item(id, id + ".pdf");
        it.extension("pdf");
        it.size(1024);
        it.status(status);
        it.sourcePath("/evidence/" + id + ".pdf");
        return it;
    }

    /** Writes {@code n} items with their queue rows, in batches, as ingest does. */
    private static void ingestBatched(CaseDatabase db, int from, int to, int batch)
            throws Exception {
        int since = 0;
        db.begin();
        for (int i = from; i < to; i++) {
            String id = "E-" + i;
            db.enqueue(id, "/evidence/" + id + ".pdf", null, 0);
            db.setQueueState(id, "PROCESSING");
            db.save(item(id, ItemStatus.INDEXED));
            db.setQueueState(id, "DONE");
            if (++since >= batch) {
                db.commit();
                db.begin();
                since = 0;
            }
        }
        db.commit();
    }

    @Test
    @DisplayName("an interrupted batch leaves item and queue rows consistent")
    void interruptedBatchIsAtomic(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("case.db");
        try (CaseDatabase db = new CaseDatabase(file)) {
            ingestBatched(db, 0, 50, 10);           // five committed batches

            // A sixth batch begins and the process dies before it commits.
            db.begin();
            for (int i = 50; i < 57; i++) {
                String id = "E-" + i;
                db.enqueue(id, "/evidence/" + id + ".pdf", null, 0);
                db.save(item(id, ItemStatus.INDEXED));
            }
            db.rollback();                          // stands in for the crash
        }

        // Reopen, exactly as a restart would.
        try (CaseDatabase db = new CaseDatabase(file)) {
            assertEquals(50, db.count(), "only committed batches may survive");

            // No queue row may reference an item that was never written, and no item
            // may exist without its queue row.
            assertEquals(0, orphanQueueRows(db), "queue rows must not outlive their items");
            assertEquals(0, orphanItems(db), "items must not exist without a queue row");

            // The derived counters must agree with what actually survived.
            DashboardStats stats = new DashboardStats(db);
            assertEquals(50, stats.snapshot().totalItems());
            assertTrue(stats.verify(), "counters must match the surviving rows");
        }
    }

    @Test
    @DisplayName("work lost to an interrupted batch is re-offered by the queue")
    void lostBatchIsResumable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("case.db");
        try (CaseDatabase db = new CaseDatabase(file)) {
            ingestBatched(db, 0, 20, 10);

            // A batch that got as far as PROCESSING and then died.
            db.begin();
            db.enqueue("E-20", "/evidence/E-20.pdf", null, 0);
            db.setQueueState("E-20", "PROCESSING");
            db.rollback();
        }
        try (CaseDatabase db = new CaseDatabase(file)) {
            // The file must look un-started, so intake picks it up again.
            assertEquals(null, db.completedIdForSource("/evidence/E-20.pdf"),
                    "an uncommitted element must not look complete");
            assertFalse(db.isDone("E-20"));
            // And the twenty that did commit must not be re-run.
            assertEquals("E-19", db.completedIdForSource("/evidence/E-19.pdf"));
        }
    }

    @Test
    @DisplayName("committed batches survive an abrupt close with WAL")
    void committedBatchesSurviveAbruptClose(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("case.db");
        CaseDatabase db = new CaseDatabase(file);
        ingestBatched(db, 0, 100, 25);
        // Abandon the connection without close(): WAL must still hold the commits.
        db = null;
        System.gc();

        try (CaseDatabase reopened = new CaseDatabase(file)) {
            assertEquals(100, reopened.count());
            assertTrue(new DashboardStats(reopened).verify());
        }
    }

    @Test
    @DisplayName("WAL and the tuned pragmas are actually in force at runtime")
    void pragmasAreInForce(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            var p = db.runtimePragmas();
            assertEquals("wal", p.get("journal_mode").toLowerCase());
            assertEquals("1", p.get("synchronous"), "NORMAL");
            assertEquals("1", p.get("foreign_keys"));
            assertTrue(Integer.parseInt(p.get("busy_timeout")) >= 5000);
            assertTrue(Integer.parseInt(p.get("cache_size")) < 0,
                    "cache_size must be expressed in KiB, not pages");
            assertTrue(Long.parseLong(p.get("mmap_size")) > 0);
            assertEquals("2", p.get("temp_store"), "MEMORY");
        }
    }

    @Test
    @DisplayName("a lower-resource profile can be configured")
    void configurableForSmallMachines(@TempDir Path dir) throws Exception {
        System.setProperty("aegis.db.cacheKb", "2048");
        System.setProperty("aegis.db.mmapBytes", "0");
        try (CaseDatabase db = new CaseDatabase(dir.resolve("small.db"))) {
            var p = db.runtimePragmas();
            assertEquals("-2048", p.get("cache_size"));
            assertEquals("0", p.get("mmap_size"), "mmap must be disableable");
        } finally {
            System.clearProperty("aegis.db.cacheKb");
            System.clearProperty("aegis.db.mmapBytes");
        }
    }

    private static int orphanQueueRows(CaseDatabase db) throws Exception {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM queue q WHERE q.state='DONE'"
                             + " AND NOT EXISTS (SELECT 1 FROM item i WHERE i.id=q.item_id)")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static int orphanItems(CaseDatabase db) throws Exception {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM item i"
                             + " WHERE NOT EXISTS (SELECT 1 FROM queue q WHERE q.item_id=i.id)")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
