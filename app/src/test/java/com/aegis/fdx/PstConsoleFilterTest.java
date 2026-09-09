package com.aegis.fdx;

import com.aegis.fdx.analyzers.PstConsoleFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library's per-item diagnostics must never reach the console, and everything
 * else must pass through byte-identical.
 *
 * <p>All filtering tests run against a buffer via {@link PstConsoleFilter#wrap};
 * only the idempotence test touches {@code System.out}, and it deliberately leaves
 * the transparent filter installed: every other byte still passes through, so later
 * suites are unaffected except that their mailbox spam is suppressed too.
 */
class PstConsoleFilterTest {

    private static PrintStream wrapping(ByteArrayOutputStream buf) {
        return PstConsoleFilter.wrap(new PrintStream(buf, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Library lines are dropped and counted, other lines pass through")
    void dropsOnlyLibraryLines() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream f = wrapping(buf);
        PstConsoleFilter.resetThreadSuppressed();
        long before = PstConsoleFilter.suppressedTotal();

        f.println("Unknown message type: IPM.SkypeTeams.Message");
        f.println("progress: 12/31");
        f.println("Unknown message type: IPM.Note.Rules.OofTemplate.Microsoft");
        f.flush();

        assertEquals("progress: 12/31" + System.lineSeparator(),
                buf.toString(StandardCharsets.UTF_8));
        assertEquals(2, PstConsoleFilter.threadSuppressed());
        assertTrue(PstConsoleFilter.suppressedTotal() >= before + 2,
                "the JVM-wide total includes this thread's drops");
    }

    @Test
    @DisplayName("A line is only dropped when it was buffered whole")
    void partialLinesPassThrough() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream f = wrapping(buf);
        PstConsoleFilter.resetThreadSuppressed();

        // The flush pushes the prefix downstream mid-line, so by the time the
        // line completes it is too late to drop: it must pass through whole.
        f.print("Unknown message type: ");
        f.flush();
        f.println("IPM.SkypeTeams.Message");
        f.flush();

        assertEquals("Unknown message type: IPM.SkypeTeams.Message" + System.lineSeparator(),
                buf.toString(StandardCharsets.UTF_8));
        assertEquals(0, PstConsoleFilter.threadSuppressed());
    }

    @Test
    @DisplayName("Windows line endings do not defeat the match")
    void carriageReturnIsIgnored() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream f = wrapping(buf);
        PstConsoleFilter.resetThreadSuppressed();

        f.write("Unknown message type: IPM.Sharing.Message\r\n".getBytes(StandardCharsets.UTF_8));
        f.println("kept");
        f.flush();

        assertEquals("kept" + System.lineSeparator(), buf.toString(StandardCharsets.UTF_8));
        assertEquals(1, PstConsoleFilter.threadSuppressed());
    }

    @Test
    @DisplayName("Installing twice keeps the first wrapper")
    void installOnceIsIdempotent() {
        PstConsoleFilter.installOnce();
        PrintStream first = System.out;
        PstConsoleFilter.installOnce();
        assertSame(first, System.out);
    }
}
