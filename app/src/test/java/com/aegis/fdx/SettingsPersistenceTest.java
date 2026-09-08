package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A setting an examiner chose has to still be in force tomorrow.
 *
 * <p>Before this suite existed, the Settings destination wrote to an in-memory object:
 * the controls were real — the next ingest run genuinely used the new worker count —
 * but closing the application discarded the choice, which is the kind of defect that
 * only shows up as "I'm sure I turned that on". These checks assert the whole round
 * trip: change, write, reopen, and the value is back; and that a damaged or absent file
 * degrades to the current values rather than resetting a case.
 */
public final class SettingsPersistenceTest {

    @Test
    @DisplayName("Processing options survive a restart")
    void optionsSurviveRestart() throws Exception {
        Path dir = work("roundtrip");
        Path file = dir.resolve(CaseSettings.FILE_NAME);

        CaseSettings session1 = new CaseSettings();
        session1.ocrEnabled(false);
        session1.dedupeScope(CaseSettings.DedupeScope.GLOBAL);
        session1.maxArchiveDepth(7);
        session1.workers(3);
        session1.indexMemoryMb(512);
        session1.saveTo(file);
        assertTrue(Files.isRegularFile(file), "settings must be written into the case folder");

        // A new run of the application, opening the same case.
        CaseSettings session2 = new CaseSettings();
        assertTrue(session2.loadFrom(file), "the settings file must be found and read");
        assertFalse(session2.ocrEnabled(), "OCR choice survived the restart");
        assertEquals(CaseSettings.DedupeScope.GLOBAL, session2.dedupeScope());
        assertEquals(7, session2.maxArchiveDepth());
        assertEquals(3, session2.workers());
        assertEquals(512, session2.indexMemoryMb());
    }

    @Test
    @DisplayName("A case that was never configured keeps its defaults")
    void missingFileKeepsDefaults() throws Exception {
        Path file = work("missing").resolve(CaseSettings.FILE_NAME);
        Files.deleteIfExists(file);
        CaseSettings s = new CaseSettings();
        boolean before = s.ocrEnabled();
        int workers = s.workers();
        assertFalse(s.loadFrom(file), "no file means nothing was loaded");
        assertEquals(before, s.ocrEnabled(), "defaults are untouched");
        assertEquals(workers, s.workers(), "defaults are untouched");
    }

    @Test
    @DisplayName("A damaged settings file cannot reset or break a case")
    void damagedFileIsIgnoredValueByValue() throws Exception {
        Path file = work("damaged").resolve(CaseSettings.FILE_NAME);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n",
                "ocr.enabled=not-a-boolean",
                "dedupe.scope=NOT_A_SCOPE",
                "archive.maxDepth=99999",
                "processing.workers=4",
                "index.memoryMb=oops"));

        CaseSettings s = new CaseSettings();
        s.ocrEnabled(true);
        s.dedupeScope(CaseSettings.DedupeScope.PER_CUSTODIAN);
        s.maxArchiveDepth(20);
        s.indexMemoryMb(4096);

        assertTrue(s.loadFrom(file), "a readable file is read even when values are bad");
        assertTrue(s.ocrEnabled(), "an unparseable boolean keeps the current value");
        assertEquals(CaseSettings.DedupeScope.PER_CUSTODIAN, s.dedupeScope(),
                "an unknown enum keeps the current value");
        assertEquals(20, s.maxArchiveDepth(), "an out-of-range depth keeps the current value");
        assertEquals(4096, s.indexMemoryMb(), "an unparseable number keeps the current value");
        assertEquals(4, s.workers(), "the one valid value is still applied");
    }

    @Test
    @DisplayName("Container passwords are never written to disk")
    void passwordsAreNotPersisted() throws Exception {
        Path file = work("secrets").resolve(CaseSettings.FILE_NAME);
        CaseSettings s = new CaseSettings();
        s.passwords().add("correct-horse-battery-staple");
        s.saveTo(file);
        String written = Files.readString(file);
        assertFalse(written.contains("correct-horse-battery-staple"),
                "a session password must not be persisted with the case settings");
    }

    private static Path work(String name) throws Exception {
        Path p = Path.of(System.getProperty("java.io.tmpdir"), "aegis-settings", name);
        Files.createDirectories(p);
        return p;
    }

    // ---- offline runner ---------------------------------------------------

    /** Runs the same assertions without a JUnit launcher, for the offline battery. */
    public static void main(String[] args) {
        SettingsPersistenceTest t = new SettingsPersistenceTest();
        String[] names = {
            "processing options survive a restart",
            "a case that was never configured keeps its defaults",
            "a damaged settings file cannot reset or break a case",
            "container passwords are never written to disk",
        };
        Check[] body = {
            t::optionsSurviveRestart,
            t::missingFileKeepsDefaults,
            t::damagedFileIsIgnoredValueByValue,
            t::passwordsAreNotPersisted,
        };
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < body.length; i++) {
            try {
                body[i].run();
                System.out.println("  ok    " + names[i]);
                passed++;
            } catch (Throwable e) {
                System.out.println("  FAIL  " + names[i] + " — " + e);
                failed++;
            }
        }
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface Check {
        void run() throws Exception;
    }
}
