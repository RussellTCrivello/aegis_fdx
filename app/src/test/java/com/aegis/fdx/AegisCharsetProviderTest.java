package com.aegis.fdx;

import com.aegis.fdx.analyzers.AegisCharsetProvider;
import com.aegis.fdx.analyzers.PstAnalyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.spi.CharsetProvider;
import java.util.Iterator;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AegisCharsetProvider} and charset aliasing in PST/OST message handling.
 */
public final class AegisCharsetProviderTest {

    @Test
    @DisplayName("ISO-8859-8-I is supported by java.nio.charset.Charset")
    void iso88598iIsSupported() {
        assertTrue(Charset.isSupported("ISO-8859-8-I"), "ISO-8859-8-I must be supported");
        assertTrue(Charset.isSupported("iso-8859-8-i"), "iso-8859-8-i (lowercase) must be supported");
        assertTrue(Charset.isSupported("ISO_8859_8_I"), "ISO_8859_8_I (underscores) must be supported");
        assertTrue(Charset.isSupported("iso88598i"), "iso88598i (alphanumeric only) must be supported");

        Charset cs = Charset.forName("ISO-8859-8-I");
        assertNotNull(cs, "Charset.forName('ISO-8859-8-I') must not be null");
    }

    @Test
    @DisplayName("ISO-8859-8-I decodes Hebrew bytes correctly")
    void decodesHebrewBytes() {
        Charset cs = Charset.forName("ISO-8859-8-I");
        // Hebrew word 'שלום' (Shalom) in ISO-8859-8 / visual Hebrew:
        // ש = 0xF9, ל = 0xEC, ו = 0xE5, ם = 0xED
        byte[] hebrewBytes = new byte[]{(byte) 0xF9, (byte) 0xEC, (byte) 0xE5, (byte) 0xED};
        String decoded = new String(hebrewBytes, cs);
        assertEquals("\u05E9\u05DC\u05D5\u05DD", decoded);

        byte[] reEncoded = decoded.getBytes(cs);
        assertEquals(hebrewBytes.length, reEncoded.length);
        for (int i = 0; i < hebrewBytes.length; i++) {
            assertEquals(hebrewBytes[i], reEncoded[i]);
        }
    }

    @Test
    @DisplayName("AegisCharsetProvider resolves legacy Outlook & MIME aliases")
    void resolvesLegacyAliases() {
        AegisCharsetProvider provider = new AegisCharsetProvider();

        // ISO-8859-8 variants
        assertNotNull(provider.charsetForName("ISO-8859-8-I"));
        assertNotNull(provider.charsetForName("iso-8859-8-e"));
        assertNotNull(provider.charsetForName("iso_8859_8"));

        // ISO-8859-6 variants
        assertNotNull(provider.charsetForName("ISO-8859-6-I"));
        assertNotNull(provider.charsetForName("iso-8859-6-e"));

        // Korean alias
        assertNotNull(provider.charsetForName("ks_c_5601-1987"));
        assertNotNull(provider.charsetForName("ksc5601"));

        // Thai alias
        assertNotNull(provider.charsetForName("windows-874"));

        // Mac Roman alias
        assertNotNull(provider.charsetForName("x-mac-roman"));
        assertNotNull(provider.charsetForName("macintosh"));

        // Fallback / unknown alias
        assertNotNull(provider.charsetForName("x-unknown"));
        assertNotNull(provider.charsetForName("unknown-8bit"));

        // Invalid names return null
        assertNull(provider.charsetForName(null));
        assertNull(provider.charsetForName(""));
        assertNull(provider.charsetForName("completely-nonexistent-charset-xyz-999"));
    }

    @Test
    @DisplayName("AegisCharsetProvider iterator yields available charsets")
    void iteratorYieldsCharsets() {
        AegisCharsetProvider provider = new AegisCharsetProvider();
        Iterator<Charset> iter = provider.charsets();
        assertNotNull(iter);
        assertTrue(iter.hasNext());
        boolean foundHebrew = false;
        while (iter.hasNext()) {
            Charset c = iter.next();
            assertNotNull(c);
            if (c.name().equalsIgnoreCase("ISO-8859-8-I") || c.aliases().contains("ISO-8859-8-I")) {
                foundHebrew = true;
            }
        }
        assertTrue(foundHebrew, "Iterator should include ISO-8859-8-I wrapper or alias");
    }

    @Test
    @DisplayName("ServiceLoader discovers AegisCharsetProvider")
    void serviceLoaderDiscoversProvider() {
        ServiceLoader<CharsetProvider> loader = ServiceLoader.load(CharsetProvider.class);
        boolean found = false;
        for (CharsetProvider cp : loader) {
            if (cp instanceof AegisCharsetProvider) {
                found = true;
                break;
            }
        }
        assertTrue(found, "META-INF/services/java.nio.charset.spi.CharsetProvider must register AegisCharsetProvider");
    }

    @Test
    @DisplayName("PstAnalyzer recognizes PST and OST file extensions")
    void pstAnalyzerExtensions() {
        // The analyzer SPI recognizes types through sniff(), not a supports()
        // predicate: magic bytes win (1.0), then extension (0.5), else 0.
        PstAnalyzer analyzer = new PstAnalyzer();
        byte[] noHeader = new byte[0];
        assertTrue(analyzer.sniff(noHeader, "archive.pst") > 0);
        assertTrue(analyzer.sniff(noHeader, "BACKUP.PST") > 0);
        assertTrue(analyzer.sniff(noHeader, "mailbox.ost") > 0);
        assertTrue(analyzer.sniff(noHeader, "MAILBOX.OST") > 0);
        assertTrue(analyzer.sniff(noHeader, "document.pdf") == 0);
        assertTrue(analyzer.sniff(noHeader, "data.docx") == 0);
        assertTrue(analyzer.sniff(noHeader, "") == 0);
    }

    // ================================================================== main

    public static void main(String[] args) {
        AegisCharsetProviderTest t = new AegisCharsetProviderTest();
        String[] names = {
            "ISO-8859-8-I is supported by java.nio.charset.Charset",
            "ISO-8859-8-I decodes Hebrew bytes correctly",
            "AegisCharsetProvider resolves legacy Outlook & MIME aliases",
            "AegisCharsetProvider iterator yields available charsets",
            "ServiceLoader discovers AegisCharsetProvider",
            "PstAnalyzer recognizes PST and OST file extensions"
        };
        Runnable[] body = {
            t::iso88598iIsSupported,
            t::decodesHebrewBytes,
            t::resolvesLegacyAliases,
            t::iteratorYieldsCharsets,
            t::serviceLoaderDiscoversProvider,
            t::pstAnalyzerExtensions
        };
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < body.length; i++) {
            try {
                body[i].run();
                System.out.println("  ok    " + names[i]);
                passed++;
            } catch (Throwable e) {
                System.out.println("  FAIL  " + names[i] + " — " + e);
                e.printStackTrace(System.out);
                failed++;
            }
        }
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
