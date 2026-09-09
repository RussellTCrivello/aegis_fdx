package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * F-01: Outlook MSG (OLE2/MAPI) via POI HSMF. Attachments — including embedded
 * messages — are emitted as independent elements (F-10).
 */
public final class MsgAnalyzer implements Analyzer {

    @Override public String id() { return "poi-hsmf-msg"; }

    @Override public List<String> mediaTypes() { return List.of("application/vnd.ms-outlook"); }

    @Override
    public double sniff(byte[] header, String fileName) {
        if (Magic.isOle2(header) && Magic.extIn(fileName, "msg")) return 1.0;
        return Magic.extIn(fileName, "msg") ? 0.6 : 0;
    }

    /** Outranks OfficeAnalyzer, which also claims OLE2 streams. */
    @Override public int priority() { return 50; }

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        item.mediaType("application/vnd.ms-outlook");

        MAPIMessage msg = new MAPIMessage(in);
        msg.setReturnNullOnMissingChunk(true);
        try {
            item.subject(msg.getSubject());
            item.from(msg.getDisplayFrom());
            item.to(msg.getDisplayTo());
            item.cc(msg.getDisplayCC());
            if (notBlank(msg.getDisplayBCC())) item.addMetadata("Bcc", msg.getDisplayBCC());
            try {
                String[] hdrs = msg.getHeaders();
                if (hdrs != null) {
                    for (String hline : hdrs) {
                        if (hline.regionMatches(true, 0, "Message-ID:", 0, 11)) {
                            item.messageId(hline.substring(11).trim());
                            break;
                        }
                    }
                }
            } catch (Exception ignored) { }
            try {
                var d = msg.getMessageDate();
                if (d != null) item.sentDate(d.toInstant());
            } catch (Exception ignored) { }

            String body = msg.getTextBody();
            if (body == null || body.isBlank()) {
                try { body = msg.getHtmlBody(); } catch (Exception ignored) { }
            }
            item.extractedText(EmlAnalyzer.header(item) + (body == null ? "" : body.strip()));

            AttachmentChunks[] atts = msg.getAttachmentFiles();
            item.attachmentCount(atts == null ? 0 : atts.length);
            if (atts == null) return;

            int n = 0;
            for (AttachmentChunks a : atts) {
                n++;
                String name = pick(a, n);
                byte[] data = null;

                if (a.getAttachData() != null) {
                    data = a.getAttachData().getValue();
                } else if (a.isEmbeddedMessage()) {
                    // Embedded MSG: flatten to text so it becomes its own element.
                    MAPIMessage emb = a.getEmbeddedMessage();
                    emb.setReturnNullOnMissingChunk(true);
                    StringBuilder sb = new StringBuilder();
                    append(sb, "Subject: ", emb.getSubject());
                    append(sb, "From: ", emb.getDisplayFrom());
                    append(sb, "To: ", emb.getDisplayTo());
                    sb.append('\n');
                    String b = emb.getTextBody();
                    if (b != null) sb.append(b);
                    data = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (!name.toLowerCase().endsWith(".eml")) name = name + ".eml";
                }
                if (data == null) continue;

                Item child = new Item(item.id() + "-A" + n, name);
                child.parentId(item.id());
                child.depth(item.depth() + 1);
                child.custodian(item.custodian());
                child.containerPath(
                        (item.containerPath() == null ? item.name() : item.containerPath())
                        + " → " + name);
                child.sourcePath(item.sourcePath());
                child.size(data.length);
                child.attachedFrom(item.id());
                sink.emit(child, new ByteArrayInputStream(data));
            }
        } finally {
            try { msg.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Names an attachment for its real filename: long then short filename, an
     * embedded message's subject, then a filename-shaped Content-ID. Whatever
     * survives is sanitized; only a fully anonymous attachment keeps a generated
     * name, with a MIME-derived extension when there is one.
     */
    private static String pick(AttachmentChunks a, int n) {
        String name = firstPresent(
                text(a.getAttachLongFileName()),
                text(a.getAttachFileName()));
        if (!name.isBlank()) return AttachmentNames.sanitize(name);
        // Attached messages carry no filename; their subject is the real name.
        // The caller appends .eml for the flattened bytes (see analyze).
        String subject = embeddedSubject(a);
        if (!subject.isBlank()) return AttachmentNames.sanitize(subject);
        // Inline content usually has only a Content-ID like image001.png@....
        String fromCid = AttachmentNames.fromContentId(text(a.getAttachContentId()));
        if (!fromCid.isBlank()) {
            return AttachmentNames.ensureExtension(
                    AttachmentNames.sanitize(fromCid), mimeExtensionOf(a));
        }
        String ext = mimeExtensionOf(a);
        return "attachment_" + n + (ext == null ? "" : ext);
    }

    private static String firstPresent(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    /** Null-safe chunk read; missing chunks and missing values both yield null. */
    private static String text(StringChunk c) {
        try {
            return c == null ? null : c.getValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String embeddedSubject(AttachmentChunks a) {
        try {
            if (a.isEmbeddedMessage() && a.getEmbeddedMessage() != null) {
                String s = a.getEmbeddedMessage().getSubject();
                return s == null ? "" : s;
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** MIME tag first, then the PidTagAttachExtension property (e.g. ".pdf"). */
    private static String mimeExtensionOf(AttachmentChunks a) {
        String ext = AttachmentNames.extensionFor(text(a.getAttachMimeTag()));
        if (ext != null) return ext;
        String raw = text(a.getAttachExtension());
        if (raw != null) {
            String r = raw.strip();
            if (r.startsWith(".")) r = r.substring(1);
            if (r.matches("[A-Za-z0-9]{1,6}")) {
                return "." + r.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static void append(StringBuilder sb, String label, String v) {
        if (v != null && !v.isBlank()) sb.append(label).append(v).append('\n');
    }
}
