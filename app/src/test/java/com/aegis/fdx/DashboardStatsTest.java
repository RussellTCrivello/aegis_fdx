package com.aegis.fdx;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.CaseDatabase;
import com.aegis.fdx.store.DashboardStats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.1 / §30: the derived dashboard counters must stay equal to a fresh scan of the
 * authoritative {@code item} table across every state transition the application can
 * put an item through — not merely after a clean insert.
 */
final class DashboardStatsTest {

    private static Item item(String id, String ext, String status, long size) {
        Item it = new Item(id, id + "." + ext);
        it.extension(ext);
        it.mediaType("application/" + ext);
        it.size(size);
        it.custodian("A. Farouk");
        it.status(ItemStatus.valueOf(status));
        return it;
    }

    @Test
    @DisplayName("empty case: every counter is zero and the dashboard still reads")
    void emptyCase(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            DashboardStats stats = new DashboardStats(db);
            DashboardStats.Snapshot s = stats.snapshot();
            assertEquals(0, s.totalItems());
            assertEquals(0, s.totalBytes());
            assertTrue(s.byStatus().isEmpty());
            assertTrue(stats.verify(), "empty derived counters must match an empty scan");
        }
    }

    @Test
    @DisplayName("one item: counters land in every dimension")
    void singleItem(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            db.save(item("E-1", "pdf", "INDEXED", 2048));
            DashboardStats stats = new DashboardStats(db);
            DashboardStats.Snapshot s = stats.snapshot();
            assertEquals(1, s.totalItems());
            assertEquals(2048, s.totalBytes());
            assertEquals(1L, s.byStatus().get("INDEXED"));
            assertEquals(1L, s.byExtension().get("pdf"));
            assertEquals(1L, s.byCustodian().get("A. Farouk"));
            assertTrue(stats.verify());
        }
    }

    @Test
    @DisplayName("re-saving an item moves it between buckets without double counting")
    void statusTransitionIsNotDoubleCounted(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            DashboardStats stats = new DashboardStats(db);

            db.save(item("E-1", "pdf", "PENDING", 1000));
            assertEquals(1L, stats.snapshot().byStatus().get("PENDING"));

            // The pipeline re-saves the same id as it advances; this is an UPSERT, so
            // the UPDATE trigger must retire the old bucket as it credits the new one.
            db.save(item("E-1", "pdf", "INDEXED", 1000));
            DashboardStats.Snapshot s = stats.snapshot();
            assertEquals(1, s.totalItems(), "one item must still be one item");
            assertEquals(1000, s.totalBytes());
            assertEquals(null, s.byStatus().get("PENDING"));
            assertEquals(1L, s.byStatus().get("INDEXED"));
            assertTrue(stats.verify());
        }
    }

    @Test
    @DisplayName("a size change is reflected in total bytes, not added to it")
    void sizeChangeAdjustsBytes(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            DashboardStats stats = new DashboardStats(db);
            db.save(item("E-1", "pdf", "INDEXED", 1000));
            db.save(item("E-1", "pdf", "INDEXED", 4000));
            assertEquals(4000, stats.snapshot().totalBytes());
            assertTrue(stats.verify());
        }
    }

    @Test
    @DisplayName("OCR completion moves an item from pending to applied")
    void ocrStateTransition(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            DashboardStats stats = new DashboardStats(db);
            Item it = item("E-1", "jpg", "INDEXED", 500);
            it.needsOcr(true);
            db.save(it);
            assertEquals(1L, stats.snapshot().byOcrState().get("pending"));

            it.ocrApplied(true);
            db.save(it);
            DashboardStats.Snapshot s = stats.snapshot();
            assertEquals(1L, s.byOcrState().get("applied"));
            assertEquals(null, s.byOcrState().get("pending"));
            assertTrue(stats.verify());
        }
    }

    @Test
    @DisplayName("an error recorded and then cleared by a retry is counted correctly")
    void errorThenSuccessfulRetry(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            DashboardStats stats = new DashboardStats(db);
            Item it = item("E-1", "zip", "ERROR", 900);
            it.errors().add("truncated archive");
            db.save(it);
            assertEquals(1, stats.snapshot().errors());

            Item retried = item("E-1", "zip", "INDEXED", 900);   // no errors this time
            db.save(retried);
            DashboardStats.Snapshot s = stats.snapshot();
            assertEquals(0, s.errors(), "a cleared error must leave the error bucket");
            assertEquals(1, s.totalItems());
            assertTrue(stats.verify());
        }
    }

    @Test
    @DisplayName("deletion decrements every dimension")
    void deletionDecrements(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            DashboardStats stats = new DashboardStats(db);
            db.save(item("E-1", "pdf", "INDEXED", 1000));
            db.save(item("E-2", "pdf", "INDEXED", 2000));
            try (Statement st = db.connection().createStatement()) {
                st.executeUpdate("DELETE FROM item WHERE id='E-1'");
            }
            DashboardStats.Snapshot s = stats.snapshot();
            assertEquals(1, s.totalItems());
            assertEquals(2000, s.totalBytes());
            assertTrue(stats.verify());
        }
    }

    @Test
    @DisplayName("null and empty fields are bucketed, never dropped")
    void nullFieldsAreBucketed(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Item it = new Item("E-1", "no-extension");
            it.status(ItemStatus.UNSUPPORTED);
            it.size(10);
            db.save(it);                                  // ext, media, custodian all null
            DashboardStats stats = new DashboardStats(db);
            DashboardStats.Snapshot s = stats.snapshot();
            assertEquals(1, s.totalItems());
            assertEquals(1L, s.byExtension().get(DashboardStats.NONE));
            assertEquals(1L, s.byCustodian().get(DashboardStats.NONE));
            assertTrue(stats.verify());
        }
    }

    @Test
    @DisplayName("rollback leaves no counter behind")
    void rolledBackBatchLeavesNoCounters(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            DashboardStats stats = new DashboardStats(db);
            db.save(item("E-1", "pdf", "INDEXED", 1000));

            db.begin();
            db.save(item("E-2", "pdf", "INDEXED", 5000));
            db.save(item("E-3", "pdf", "INDEXED", 5000));
            db.rollback();

            DashboardStats.Snapshot s = stats.snapshot();
            assertEquals(1, s.totalItems(), "counters must roll back with their rows");
            assertEquals(1000, s.totalBytes());
            assertTrue(stats.verify());
        }
    }

    @Test
    @DisplayName("rebuild reconstructs counters deterministically from authoritative data")
    void rebuildIsDeterministic(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            Random rnd = new Random(7);
            db.begin();
            for (int i = 0; i < 500; i++) {
                db.save(item("E-" + i, i % 3 == 0 ? "pdf" : "docx",
                        i % 7 == 0 ? "ERROR" : "INDEXED", 100 + i));
            }
            db.commit();

            DashboardStats stats = new DashboardStats(db);
            DashboardStats.Snapshot before = stats.snapshot();

            // Corrupt the derived table deliberately, exactly as a botched migration or
            // a hand-edited database would.
            try (Statement st = db.connection().createStatement()) {
                st.executeUpdate("UPDATE dashboard_stats SET items = items * 3 + 11");
            }
            assertTrue(!stats.verify(), "verify must notice corrupted counters");

            stats.rebuild();
            assertTrue(stats.verify(), "rebuild must restore agreement");
            assertEquals(before.totalItems(), stats.snapshot().totalItems());
            assertEquals(before.byExtension(), stats.snapshot().byExtension());
            assertNotNull(stats.rebuiltAt());

            // Rebuilding twice must not change anything.
            stats.rebuild();
            assertEquals(before.byStatus(), stats.snapshot().byStatus());
        }
    }

    @Test
    @DisplayName("counters survive closing and reopening the case")
    void countersSurviveReopen(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("case.db");
        try (CaseDatabase db = new CaseDatabase(file)) {
            db.begin();
            for (int i = 0; i < 100; i++) db.save(item("E-" + i, "pdf", "INDEXED", 1000));
            db.commit();
        }
        try (CaseDatabase db = new CaseDatabase(file)) {
            DashboardStats stats = new DashboardStats(db);
            assertEquals(100, stats.snapshot().totalItems());
            assertTrue(stats.verify(), "counters must still agree after reopening");
        }
    }

    @Test
    @DisplayName("counters agree with a scan over a large mixed case")
    void largeMixedCase(@TempDir Path dir) throws Exception {
        try (CaseDatabase db = new CaseDatabase(dir.resolve("case.db"))) {
            String[] exts = {"pdf", "docx", "jpg", "zip", "", null};
            String[] states = {"INDEXED", "ERROR", "LOCKED", "UNSUPPORTED", "PENDING"};
            Random rnd = new Random(11);
            db.begin();
            for (int i = 0; i < 5000; i++) {
                Item it = item("E-" + i, "pdf", states[rnd.nextInt(states.length)], rnd.nextInt(9999));
                it.extension(exts[rnd.nextInt(exts.length)]);
                it.needsOcr(rnd.nextBoolean());
                it.ocrApplied(it.needsOcr() && rnd.nextBoolean());
                it.duplicate(rnd.nextInt(10) == 0);
                if (rnd.nextInt(8) == 0) it.errors().add("failed");
                db.save(it);
            }
            db.commit();

            DashboardStats stats = new DashboardStats(db);
            assertTrue(stats.verify(), "derived counters must equal a scan of 5,000 mixed rows");

            Map<ItemStatus, Integer> authoritative = db.statusCounts();
            Map<String, Long> derived = stats.snapshot().byStatus();
            for (Map.Entry<ItemStatus, Integer> e : authoritative.entrySet()) {
                long d = derived.getOrDefault(e.getKey().name(), 0L);
                assertEquals(e.getValue().longValue(), d,
                        "status " + e.getKey() + " must agree with the authoritative count");
            }
        }
    }
}
