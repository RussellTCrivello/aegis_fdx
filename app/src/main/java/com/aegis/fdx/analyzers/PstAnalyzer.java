package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;
import com.pff.PSTAttachment;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import com.pff.PSTObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * F-01 / F-03: Outlook PST and OST mailbox containers via java-libpst.
 *
 * <p>Walks the full folder tree and emits every message as a child element,
 * preserving the mailbox folder path in {@code containerPath}. Message
 * attachments are emitted one level deeper, which is what produces the
 * ZIP→PST→MSG→PDF chain required by AT-02.
 *
 * <p>libpst needs random access, so the stream is spooled to a temp file that is
 * deleted in {@code finally} — the source evidence is never touched (F-06).
 */
public final class PstAnalyzer implements Analyzer {

    /** Hard cap so a corrupt tree cannot spin forever. */
    private static final int MAX_FOLDER_DEPTH = 64;

    @Override public String id() { return "libpst"; }

    @Override
    public List<String> mediaTypes() {
        return List.of("application/vnd.ms-outlook-pst", "application/vnd.ms-outlook-ost");
    }

    @Override
    public double sniff(byte[] header, String fileName) {
        if (Magic.isPst(header)) return 1.0;
        return Magic.extIn(fileName, "pst", "ost") ? 0.5 : 0;
    }

    @Override public int priority() { return 60; }

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        item.mediaType(Magic.extIn(item.name(), "ost")
                ? "application/vnd.ms-outlook-ost"
                : "application/vnd.ms-outlook-pst");
        item.container(true);

