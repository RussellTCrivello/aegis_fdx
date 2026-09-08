package com.aegis.fdx.analyzers;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.spi.CharsetProvider;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Custom CharsetProvider registering common aliases encountered in Outlook PST/OST,
 * MSG, and MIME/EML email containers (such as RFC-1556 logical Hebrew {@code ISO-8859-8-I}).
 *
 * <p>Java SE's default charset database supports {@code ISO-8859-8} (visual order)
 * but does not register the {@code -I} (implicit/logical) or {@code -E} (explicit) MIME
 * aliases out of the box, causing {@link java.io.UnsupportedEncodingException} when
 * java-libpst attempts to decode string items. Registering them through this provider
 * allows {@code Charset.forName("ISO-8859-8-I")} and {@code new String(bytes, "ISO-8859-8-I")}
 * to resolve cleanly.
 */
public final class AegisCharsetProvider extends CharsetProvider {

    private static final Map<String, Charset> CHARSETS = new HashMap<>();

    static {
        registerAliases("ISO-8859-8-I", new String[]{
                "iso-8859-8-i", "iso_8859_8_i", "iso8859-8-i", "iso8859_8_i",
                "csiso88598i", "logical-hebrew", "hebrew-logical", "iso-ir-138-i"
        }, safeCharset("ISO-8859-8", "windows-1255", "ISO-8859-1"));

        registerAliases("ISO-8859-8-E", new String[]{
                "iso-8859-8-e", "iso_8859_8_e", "iso8859-8-e", "iso8859_8_e",
                "csiso88598e", "explicit-hebrew", "hebrew-explicit"
        }, safeCharset("ISO-8859-8", "windows-1255", "ISO-8859-1"));

        registerAliases("ISO-8859-6-I", new String[]{
                "iso-8859-6-i", "iso_8859_6_i", "iso8859-6-i", "iso8859_6_i",
                "csiso88596i", "logical-arabic", "arabic-logical"
        }, safeCharset("ISO-8859-6", "windows-1256", "ISO-8859-1"));

        registerAliases("ISO-8859-6-E", new String[]{
                "iso-8859-6-e", "iso_8859_6_e", "iso8859-6-e", "iso8859_6_e",
                "csiso88596e", "explicit-arabic", "arabic-explicit"
        }, safeCharset("ISO-8859-6", "windows-1256", "ISO-8859-1"));

        registerAliases("ISO-8859-1-Windows-3.1-Latin-1", new String[]{
                "iso-8859-1-windows-3.1-latin-1", "iso-8859-1-windows-3.0-latin-1"
        }, safeCharset("windows-1252", "ISO-8859-1"));

        registerAliases("KS_C_5601-1987", new String[]{
                "ks_c_5601-1987", "ks_c_5601_1987", "ksc5601", "ksc-5601", "ksc_5601",
                "korean", "ks-c-5601-1987", "ks_c_5601_1989"
        }, safeCharset("EUC-KR", "x-windows-949", "UTF-8"));

        registerAliases("windows-874", new String[]{
                "windows-874", "cp874", "cp-874", "ibm874", "ibm-874", "x-windows-874", "dos-874"
        }, safeCharset("x-windows-874", "TIS-620", "ISO-8859-1"));

        registerAliases("x-mac-roman", new String[]{
                "x-mac-roman", "mac-roman", "macintosh", "macroman"
        }, safeCharset("x-MacRoman", "ISO-8859-1"));

        registerAliases("x-unknown", new String[]{
                "x-unknown", "unknown", "unknown-8bit", "x-user-defined", "binary", "default"
        }, StandardCharsets.ISO_8859_1);
    }

    private static Charset safeCharset(String... candidates) {
        for (String c : candidates) {
            try {
                if (Charset.isSupported(c)) {
                    return Charset.forName(c);
                }
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void registerAliases(String canonicalName, String[] aliases, Charset target) {
        DelegatingCharset wrapper = new DelegatingCharset(canonicalName, aliases, target);
        CHARSETS.put(canonicalName.toLowerCase(Locale.ROOT), wrapper);
        for (String a : aliases) {
            CHARSETS.put(a.toLowerCase(Locale.ROOT), wrapper);
        }
    }

    @Override
    public Iterator<Charset> charsets() {
        return Collections.unmodifiableCollection(CHARSETS.values())
                .stream().distinct().iterator();
    }

    @Override
    public Charset charsetForName(String charsetName) {
        if (charsetName == null || charsetName.isBlank()) {
            return null;
        }
        String key = charsetName.trim().toLowerCase(Locale.ROOT);
        Charset cs = CHARSETS.get(key);
        if (cs != null) {
            return cs;
        }
        // Dynamic normalization fallback for loose formatting
        String norm = key.replace('_', '-');
        cs = CHARSETS.get(norm);
        if (cs != null) {
            return cs;
        }
        if (norm.startsWith("iso-8859-8")) {
            return CHARSETS.get("iso-8859-8-i");
        }
        if (norm.startsWith("iso-8859-6")) {
            return CHARSETS.get("iso-8859-6-i");
        }
        if (norm.contains("hebrew")) {
            return CHARSETS.get("iso-8859-8-i");
        }
        if (norm.contains("arabic")) {
            return CHARSETS.get("iso-8859-6-i");
        }
        if (norm.contains("korean") || norm.contains("ksc")) {
            return CHARSETS.get("ks_c_5601-1987");
        }
        if (norm.contains("unknown") || norm.contains("binary")) {
            return CHARSETS.get("x-unknown");
        }
        return null;
    }

    private static final class DelegatingCharset extends Charset {
        private final Charset target;

        DelegatingCharset(String canonicalName, String[] aliases, Charset target) {
            super(canonicalName, aliases);
            this.target = target;
        }

        @Override
        public boolean contains(Charset cs) {
            return target.contains(cs);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return target.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            return target.newEncoder();
        }
    }
}
