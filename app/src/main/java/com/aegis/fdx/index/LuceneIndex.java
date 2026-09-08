package com.aegis.fdx.index;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F-12 / F-13 / F-14: one Lucene index per case, living in {@code <case>/index}.
 *
 * <p>Near-real-time: {@link #openReader()} pulls a reader straight off the
 * {@link IndexWriter}, so an element is searchable the instant it is added —
 * during ingest, not after it (F-13).
 *
 * <p>The index is disposable. Every stored field needed to rebuild it also lives
 * in {@code <case>/text} and the SQLite database, satisfying F-14: reindex without
 * re-reading source evidence.
 */
public final class LuceneIndex implements Closeable {

    // --- field names (kept public: the query layer maps user syntax onto these) --
    public static final String F_ID = "id";
    public static final String F_NAME = "name";
    public static final String F_EXT = "ext";
    public static final String F_MEDIA = "media";
    public static final String F_TEXT = "text";
    public static final String F_ALL = "all";
    public static final String F_FROM = "from";
    public static final String F_TO = "to";
    public static final String F_CC = "cc";
    public static final String F_SUBJECT = "subject";
    public static final String F_MESSAGE_ID = "messageid";
    public static final String F_PATH = "path";
    public static final String F_CONTAINER = "container";
    public static final String F_CUSTODIAN = "custodian";
    public static final String F_TAG = "tag";
    public static final String F_NOTES = "notes";
    public static final String F_STATUS = "status";
    public static final String F_MD5 = "md5";
    public static final String F_SHA256 = "sha256";
    public static final String F_HAS_ATT = "hasattachments";
    public static final String F_DEPTH = "depth";
    public static final String F_PARENT = "parent";
    public static final String F_SIZE = "size";
    public static final String F_MODIFIED = "modified";
    public static final String F_CREATED = "created";
    public static final String F_SENT = "sent";
    public static final String F_META = "meta";
    /** Stored-only concatenation used by {@link StoredTextRegexQuery} (F-15). */
    public static final String F_TEXT_ALL = "text_all";
    // M3: forensic relationship + OCR provenance fields. These previously existed
    // only in memory and were silently lost on every index round-trip, which broke
    // duplicate/attachment relationships in exports and OCR resume.
    public static final String F_OCR_APPLIED = "ocrapplied";
    public static final String F_NEEDS_OCR = "needsocr";
    public static final String F_DUPLICATE = "duplicate";
    public static final String F_DUPLICATE_OF = "duplicateof";
    public static final String F_ATTACHED_FROM = "attachedfrom";
    public static final String F_ATT_COUNT = "attcount";
    public static final String F_GEO = "geo";
    public static final String F_IS_CONTAINER = "iscontainer";
    public static final String F_ERRORS = "errors";
    public static final String F_FS_MODIFIED = "fsmodified";

    private final Directory directory;
    private final IndexWriter writer;
    private final Analyzer analyzer;
    private final AtomicReference<DirectoryReader> reader = new AtomicReference<>();

    public LuceneIndex(Path indexDir, int ramBufferMb) throws IOException {
        this.directory = FSDirectory.open(indexDir);
        this.analyzer = newAnalyzer();

        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        cfg.setRAMBufferSizeMB(Math.max(64, ramBufferMb));      // F-29 index memory
        cfg.setCommitOnClose(true);
        this.writer = new IndexWriter(directory, cfg);
    }

    /**
     * Identifier-ish fields must not be tokenised or an address like
     * {@code a.farouk@northwind.example} would be shredded; free text uses the
     * standard analyzer. This is the "accurate parsing for email addresses and
     * dates" clause of F-12.
     */
    public static Analyzer newAnalyzer() {
        return new PerFieldAnalyzerWrapper(new StandardAnalyzer(), Map.of(
                F_ID, new KeywordAnalyzer(),
                F_EXT, new KeywordAnalyzer(),
                F_TAG, new KeywordAnalyzer(),
                F_STATUS, new KeywordAnalyzer(),
                F_MD5, new KeywordAnalyzer(),
                F_SHA256, new KeywordAnalyzer(),
                F_PARENT, new KeywordAnalyzer(),
                F_CUSTODIAN, new KeywordAnalyzer()));
    }

    public Analyzer analyzer() { return analyzer; }

    // ---- writing ------------------------------------------------------------

    /** Upsert by element id, so re-running a stage is idempotent (A-01 replay). */
    public void put(Item it) throws IOException {
        writer.updateDocument(new Term(F_ID, it.id()), toDocument(it));
    }

    public void commit() throws IOException {
        writer.commit();
    }

    public long pending() {
        return writer.hasUncommittedChanges() ? 1 : 0;
    }

    static Document toDocument(Item it) {
        Document d = new Document();
        StringBuilder all = new StringBuilder(512);

        d.add(new StringField(F_ID, it.id(), Field.Store.YES));
        add(d, all, F_NAME, it.name(), true);
        addKeyword(d, all, F_EXT, it.extension());
        add(d, all, F_MEDIA, it.mediaType(), false);
        add(d, all, F_TEXT, it.extractedText(), true);
        add(d, all, F_FROM, it.from(), true);
        add(d, all, F_TO, it.to(), true);
        add(d, all, F_CC, it.cc(), true);
        add(d, all, F_SUBJECT, it.subject(), true);
        add(d, all, F_MESSAGE_ID, it.messageId(), false);
        add(d, all, F_PATH, it.sourcePath(), true);
        add(d, all, F_CONTAINER, it.containerPath(), true);
        addKeyword(d, all, F_CUSTODIAN, it.custodian());
        add(d, all, F_NOTES, it.notes(), true);
        addKeyword(d, all, F_STATUS, it.status().label());
        addKeyword(d, all, F_MD5, it.md5());
        addKeyword(d, all, F_SHA256, it.sha256());

        for (String tag : it.tags()) {
            d.add(new StringField(F_TAG, tag, Field.Store.YES));
            all.append(tag).append(' ');
        }
        for (Map.Entry<String, String> e : it.metadata().entrySet()) {
            d.add(new TextField(F_META, e.getKey() + " " + e.getValue(), Field.Store.YES));
            all.append(e.getValue()).append(' ');
        }

        d.add(new StringField(F_HAS_ATT, String.valueOf(it.hasAttachments()), Field.Store.YES));
        d.add(new StoredField(F_ATT_COUNT, it.attachmentCount()));
        d.add(new StringField(F_OCR_APPLIED, String.valueOf(it.ocrApplied()), Field.Store.YES));
        d.add(new StringField(F_NEEDS_OCR, String.valueOf(it.needsOcr()), Field.Store.YES));
        d.add(new StringField(F_DUPLICATE, String.valueOf(it.duplicate()), Field.Store.YES));
        d.add(new StringField(F_IS_CONTAINER, String.valueOf(it.container()), Field.Store.YES));
        if (it.duplicateOf() != null)
            d.add(new StringField(F_DUPLICATE_OF, it.duplicateOf(), Field.Store.YES));
        if (it.attachedFrom() != null)
            d.add(new StringField(F_ATTACHED_FROM, it.attachedFrom(), Field.Store.YES));
        if (it.geoLocation() != null) add(d, all, F_GEO, it.geoLocation(), true);
        for (String err : it.errors()) d.add(new StoredField(F_ERRORS, err));
        d.add(new StringField(F_DEPTH, String.valueOf(it.depth()), Field.Store.YES));
        if (it.parentId() != null) d.add(new StringField(F_PARENT, it.parentId(), Field.Store.YES));

        d.add(new LongPoint(F_SIZE, it.size()));
        d.add(new StoredField(F_SIZE + "_s", it.size()));
        addDate(d, F_MODIFIED, it.modified());
        addDate(d, F_CREATED, it.created());
        addDate(d, F_SENT, it.sentDate());
        if (it.fsModified() != null)
            d.add(new StoredField(F_FS_MODIFIED, it.fsModified().toEpochMilli()));

        // Sorting (F-17)
        d.add(new SortedDocValuesField("sort_name", new BytesRef(nz(it.name()).toLowerCase())));
        d.add(new org.apache.lucene.document.NumericDocValuesField("sort_size", it.size()));
        d.add(new org.apache.lucene.document.NumericDocValuesField("sort_modified",
                it.modified() == null ? 0 : it.modified().toEpochMilli()));

        d.add(new TextField(F_ALL, all.toString(), Field.Store.NO));
        d.add(new StoredField(F_TEXT_ALL, all.toString()));
        return d;
    }

    private static void add(Document d, StringBuilder all, String field, String v, boolean toAll) {
        if (v == null || v.isEmpty()) return;
        d.add(new TextField(field, v, Field.Store.YES));
        if (toAll) all.append(v).append(' ');
    }

    private static void addKeyword(Document d, StringBuilder all, String field, String v) {
        if (v == null || v.isEmpty()) return;
        d.add(new StringField(field, v, Field.Store.YES));
        all.append(v).append(' ');
    }

    private static void addDate(Document d, String field, Instant when) {
        if (when == null) return;
        long ms = when.toEpochMilli();
        d.add(new LongPoint(field, ms));
        d.add(new StoredField(field + "_s", ms));
    }

    // ---- reading ------------------------------------------------------------

    /** NRT reader; reopened only when the index actually changed. */
    public synchronized DirectoryReader openReader() throws IOException {
        DirectoryReader cur = reader.get();
        if (cur == null) {
            cur = DirectoryReader.open(writer, true, true);
            reader.set(cur);
            return cur;
        }
        DirectoryReader next = DirectoryReader.openIfChanged(cur, writer, true);
        if (next != null) {
            cur.close();
            reader.set(next);
            return next;
        }
        return cur;
    }

    public IndexSearcher searcher() throws IOException {
        return new IndexSearcher(openReader());
    }

    public List<Document> search(Query q, int limit, Sort sort) throws IOException {
        IndexSearcher s = searcher();
        TopDocs td = sort == null ? s.search(q, limit) : s.search(q, limit, sort);
        List<Document> out = new ArrayList<>(td.scoreDocs.length);
        for (ScoreDoc sd : td.scoreDocs) out.add(s.storedFields().document(sd.doc));
        return out;
    }

    /** M3. Loads a single element by id, or null when it is not indexed. */
    public Item byId(String id) throws IOException {
        if (id == null) return null;
        List<Document> d = search(new org.apache.lucene.search.TermQuery(
                new org.apache.lucene.index.Term(F_ID, id)), 1, null);
        return d.isEmpty() ? null : fromDocument(d.get(0));
    }

    public int count() throws IOException {
        return openReader().numDocs();
    }

    @Override
    public void close() throws IOException {
        DirectoryReader r = reader.getAndSet(null);
        if (r != null) {
            try { r.close(); } catch (IOException ignored) { }
        }
        try { writer.close(); } finally { directory.close(); }
    }

    /** Reconstructs an {@link Item} from stored fields (F-14). */
    public static Item fromDocument(Document d) {
        Item it = new Item(d.get(F_ID), nz(d.get(F_NAME)));
        it.extension(nz(d.get(F_EXT)));
        it.mediaType(d.get(F_MEDIA));
        it.extractedText(nz(d.get(F_TEXT)));
        it.from(d.get(F_FROM));
        it.to(d.get(F_TO));
        it.cc(d.get(F_CC));
        it.subject(d.get(F_SUBJECT));
        it.messageId(d.get(F_MESSAGE_ID));
        it.sourcePath(d.get(F_PATH));
        it.containerPath(d.get(F_CONTAINER));
        it.custodian(d.get(F_CUSTODIAN));
        it.notes(nz(d.get(F_NOTES)));
        it.md5(d.get(F_MD5));
        it.sha256(d.get(F_SHA256));
        it.parentId(d.get(F_PARENT));

        String st = d.get(F_STATUS);
        for (ItemStatus s : ItemStatus.values()) {
            if (s.label().equals(st)) { it.status(s); break; }
        }
        for (String tag : d.getValues(F_TAG)) it.tags().add(tag);

        var sizeField = d.getField(F_SIZE + "_s");
        if (sizeField != null) it.size(sizeField.numericValue().longValue());
        it.depth(parseInt(d.get(F_DEPTH)));

        // Attachment count is a real count, not a flag: an email with 12 attachments
        // must not round-trip as 1.
        var attCount = d.getField(F_ATT_COUNT);
        if (attCount != null) it.attachmentCount(attCount.numericValue().intValue());
        else it.attachmentCount("true".equals(d.get(F_HAS_ATT)) ? 1 : 0);

        it.ocrApplied("true".equals(d.get(F_OCR_APPLIED)));
        it.needsOcr("true".equals(d.get(F_NEEDS_OCR)));
        it.duplicate("true".equals(d.get(F_DUPLICATE)));
        it.container("true".equals(d.get(F_IS_CONTAINER)));
        it.duplicateOf(d.get(F_DUPLICATE_OF));
        it.attachedFrom(d.get(F_ATTACHED_FROM));
        it.geoLocation(d.get(F_GEO));
        for (String err : d.getValues(F_ERRORS)) it.errors().add(err);

        var mod = d.getField(F_MODIFIED + "_s");
        if (mod != null) it.modified(Instant.ofEpochMilli(mod.numericValue().longValue()));
        var cre = d.getField(F_CREATED + "_s");
        if (cre != null) it.created(Instant.ofEpochMilli(cre.numericValue().longValue()));
        var sent = d.getField(F_SENT + "_s");
        if (sent != null) it.sentDate(Instant.ofEpochMilli(sent.numericValue().longValue()));
        var fsm = d.getField(F_FS_MODIFIED);
        if (fsm != null) it.fsModified(Instant.ofEpochMilli(fsm.numericValue().longValue()));

        for (String m : d.getValues(F_META)) {
            int sp = m.indexOf(' ');
            if (sp > 0) it.addMetadata(m.substring(0, sp), m.substring(sp + 1));
        }
        return it;
    }

    private static int parseInt(String s) {
        try { return s == null ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
