package com.aegis.fdx;

import com.aegis.fdx.analyzers.EmlAnalyzer;
import com.aegis.fdx.model.Item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End to end through {@link EmlAnalyzer}: a declared filename wins, a bare
 * Content-ID is recovered, and an attached message is named for its subject.
 */
class EmlAttachmentNamesTest {

    private static final String MESSAGE =
            "From: a@x.test\n"
            + "To: b@x.test\n"
            + "Subject: test\n"
            + "Message-ID: <1@x>\n"
            + "MIME-Version: 1.0\n"
            + "Content-Type: multipart/mixed; boundary=\"B\"\n"
            + "\n"
            + "--B\n"
            + "Content-Type: text/plain\n"
            + "\n"
            + "hello\n"
            + "--B\n"
            + "Content-Type: application/pdf; name=\"report.pdf\"\n"
            + "Content-Disposition: attachment; filename=\"report.pdf\"\n"
            + "Content-Transfer-Encoding: base64\n"
            + "\n"
            + "aGVsbG8=\n"
            + "--B\n"
            + "Content-Type: image/png\n"
            + "Content-Disposition: attachment\n"
            + "Content-ID: <image001.png@01DA>\n"
            + "Content-Transfer-Encoding: base64\n"
            + "\n"
            + "aGVsbG8=\n"
            + "--B\n"
            + "Content-Type: message/rfc822\n"
            + "\n"
            + "From: c@x.test\n"
            + "Subject: nested-subject-here\n"
            + "Message-ID: <2@x>\n"
            + "\n"
            + "nested body\n"
            + "--B--\n";

    @Test
    @DisplayName("Attachments keep their real names")
    void attachmentsKeepRealNames() throws Exception {
        Item item = new Item("E-000001", "test.eml");
        List<String> names = new ArrayList<>();
        new EmlAnalyzer().analyze(item,
                new ByteArrayInputStream(MESSAGE.getBytes(StandardCharsets.UTF_8)),
                (child, content) -> names.add(child.name()));
        assertEquals(List.of("report.pdf", "image001.png", "nested-subject-here.eml"), names);
        assertEquals(3, item.attachmentCount());
    }
}
