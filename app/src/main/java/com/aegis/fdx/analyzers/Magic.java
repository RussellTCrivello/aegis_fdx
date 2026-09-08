package com.aegis.fdx.analyzers;

import java.util.Locale;

/** Shared magic-number helpers used by the built-in analyzers' {@code sniff}. */
public final class Magic {

    private Magic() { }

    public static boolean startsWith(byte[] h, int... sig) {
        if (h.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if ((h[i] & 0xFF) != sig[i]) return false;
        }
        return true;
    }

    public static boolean startsWith(byte[] h, String ascii) {
        if (h.length < ascii.length()) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if ((h[i] & 0xFF) != ascii.charAt(i)) return false;
        }
        return true;
    }

    public static boolean contains(byte[] h, String ascii, int limit) {
        byte[] needle = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int max = Math.min(h.length, limit) - needle.length;
        outer:
        for (int i = 0; i <= max; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (h[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    public static String ext(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static boolean extIn(String fileName, String... exts) {
        String e = ext(fileName);
        for (String x : exts) if (x.equals(e)) return true;
        return false;
    }

    // --- common container signatures ---

    /** ZIP / OOXML / ODF / JAR all start PK\003\004. */
    public static boolean isZip(byte[] h) {
        return startsWith(h, 0x50, 0x4B, 0x03, 0x04)
                || startsWith(h, 0x50, 0x4B, 0x05, 0x06)
                || startsWith(h, 0x50, 0x4B, 0x07, 0x08);
    }

    /** OLE2 compound file: DOC/XLS/PPT/MSG. */
    public static boolean isOle2(byte[] h) {
        return startsWith(h, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
    }

    public static boolean isPdf(byte[] h) {
        return startsWith(h, "%PDF-");
    }

    public static boolean isPst(byte[] h) {
        return startsWith(h, "!BDN");
    }

    public static boolean isRar(byte[] h) {
        return startsWith(h, "Rar!");
    }

    public static boolean is7z(byte[] h) {
        return startsWith(h, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C);
    }

    public static boolean isGzip(byte[] h) {
        return startsWith(h, 0x1F, 0x8B);
    }

    public static boolean isBzip2(byte[] h) {
        return startsWith(h, "BZh");
    }

    public static boolean isXz(byte[] h) {
        return startsWith(h, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00);
    }

    /** TAR: "ustar" at offset 257. */
    public static boolean isTar(byte[] h) {
        if (h.length < 265) return false;
        return h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r';
    }
}
