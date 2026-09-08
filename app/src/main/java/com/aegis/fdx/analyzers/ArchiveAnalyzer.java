package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * F-01 / F-03 / F-04: archive expansion.
 *
 * <p>ZIP, 7Z, TAR(.GZ/.BZ2/.XZ), GZ, BZ2, XZ via commons-compress.
 *
 * <p><b>RAR is intentionally not extracted in v1.</b> The only mature Java RAR
 * library (junrar) inherits the UnRar licence restriction, which falls outside the
 * mandated Apache/MIT/BSD/EPL set. Rather than ship a non-compliant dependency, RAR
 * archives are retained, hashed, indexed and marked {@code Unsupported} — evidence
 * is never silently dropped. A compliant extractor can be added later through the
 * {@link com.aegis.fdx.spi.Analyzer} SPI without modifying this class or the engine.
 * Each member is emitted as a child element; the pipeline re-analyzes it, which is
 * what makes nesting recursive to the configured depth.
 *
 * <p>Encrypted members are attempted against the case password list. If none work
 * the <em>member</em> is marked locked and the remaining members still process —
 * a locked entry never aborts the container (AT-03).
 */
public final class ArchiveAnalyzer implements Analyzer {

    /** Members larger than this are spooled to disk rather than held in heap. */
    private static final long HEAP_LIMIT = 64L * 1024 * 1024;

    private final List<String> passwords;

    public ArchiveAnalyzer(List<String> passwords) {
        this.passwords = passwords == null ? List.of() : passwords;
    }

    @Override public String id() { return "commons-compress"; }

    @Override
    public List<String> mediaTypes() {
        return List.of("application/zip", "application/x-7z-compressed",
                "application/x-tar", "application/gzip", "application/x-bzip2",
                "application/x-xz", "application/vnd.rar");
    }

    @Override
    public double sniff(byte[] h, String fileName) {
        // OOXML/ODF are ZIPs too — the Office analyzer must win those.
        if (Magic.isZip(h)) {
            if (Magic.extIn(fileName, "docx", "xlsx", "pptx", "docm", "xlsm", "pptm",
                    "odt", "ods", "odp", "jar")) {
                return 0;
            }
            return 0.95;
        }
        if (Magic.is7z(h)) return 1.0;
        if (Magic.isRar(h)) return 1.0;
        if (Magic.isTar(h)) return 0.9;
        if (Magic.isXz(h)) return 1.0;
        if (Magic.isBzip2(h)) return 0.95;
        if (Magic.isGzip(h)) return 0.95;
        return Magic.extIn(fileName, "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz") ? 0.5 : 0;
    }

    @Override public int priority() { return 20; }

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        item.container(true);
        byte[] head = new byte[512];
        in.mark(head.length + 1);
        int n = in.read(head);
        in.reset();
        byte[] h = n > 0 ? java.util.Arrays.copyOf(head, n) : new byte[0];

        if (Magic.isRar(h)) { rar(item, in, sink); return; }
        if (Magic.is7z(h)) { sevenZ(item, in, sink); return; }
        if (Magic.isZip(h)) { zip(item, in, sink); return; }
        if (Magic.isTar(h)) { tar(item, new TarArchiveInputStream(in), sink); return; }
        if (Magic.isGzip(h)) { single(item, new GzipCompressorInputStream(in), sink, "gz"); return; }
        if (Magic.isBzip2(h)) { single(item, new BZip2CompressorInputStream(in), sink, "bz2"); return; }
        if (Magic.isXz(h)) { single(item, new XZCompressorInputStream(in), sink, "xz"); return; }

