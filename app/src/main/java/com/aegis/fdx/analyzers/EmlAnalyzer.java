package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.dom.address.Address;
import org.apache.james.mime4j.dom.address.AddressList;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.stream.MimeConfig;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * F-01 / F-09 / F-10: RFC-822 (EML) parsing via Apache mime4j.
 *
 * <p>Populates the email metadata block and emits every attachment as an
 * independent, searchable element linked back to this message.
 */
public final class EmlAnalyzer implements Analyzer {

    @Override public String id() { return "mime4j-eml"; }

    @Override public List<String> mediaTypes() { return List.of("message/rfc822"); }

    @Override
    public double sniff(byte[] header, String fileName) {
        if (Magic.extIn(fileName, "eml")) return 0.9;
        // Header sniff: a bare RFC-822 stream starts with common headers.
        if (Magic.contains(header, "\nFrom: ", 2048)
                || Magic.startsWith(header, "From: ")
                || Magic.startsWith(header, "Received: ")
                || Magic.startsWith(header, "Return-Path: ")
                || Magic.startsWith(header, "Message-ID: ")) {
            return 0.75;
        }
        return 0;
    }

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        item.mediaType("message/rfc822");

        MimeConfig config = MimeConfig.custom()
                .setMaxLineLen(-1)
                .setMaxHeaderLen(-1)
                .setMaxHeaderCount(-1)
                .build();
        DefaultMessageBuilder builder = new DefaultMessageBuilder();
        builder.setMimeEntityConfig(config);

        Message msg = builder.parseMessage(in);
        try {
            item.subject(msg.getSubject());
            item.from(fmtMailboxList(msg.getFrom()));
            item.to(fmtAddressList(msg.getTo()));
            item.cc(fmtAddressList(msg.getCc()));
            if (msg.getBcc() != null) item.addMetadata("Bcc", fmtAddressList(msg.getBcc()));
            if (msg.getDate() != null) item.sentDate(msg.getDate().toInstant());
            item.messageId(msg.getMessageId());
            if (msg.getHeader() != null && msg.getHeader().getField("In-Reply-To") != null) {
                item.addMetadata("In-Reply-To", msg.getHeader().getField("In-Reply-To").getBody());
            }

            StringBuilder text = new StringBuilder();
            List<Entity> attachments = new ArrayList<>();
            walk(msg.getBody(), text, attachments);

            item.extractedText(header(item) + text.toString().strip());
            item.attachmentCount(attachments.size());

            int n = 0;
            for (Entity att : attachments) {
                n++;
                String name = att.getFilename() != null ? att.getFilename() : "attachment_" + n;
                byte[] data = bodyBytes(att);
                if (data == null) continue;
                Item child = new Item(item.id() + "-A" + n, name);
                child.parentId(item.id());
                child.depth(item.depth() + 1);
                child.custodian(item.custodian());
                child.containerPath(join(item.containerPath(), item.name()) + " → " + name);
                child.sourcePath(item.sourcePath());
                child.size(data.length);
                child.attachedFrom(item.id());
                sink.emit(child, new ByteArrayInputStream(data));
            }
        } finally {
            try { msg.dispose(); } catch (Exception ignored) { }
        }
    }

    /** Header block is prepended so From/To/Subject are hit by free-text search. */
    static String header(Item item) {
        StringBuilder sb = new StringBuilder();
        if (item.subject() != null) sb.append("Subject: ").append(item.subject()).append('\n');
        if (item.from() != null) sb.append("From: ").append(item.from()).append('\n');
        if (item.to() != null) sb.append("To: ").append(item.to()).append('\n');
        if (item.cc() != null && !item.cc().isBlank()) sb.append("Cc: ").append(item.cc()).append('\n');
        if (sb.length() > 0) sb.append('\n');
        return sb.toString();
    }

    private void walk(Body body, StringBuilder text, List<Entity> attachments) throws Exception {
        if (body instanceof TextBody tb) {
            try (var r = tb.getReader()) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) text.append(buf, 0, n);
                text.append('\n');
            }
        } else if (body instanceof Multipart mp) {
            for (Entity part : mp.getBodyParts()) {
                boolean isAttachment = part.getFilename() != null
                        || "attachment".equalsIgnoreCase(part.getDispositionType());
                if (isAttachment) {
                    attachments.add(part);
                } else {
                    walk(part.getBody(), text, attachments);
                }
            }
        } else if (body instanceof Message nested) {
            // message/rfc822 part: treat as an attachment so it becomes its own element
            attachments.add(nested);
        }
    }

    private static byte[] bodyBytes(Entity e) {
        try {
            Body b = e.getBody();
            var out = new java.io.ByteArrayOutputStream();
            if (b instanceof org.apache.james.mime4j.dom.BinaryBody bin) {
                try (InputStream is = bin.getInputStream()) { is.transferTo(out); }
            } else if (b instanceof TextBody tb) {
                try (InputStream is = tb.getInputStream()) { is.transferTo(out); }
            } else if (b instanceof Message m) {
                org.apache.james.mime4j.message.DefaultMessageWriter w =
                        new org.apache.james.mime4j.message.DefaultMessageWriter();
                w.writeMessage(m, out);
            } else {
                return null;
            }
            return out.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    static String fmtMailboxList(MailboxList list) {
        if (list == null || list.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Mailbox m : list) parts.add(fmtMailbox(m));
        return String.join(", ", parts);
    }

    static String fmtAddressList(AddressList list) {
        if (list == null || list.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Address a : list) {
            if (a instanceof Mailbox m) parts.add(fmtMailbox(m));
            else parts.add(a.toString());
        }
        return String.join(", ", parts);
    }

    private static String fmtMailbox(Mailbox m) {
        String addr = m.getAddress();
        String name = m.getName();
        return (name == null || name.isBlank()) ? addr : name + " <" + addr + ">";
    }

    static String join(String container, String name) {
        return container == null || container.isBlank() ? name : container;
    }
}
