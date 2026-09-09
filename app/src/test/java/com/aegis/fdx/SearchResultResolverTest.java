package com.aegis.fdx;

import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.SearchResultDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit 5: a search result opens the exact registered file behind it.
 *
 * <p>The index answers which forensic <em>items</em> match; the registry answers which
 * <em>file records</em> those items are. {@code getPathByElementId} is the bridge, and
 * every test here pins one leg of it: distinct records that share a name, records that
 * share content, Unicode names, archive children with derived element ids, and every
 * failure mode the interface must surface instead of crashing on.
 */
class SearchResultResolverTest {

    private LiveCase openCase(Path root) throws Exception {
        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);
        return new LiveCase(root.resolve("case"), "Resolver", s);
    }

    private record Seeded(AegisFacades f, int sourceId, int aspectId, Path evidence) {
    }

    /**
     * Evidence layout: duplicate names in different folders, duplicate content under
     * different names, a Unicode name, a nested folder, and a ZIP whose member becomes
     * an extracted child with a derived element id.
     */
    private Seeded seed(LiveCase c, Path tmp) throws Exception {
        Path ev = tmp.resolve("evidence");
        Files.createDirectories(ev.resolve("a"));
        Files.createDirectories(ev.resolve("b"));
        Files.createDirectories(ev.resolve("notes"));
        Files.writeString(ev.resolve("a").resolve("report.txt"),
                "Alpha report about the Rotterdam harbor contract.", StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("b").resolve("report.txt"),
                "Beta report about the Antwerp harbor contract.", StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("same-one.txt"),
                "Identical bytes in two records.", StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("same-two.txt"),
                "Identical bytes in two records.", StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("données-日本語-דוח.txt"),
                "Unicode filename, accented and CJK and Hebrew content שלום.",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("notes").resolve("plain.txt"),
                "Nothing of interest here.", StandardCharsets.UTF_8);
        try (ZipOutputStream z = new ZipOutputStream(
                Files.newOutputStream(ev.resolve("bundle.zip")))) {
            z.putNextEntry(new ZipEntry("inner-member.txt"));
            z.write("Zipped member with the Rotterdam keyword.".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }

        AegisFacades f = AegisFacades.open(c);
        int src = f.sources().createSource(
                new SourceDraft("Acme", "NL", "custodian", 0.8).city("Amsterdam"));
        int asp = f.aspects().createAspect("Plaintiff", 0.9);
        f.processing("Acme", "Plaintiff").processFolder(ev.toString());
        f.contents().registerIngestedItems(src, asp);
        return new Seeded(f, src, asp, ev);
    }

    private static List<PathDto> all(AegisFacades f) {
        return new ArrayList<>(f.contents().getPaths().results());
    }

    private static PathDto named(List<PathDto> rows, String fileName) {
        List<PathDto> hits = new ArrayList<>();
        for (PathDto p : rows) {
            if (p.fileName().equals(fileName)) {
                hits.add(p);
            }
        }
        assertEquals(1, hits.size(), "expected exactly one record named " + fileName);
        return hits.get(0);
    }

    @Test
    @DisplayName("every registered file resolves back through its own element id")
    void roundTrip(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            List<PathDto> rows = all(s.f());
            assertTrue(rows.size() >= 7, "all evidence plus the zip member must register");
            for (PathDto p : rows) {
                assertNotNull(p.elementId(), p.fileName() + " must carry its element id");
                PathDto back = s.f().contents().getPathByElementId(p.elementId());
                assertEquals(p.id(), back.id());
                assertEquals(p.fileName(), back.fileName());
                assertEquals(p.filePath(), back.filePath());
            }
        }
    }

    @Test
    @DisplayName("a Lucene hit resolves to the indexed record, not a namesake")
    void luceneHitResolves(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            List<SearchResultDto> hits = s.f().search().search("Rotterdam").results();
            assertTrue(hits.size() >= 2, "both Rotterdam files must be found");
            for (SearchResultDto h : hits) {
                PathDto p = s.f().contents().getPathByElementId(h.id());
                assertEquals(h.id(), p.elementId());
            }
            // The two reports share a name; their element ids must still tell them apart.
            List<PathDto> reports = new ArrayList<>();
            for (PathDto p : all(s.f())) {
                if (p.fileName().equals("report.txt")) {
                    reports.add(p);
                }
            }
            assertEquals(2, reports.size());
            assertNotEquals(reports.get(0).id(), reports.get(1).id());
            assertNotEquals(reports.get(0).elementId(), reports.get(1).elementId());
            assertEquals(reports.get(0).id(), s.f().contents()
                    .getPathByElementId(reports.get(0).elementId()).id());
            assertEquals(reports.get(1).id(), s.f().contents()
                    .getPathByElementId(reports.get(1).elementId()).id());
        }
    }

    @Test
    @DisplayName("identical bytes in two records stay two records")
    void duplicateContent(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            PathDto one = named(all(s.f()), "same-one.txt");
            PathDto two = named(all(s.f()), "same-two.txt");
            assertNotEquals(one.id(), two.id());
            assertEquals(one.id(),
                    s.f().contents().getPathByElementId(one.elementId()).id());
            assertEquals(two.id(),
                    s.f().contents().getPathByElementId(two.elementId()).id());
        }
    }

    @Test
    @DisplayName("Unicode filenames resolve")
    void unicodeName(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            PathDto u = named(all(s.f()), "données-日本語-דוח.txt");
            PathDto back = s.f().contents().getPathByElementId(u.elementId());
            assertEquals(u.id(), back.id());
            assertEquals("données-日本語-דוח.txt", back.fileName());
        }
    }

    @Test
    @DisplayName("an extracted archive child resolves through its derived element id")
    void extractedChild(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            List<PathDto> children = new ArrayList<>();
            for (PathDto p : all(s.f())) {
                if (p.elementId() != null && p.elementId().contains("-E")) {
                    children.add(p);
                }
            }
            assertTrue(children.size() >= 1, "the zip member must register as a child");
            for (PathDto child : children) {
                PathDto back = s.f().contents().getPathByElementId(child.elementId());
                assertEquals(child.id(), back.id());
            }
        }
    }

    @Test
    @DisplayName("an unknown element id is NOT_FOUND, never a crash")
    void unknownElement(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            FacadeException e = assertThrows(FacadeException.class,
                    () -> s.f().contents().getPathByElementId("E-999999"));
            assertEquals(FacadeException.Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    @DisplayName("a blank element id is rejected as invalid input")
    void blankElement(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            FacadeException e = assertThrows(FacadeException.class,
                    () -> s.f().contents().getPathByElementId("  "));
            assertEquals(FacadeException.Kind.VALIDATION, e.kind());
        }
    }

    @Test
    @DisplayName("a deleted record resolves to NOT_FOUND")
    void deletedRecord(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            PathDto plain = named(all(s.f()), "plain.txt");
            assertTrue(s.f().contents().deletePath(plain.id()));
            FacadeException e = assertThrows(FacadeException.class,
                    () -> s.f().contents().getPathByElementId(plain.elementId()));
            assertEquals(FacadeException.Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    @DisplayName("resolution is database-backed: it survives the evidence file's removal")
    void survivesEvidenceDeletion(@TempDir Path tmp) throws Exception {
        try (LiveCase c = openCase(tmp)) {
            Seeded s = seed(c, tmp);
            PathDto plain = named(all(s.f()), "plain.txt");
            Files.delete(s.evidence().resolve("notes").resolve("plain.txt"));
            PathDto back = s.f().contents().getPathByElementId(plain.elementId());
            assertEquals(plain.id(), back.id());
        }
    }
}