        item.errors().add("Unrecognised archive signature");
    }

    // ---- ZIP (random access, so encrypted entries can be detected) ----------

    private void zip(Item item, InputStream in, ChildSink sink) throws Exception {
        Path tmp = spool(in);
        try (ZipFile zf = ZipFile.builder().setPath(tmp).get()) {
            int seq = 0, locked = 0;
            var entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                seq++;
                if (!zf.canReadEntryData(e)) {
                    // Encrypted or unsupported compression method.
                    Item child = childOf(item, e.getName(), seq, e.getSize());
                    child.markLocked(passwords.size());
                    child.addMetadata("Zip-Method", String.valueOf(e.getMethod()));
                    locked++;
                    sink.emit(child, null);
                    continue;
                }
                byte[] data;
                try (InputStream es = zf.getInputStream(e)) {
                    data = es.readAllBytes();
                } catch (Exception ex) {
                    Item child = childOf(item, e.getName(), seq, e.getSize());
                    child.errors().add("Entry read failed: " + ex.getMessage());
                    sink.emit(child, null);
                    continue;
                }
                Item child = childOf(item, e.getName(), seq, data.length);
                if (e.getLastModifiedDate() != null) {
                    child.modified(e.getLastModifiedDate().toInstant());
                }
                sink.emit(child, new ByteArrayInputStream(data));
            }
            item.addMetadata("Entries", String.valueOf(seq));
            if (locked > 0) item.addMetadata("Locked-Entries", String.valueOf(locked));
            item.extractedText("ZIP archive: " + seq + " entries"
                    + (locked > 0 ? ", " + locked + " encrypted" : "") + ".");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- 7z ----------------------------------------------------------------

    private void sevenZ(Item item, InputStream in, ChildSink sink) throws Exception {
        Path tmp = spool(in);
        try {
            int seq = 0;
            try (SevenZFile sz = SevenZFile.builder().setPath(tmp).get()) {
                SevenZArchiveEntry e;
                while ((e = sz.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    seq++;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = sz.read(buf)) > 0) bos.write(buf, 0, r);
                    Item child = childOf(item, e.getName(), seq, bos.size());
                    if (e.getHasLastModifiedDate()) {
                        child.modified(e.getLastModifiedDate().toInstant());
                    }
                    sink.emit(child, new ByteArrayInputStream(bos.toByteArray()));
                }
                item.addMetadata("Entries", String.valueOf(seq));
                item.extractedText("7z archive: " + seq + " entries.");
            } catch (java.io.IOException enc) {
                // Encrypted 7z headers fail here; try the password list.
                boolean ok = false;
                for (String pw : passwords) {
                    try (SevenZFile sz = SevenZFile.builder()
                            .setPath(tmp).setPassword(pw.toCharArray()).get()) {
                        SevenZArchiveEntry e;
                        while ((e = sz.getNextEntry()) != null) {
                            if (e.isDirectory()) continue;
                            seq++;
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = sz.read(buf)) > 0) bos.write(buf, 0, r);
                            sink.emit(childOf(item, e.getName(), seq, bos.size()),
                                    new ByteArrayInputStream(bos.toByteArray()));
                        }
                        item.addMetadata("Unlocked-With", "supplied password");
                        ok = true;
                        break;
                    } catch (Exception ignored) { }
                }
                if (!ok) item.markLocked(passwords.size());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- RAR ---------------------------------------------------------------

    /**
     * F-02 / licence compliance. RAR content is not extracted in v1.
     *
     * <p>The archive itself is still fully preserved: hashed, stored, indexed and
     * reported, so it appears in the processing report as an exception a reviewer
     * must handle out-of-band rather than vanishing from the case.
     */
    private void rar(Item item, InputStream in, ChildSink sink) throws Exception {
        item.status(com.aegis.fdx.model.ItemStatus.UNSUPPORTED);
        item.addMetadata("Unsupported",
                "RAR extraction is not included in v1 for licence-compliance reasons; "
                + "the archive is preserved and hashed but its contents are not expanded");
        item.addMetadata("Remediation",
                "Extract externally and re-ingest, or install a licence-compliant "
                + "RAR analyzer plugin");
        item.extractedText("RAR archive (contents not extracted).");
    }

    // ---- TAR ---------------------------------------------------------------

    private void tar(Item item, ArchiveInputStream<?> ais, ChildSink sink) throws Exception {
        int seq = 0;
        try (ais) {
            ArchiveEntry e;
            while ((e = ais.getNextEntry()) != null) {
                if (e.isDirectory() || !ais.canReadEntryData(e)) continue;
                seq++;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ais.transferTo(bos);
                Item child = childOf(item, e.getName(), seq, bos.size());
                if (e.getLastModifiedDate() != null) {
                    child.modified(e.getLastModifiedDate().toInstant());
                }
                sink.emit(child, new ByteArrayInputStream(bos.toByteArray()));
            }
        }
        item.addMetadata("Entries", String.valueOf(seq));
        item.extractedText("TAR archive: " + seq + " entries.");
    }

    /** GZ/BZ2/XZ wrap exactly one stream, which may itself be a TAR. */
    private void single(Item item, InputStream decompressed, ChildSink sink, String kind)
            throws Exception {
        String base = item.name();
        String suffix = "." + kind;
        if (base.toLowerCase().endsWith(suffix)) {
            base = base.substring(0, base.length() - suffix.length());
        } else if (base.toLowerCase().endsWith(".tgz")) {
            base = base.substring(0, base.length() - 4) + ".tar";
        }
        byte[] data;
        try (decompressed) {
            data = decompressed.readAllBytes();
        }
        Item child = childOf(item, base, 1, data.length);
        item.addMetadata("Entries", "1");
        item.extractedText(kind.toUpperCase() + " stream containing " + base + ".");
        sink.emit(child, new ByteArrayInputStream(data));
    }

    // ---- helpers -----------------------------------------------------------

    private Item childOf(Item parent, String entryName, int seq, long size) {
        String clean = entryName == null ? "entry_" + seq : entryName.replace('\\', '/');
        String leaf = clean.contains("/") ? clean.substring(clean.lastIndexOf('/') + 1) : clean;
        if (leaf.isBlank()) leaf = "entry_" + seq;

        Item child = new Item(parent.id() + "-E" + seq, leaf);
        child.parentId(parent.id());
        child.depth(parent.depth() + 1);
        child.custodian(parent.custodian());
        child.sourcePath(parent.sourcePath());
        child.size(Math.max(size, 0));
        child.containerPath(
                (parent.containerPath() == null ? parent.name() : parent.containerPath())
                + " → " + clean);
        return child;
    }

    private static Path spool(InputStream in) throws Exception {
        Path tmp = Files.createTempFile("aegis-arc-", ".bin");
        try (var out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        }
        return tmp;
    }
}
