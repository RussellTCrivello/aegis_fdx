package com.aegis.fdx.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An Element (F-09): any discrete object produced by extraction — file, email,
 * attachment or archive member. Immutable identity ({@link #id}) with mutable
 * review state (tags, notes, status) owned by the review layer.
 */
public final class Item {

    private final String id;
    private String name;
    private String extension;
    private long size;
    private Instant created;
    private Instant fsModified;
    private Instant modified;
    private Instant accessed;
    private String md5;
    private String sha256;
    private String mediaType;
    private String sourcePath;        // original full path on evidence media
    private String custodian;         // "responsible party"
    private String containerPath;     // path within parent container chain
    private String geoLocation;       // EXIF GPS, if any
    private int depth;                // nesting level, 0 = top-level
    private String parentId;          // F-03 parent link
    private ItemStatus status = ItemStatus.PENDING;

    // Email-specific (F-09)
    private String from;
    private String to;
    private String cc;
    private String subject;
    private Instant sentDate;
    private String messageId;
    private int attachmentCount;

    private String extractedText = "";
    private String notes = "";
    private boolean container;          // holds other elements (F-03)
    private boolean needsOcr;           // F-08 OCR candidate
    private boolean ocrApplied;
    private String attachedFrom;        // F-10 "attachment from" link
    private boolean duplicate;          // F-05 marked, never deleted
    private String duplicateOf;
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private final Set<String> tags = new LinkedHashSet<>();
    private final List<String> errors = new ArrayList<>();

    public Item(String id, String name) {
        this.id = id;
        this.name = name;
        int dot = name.lastIndexOf('.');
        this.extension = dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    public boolean isEmail() {
        return mediaType != null && (mediaType.startsWith("message/")
                || mediaType.contains("outlook") || mediaType.contains("rfc822"));
    }

    public boolean hasAttachments() { return attachmentCount > 0; }

    /** F-20: hidden items are excluded from search results and export. */
    public boolean isHidden() { return tags.contains("Hidden"); }

    public String id() { return id; }
    public String name() { return name; }
    public void name(String v) { name = v; }
    public String extension() { return extension; }
    public void extension(String v) { extension = v; }
    public long size() { return size; }
    public void size(long v) { size = v; }
    public Instant created() { return created; }
    public void created(Instant v) { created = v; }
    /**
     * M3-D. The source file's last-modified time as observed at intake, before any
     * analyzer runs. Analyzers legitimately overwrite {@link #modified()} with the
     * document's own internal date (a .pptx authored in 2011 reports 2011), so that
     * field cannot serve as the read-only baseline. This one is never touched after
     * intake and is what F-06 verification compares against.
     */
    public Instant fsModified() { return fsModified; }
    public void fsModified(Instant v) { fsModified = v; }

    public Instant modified() { return modified; }
    public void modified(Instant v) { modified = v; }
    public Instant accessed() { return accessed; }
    public void accessed(Instant v) { accessed = v; }
    public String md5() { return md5; }
    public void md5(String v) { md5 = v; }
    public String sha256() { return sha256; }
    public void sha256(String v) { sha256 = v; }
    public String mediaType() { return mediaType; }
    public void mediaType(String v) { mediaType = v; }
    public String sourcePath() { return sourcePath; }
    public void sourcePath(String v) { sourcePath = v; }
    public String custodian() { return custodian; }
    public void custodian(String v) { custodian = v; }
    public String containerPath() { return containerPath; }
    public void containerPath(String v) { containerPath = v; }
    public String geoLocation() { return geoLocation; }
    public void geoLocation(String v) { geoLocation = v; }
    public int depth() { return depth; }
    public void depth(int v) { depth = v; }
    public String parentId() { return parentId; }
    public void parentId(String v) { parentId = v; }
    public ItemStatus status() { return status; }
    public void status(ItemStatus v) { status = v; }
    public String from() { return from; }
    public void from(String v) { from = v; }
    public String to() { return to; }
    public void to(String v) { to = v; }
    public String cc() { return cc; }
    public void cc(String v) { cc = v; }
    public String subject() { return subject; }
    public void subject(String v) { subject = v; }
    public Instant sentDate() { return sentDate; }
    public void sentDate(Instant v) { sentDate = v; }
    public String messageId() { return messageId; }
    public void messageId(String v) { messageId = v; }
    public int attachmentCount() { return attachmentCount; }
    public void attachmentCount(int v) { attachmentCount = v; }
    public String extractedText() { return extractedText; }
    public void extractedText(String v) { extractedText = v == null ? "" : v; }
    public String notes() { return notes; }
    public void notes(String v) { notes = v == null ? "" : v; }
    public Set<String> tags() { return tags; }
    public List<String> errors() { return errors; }
    public boolean container() { return container; }
    public void container(boolean v) { container = v; }
    public boolean needsOcr() { return needsOcr; }
    public void needsOcr(boolean v) { needsOcr = v; }
    public boolean ocrApplied() { return ocrApplied; }
    public void ocrApplied(boolean v) { ocrApplied = v; }
    public String attachedFrom() { return attachedFrom; }
    public void attachedFrom(String v) { attachedFrom = v; }
    public boolean duplicate() { return duplicate; }
    public void duplicate(boolean v) { duplicate = v; }
    public String duplicateOf() { return duplicateOf; }
    public void duplicateOf(String v) { duplicateOf = v; }
    public Map<String, String> metadata() { return metadata; }

    public void addMetadata(String key, String value) {
        if (key != null && value != null && !value.isBlank()) metadata.put(key, value);
    }

    /** F-04: retained and marked, processing continues. */
    public void markLocked(int triedPasswords) {
        status(ItemStatus.LOCKED);
        addMetadata("Locked", "no supplied password matched (" + triedPasswords + " tried)");
    }

    @Override public String toString() { return id + ":" + name; }
}