        Path tmp = Files.createTempFile("aegis-pst-", ".pst");
        try {
            try (var out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            PSTFile pst = new PSTFile(tmp.toFile());
            try {
                // The library prints one "Unknown message type" line per unhandled
                // item with no way to mute it; filter the console and count instead.
                PstConsoleFilter.installOnce();
                PstConsoleFilter.resetThreadSuppressed();
                item.addMetadata("PST-Encryption", String.valueOf(pst.getEncryptionType()));
                Counter c = new Counter();
                walk(pst.getRootFolder(), "", item, sink, c, 0);
                item.addMetadata("Messages", String.valueOf(c.messages));
                item.addMetadata("Folders", String.valueOf(c.folders));
                item.addMetadata("PST-Unknown-Classes",
                        String.valueOf(PstConsoleFilter.threadSuppressed()));
                item.extractedText("Outlook store: " + c.messages
                        + " messages across " + c.folders + " folders.");
            } finally {
                try { pst.close(); } catch (Exception ignored) { }
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
        }
    }

    private void walk(PSTFolder folder, String path, Item parent,
                      ChildSink sink, Counter c, int depth) {
        if (depth > MAX_FOLDER_DEPTH) return;
        c.folders++;

        try {
            if (folder.hasSubfolders()) {
                for (PSTFolder sub : folder.getSubFolders()) {
                    String name = sub.getDisplayName() == null ? "(unnamed)" : sub.getDisplayName();
                    walk(sub, path.isEmpty() ? name : path + "/" + name, parent, sink, c, depth + 1);
                }
            }
        } catch (Exception e) {
            parent.errors().add("PST subfolder walk failed at '" + path + "': " + e.getMessage());
        }

        try {
            PSTObject child;
            while ((child = folder.getNextChild()) != null) {
                if (!(child instanceof PSTMessage msg)) continue;
                try {
                    emitMessage(msg, path, parent, sink, ++c.messages);
                } catch (Exception e) {
                    parent.errors().add("PST message read failed in '" + path + "': " + e.getMessage());
                }
            }
        } catch (Exception e) {
            parent.errors().add("PST message iteration failed in '" + path + "': " + e.getMessage());
        }
    }

    private void emitMessage(PSTMessage msg, String folderPath, Item parent,
                             ChildSink sink, int seq) {
        String subject = null;
        try {
            subject = msg.getSubject();
        } catch (Exception ignored) { }

        String name = (subject == null || subject.isBlank())
                ? "message_" + seq + ".msg"
                : sanitize(subject) + ".msg";

        Item child = new Item(parent.id() + "-M" + seq, name);
        child.parentId(parent.id());
        child.depth(parent.depth() + 1);
        child.custodian(parent.custodian());
        child.sourcePath(parent.sourcePath());
        child.mediaType("application/vnd.ms-outlook");
        child.containerPath(
                (parent.containerPath() == null ? parent.name() : parent.containerPath())
                + " → " + (folderPath.isEmpty() ? "" : folderPath + "/") + name);

        child.subject(subject);
        try { child.from(fmtFrom(msg)); } catch (Exception ignored) { }
        try { child.to(msg.getDisplayTo()); } catch (Exception ignored) { }
        try { child.cc(msg.getDisplayCC()); } catch (Exception ignored) { }
        try { child.messageId(msg.getInternetMessageId()); } catch (Exception ignored) { }
        try {
            if (msg.getMessageDeliveryTime() != null) {
                child.sentDate(msg.getMessageDeliveryTime().toInstant());
                child.modified(msg.getMessageDeliveryTime().toInstant());
            }
        } catch (Exception ignored) { }
        try {
            if (msg.getCreationTime() != null) child.created(msg.getCreationTime().toInstant());
        } catch (Exception ignored) { }
        child.addMetadata("Mailbox-Folder", folderPath.isEmpty() ? "(root)" : folderPath);

        String body = "";
        try {
            body = msg.getBody();
        } catch (Exception ignored) { }
        if (body == null || body.isBlank()) {
            try {
                body = msg.getBodyHTML();
            } catch (Exception ignored) { }
        }
        if (body == null || body.isBlank()) {
            try {
                body = msg.getRTFBody();
            } catch (Exception ignored) { }
        }
        child.extractedText(EmlAnalyzer.header(child) + (body == null ? "" : body.strip()));
        child.size(child.extractedText().length());

        int nAtt = 0;
        try {
            nAtt = msg.getNumberOfAttachments();
        } catch (Exception ignored) { }
        child.attachmentCount(nAtt);

        // The message element itself is already fully parsed: emit with no stream.
        sink.emit(child, null);

        for (int i = 0; i < nAtt; i++) {
            try {
                PSTAttachment att = msg.getAttachment(i);
                if (att == null) continue;
                byte[] data = readAttachment(att);
                if (data == null) continue;

                String aName = null;
                try {
                    aName = att.getLongFilename();
                } catch (Exception ignored) { }
                if (aName == null || aName.isBlank()) {
                    try {
                        aName = att.getFilename();
                    } catch (Exception ignored) { }
                }
                if (aName == null || aName.isBlank()) aName = "attachment_" + (i + 1);

                Item grand = new Item(child.id() + "-A" + (i + 1), aName);
                grand.parentId(child.id());
                grand.depth(child.depth() + 1);
                grand.custodian(parent.custodian());
                grand.sourcePath(parent.sourcePath());
                grand.containerPath(child.containerPath() + " → " + aName);
                grand.size(data.length);
                grand.attachedFrom(child.id());
                sink.emit(grand, new ByteArrayInputStream(data));
            } catch (Exception e) {
                child.errors().add("Attachment " + (i + 1) + " failed: " + e.getMessage());
            }
        }
    }

    private static byte[] readAttachment(PSTAttachment att) {
        try (InputStream is = att.getFileInputStream()) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            is.transferTo(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            // Embedded message attachments expose no file stream.
            try {
                PSTMessage embedded = att.getEmbeddedPSTMessage();
                if (embedded != null) {
                    String s = EmlAnalyzer.header(new Item("x", "x")) + embedded.getBody();
                    return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) { }
            return null;
        }
    }

    private static String fmtFrom(PSTMessage msg) {
        String addr = null;
        try {
            addr = msg.getSenderEmailAddress();
        } catch (Exception ignored) { }
        String name = null;
        try {
            name = msg.getSenderName();
        } catch (Exception ignored) { }
        if (addr == null || addr.isBlank()) return name;
        if (name == null || name.isBlank()) return addr;
        return name + " <" + addr + ">";
    }

    private static String sanitize(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    private static final class Counter {
        int messages;
        int folders;
    }
}
