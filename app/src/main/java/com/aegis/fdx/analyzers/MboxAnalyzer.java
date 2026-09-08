package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * F-01: MBOX splitting. Each "From " separator line starts a new message, which is
 * emitted as its own element and re-analyzed by {@link EmlAnalyzer}.
 *
 * <p>Streamed line-by-line so a multi-gigabyte mailbox never lands in heap (N-04).
 */
public final class MboxAnalyzer implements Analyzer {

    @Override public String id() { return "mbox"; }

    @Override public List<String> mediaTypes() { return List.of("application/mbox"); }

    @Override
    public double sniff(byte[] header, String fileName) {
        if (Magic.extIn(fileName, "mbox")) return 0.95;
        if (Magic.startsWith(header, "From ")) return 0.8;
        return 0;
    }

    @Override public int priority() { return 40; }

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        item.mediaType("application/mbox");
        item.container(true);

        BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);

        StringBuilder buf = new StringBuilder(1 << 14);
        int seq = 0;
        String line;
        boolean started = false;

        while ((line = r.readLine()) != null) {
            if (line.startsWith("From ") && started) {
                seq = flush(item, sink, buf, seq);
                buf.setLength(0);
                continue;                    // drop the separator itself
            }
            if (line.startsWith("From ") && !started) {
                started = true;
                continue;
            }
            started = true;
            buf.append(line).append('\n');
        }
        seq = flush(item, sink, buf, seq);

        item.addMetadata("Messages", String.valueOf(seq));
        item.extractedText("MBOX archive containing " + seq + " messages.");
    }

    private int flush(Item parent, ChildSink sink, StringBuilder buf, int seq) {
        if (buf.length() == 0) return seq;
        int n = seq + 1;
        byte[] data = buf.toString().getBytes(StandardCharsets.UTF_8);

        Item child = new Item(parent.id() + "-M" + n, "message_" + n + ".eml");
        child.parentId(parent.id());
        child.depth(parent.depth() + 1);
        child.custodian(parent.custodian());
        child.sourcePath(parent.sourcePath());
        child.containerPath(
                (parent.containerPath() == null ? parent.name() : parent.containerPath())
                + " → message_" + n + ".eml");
        child.size(data.length);
        sink.emit(child, new ByteArrayInputStream(data));
        return n;
    }
}
