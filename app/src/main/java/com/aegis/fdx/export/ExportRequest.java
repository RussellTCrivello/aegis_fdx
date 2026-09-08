package com.aegis.fdx.export;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M3-B / F-25..F-27. Describes one export job.
 *
 * <p>Deliberately a plain value object: the UI fills it in, the writer consumes it,
 * and a saved job can be replayed for a defensible re-production.
 */
public final class ExportRequest {

    /** F-25 scope. */
    public enum Scope { SELECTED, ALL, BY_TAG }

    /** F-26 output format for each element. */
    public enum Format {
        /** Byte-for-byte original file. */
        NATIVE,
        /** RFC-822 message; non-email elements fall back to native. */
        EML,
        /** Rendered/wrapped PDF. */
        PDF,
        /** Extracted text only. */
        TEXT
    }

    /** F-26 folder layout on disk. */
    public enum Layout {
        /** Everything in one directory, ids prefixed to guarantee uniqueness. */
        FLAT,
        /** Grouped by file extension. */
        BY_TYPE,
        /** Grouped by custodian. */
        BY_CUSTODIAN,
        /** Mirrors the original container/folder structure. */
        HIERARCHY
    }

    private Path destination;
    private Scope scope = Scope.ALL;
    private Format format = Format.NATIVE;
    private Layout layout = Layout.BY_CUSTODIAN;

    private final List<String> selectedIds = new ArrayList<>();
    private String tag;

    /** F-24: Hidden is excluded from export unless explicitly overridden. */
    private boolean includeHidden = false;

    /** Emit the UTF-8 CSV load file (F-27). */
    private boolean writeLoadFile = true;

    /** Emit extracted text alongside each element. */
    private boolean writeText = true;

    /** Emit a per-export SHA-256 manifest for chain of custody. */
    private boolean writeManifest = true;

    /** Verify each written file's hash against the recorded value (D-04). */
    private boolean verifyHashes = true;

    public Path destination() { return destination; }
    public ExportRequest destination(Path v) { this.destination = v; return this; }

    public Scope scope() { return scope; }
    public ExportRequest scope(Scope v) { this.scope = v; return this; }

    public Format format() { return format; }
    public ExportRequest format(Format v) { this.format = v; return this; }

    public Layout layout() { return layout; }
    public ExportRequest layout(Layout v) { this.layout = v; return this; }

    public List<String> selectedIds() { return selectedIds; }
    public ExportRequest selectedIds(List<String> ids) {
        selectedIds.clear();
        if (ids != null) selectedIds.addAll(ids);
        return this;
    }

    public String tag() { return tag; }
    public ExportRequest tag(String v) { this.tag = v; return this; }

    public boolean includeHidden() { return includeHidden; }
    public ExportRequest includeHidden(boolean v) { this.includeHidden = v; return this; }

    public boolean writeLoadFile() { return writeLoadFile; }
    public ExportRequest writeLoadFile(boolean v) { this.writeLoadFile = v; return this; }

    public boolean writeText() { return writeText; }
    public ExportRequest writeText(boolean v) { this.writeText = v; return this; }

    public boolean writeManifest() { return writeManifest; }
    public ExportRequest writeManifest(boolean v) { this.writeManifest = v; return this; }

    public boolean verifyHashes() { return verifyHashes; }
    public ExportRequest verifyHashes(boolean v) { this.verifyHashes = v; return this; }

    @Override public String toString() {
        return "Export[" + scope + " as " + format + ", layout " + layout
                + (tag != null ? ", tag=" + tag : "")
                + (includeHidden ? ", +hidden" : "") + "]";
    }
}
