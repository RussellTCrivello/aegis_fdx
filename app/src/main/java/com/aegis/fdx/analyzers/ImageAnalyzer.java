package com.aegis.fdx.analyzers;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.spi.Analyzer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * F-02 / F-09: image metadata, including EXIF date and GPS.
 *
 * <p>A minimal, dependency-free EXIF/TIFF IFD reader — enough for the mandated
 * fields (dimensions, capture date, geo location) without pulling another library.
 * Images are flagged {@code needsOcr} so the OCR stage can pick them up (F-08).
 */
public final class ImageAnalyzer implements Analyzer {

    @Override public String id() { return "image-exif"; }

    @Override
    public List<String> mediaTypes() {
        return List.of("image/jpeg", "image/png", "image/gif", "image/bmp", "image/tiff");
    }

    @Override
    public double sniff(byte[] h, String fileName) {
        if (Magic.startsWith(h, 0xFF, 0xD8, 0xFF)) return 1.0;                       // JPEG
        if (Magic.startsWith(h, 0x89, 0x50, 0x4E, 0x47)) return 1.0;                 // PNG
        if (Magic.startsWith(h, "GIF8")) return 1.0;                                 // GIF
        if (Magic.startsWith(h, "BM")) return 0.8;                                   // BMP
        if (Magic.startsWith(h, 0x49, 0x49, 0x2A, 0x00)
                || Magic.startsWith(h, 0x4D, 0x4D, 0x00, 0x2A)) return 0.95;         // TIFF
        return Magic.extIn(fileName, "jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff") ? 0.5 : 0;
    }

    @Override
    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        byte[] data = in.readAllBytes();
        item.mediaType(mediaTypeOf(data, item.name()));
        item.needsOcr(true);                     // F-08: candidate for the OCR stage

        int[] dim = dimensions(data);
        if (dim != null) {
            item.addMetadata("Width", String.valueOf(dim[0]));
            item.addMetadata("Height", String.valueOf(dim[1]));
        }

        if (Magic.startsWith(data, 0xFF, 0xD8, 0xFF)) readJpegExif(item, data);

        if (item.extractedText().isEmpty()) {
            StringBuilder sb = new StringBuilder(item.name());
            item.metadata().forEach((k, v) -> sb.append(' ').append(k).append(": ").append(v));
            item.extractedText(sb.toString());
        }
    }

    private static String mediaTypeOf(byte[] h, String name) {
        if (Magic.startsWith(h, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (Magic.startsWith(h, 0x89, 0x50, 0x4E, 0x47)) return "image/png";
        if (Magic.startsWith(h, "GIF8")) return "image/gif";
        if (Magic.startsWith(h, "BM")) return "image/bmp";
        if (Magic.startsWith(h, 0x49, 0x49, 0x2A, 0x00)
                || Magic.startsWith(h, 0x4D, 0x4D, 0x00, 0x2A)) return "image/tiff";
        return "image/" + Magic.ext(name);
    }

    /** PNG IHDR and JPEG SOFn dimension read. */
    private static int[] dimensions(byte[] d) {
        if (Magic.startsWith(d, 0x89, 0x50, 0x4E, 0x47) && d.length > 24) {
            return new int[]{be32(d, 16), be32(d, 20)};
        }
        if (Magic.startsWith(d, 0xFF, 0xD8, 0xFF)) {
            int i = 2;
            while (i + 9 < d.length) {
                if ((d[i] & 0xFF) != 0xFF) { i++; continue; }
                int marker = d[i + 1] & 0xFF;
                int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                boolean sof = (marker >= 0xC0 && marker <= 0xCF)
                        && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (sof) {
                    int h = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                    int w = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                    return new int[]{w, h};
                }
                i += 2 + len;
            }
        }
        return null;
    }

    /** Locates APP1/Exif and walks IFD0 + GPS IFD. */
    private static void readJpegExif(Item item, byte[] d) {
        int i = 2;
        while (i + 4 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) { i++; continue; }
            int marker = d[i + 1] & 0xFF;
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (marker == 0xE1 && i + 10 < d.length
                    && d[i + 4] == 'E' && d[i + 5] == 'x' && d[i + 6] == 'i' && d[i + 7] == 'f') {
                parseTiff(item, d, i + 10, len - 8);
                return;
            }
            if (marker == 0xDA) return;      // start of scan — no EXIF
            i += 2 + len;
        }
    }

    private static void parseTiff(Item item, byte[] d, int base, int len) {
        try {
            if (base + 8 > d.length) return;
            boolean le = (d[base] & 0xFF) == 0x49;
            int ifd0 = (int) num(d, base + 4, 4, le);
            int gpsOffset = -1;

            int p = base + ifd0;
            if (p + 2 > d.length) return;
            int count = (int) num(d, p, 2, le);
            p += 2;
            for (int k = 0; k < count && p + 12 <= d.length; k++, p += 12) {
                int tag = (int) num(d, p, 2, le);
                int type = (int) num(d, p + 2, 2, le);
                int n = (int) num(d, p + 4, 4, le);
                int valOff = p + 8;
                int size = typeSize(type) * n;
                int dataAt = size <= 4 ? valOff : base + (int) num(d, valOff, 4, le);

                switch (tag) {
                    case 0x010F -> put(item, "Camera-Make", ascii(d, dataAt, n));
                    case 0x0110 -> put(item, "Camera-Model", ascii(d, dataAt, n));
                    case 0x0131 -> put(item, "Software", ascii(d, dataAt, n));
                    case 0x0132 -> put(item, "EXIF-DateTime", ascii(d, dataAt, n));
                    case 0x013B -> put(item, "Artist", ascii(d, dataAt, n));
                    case 0x8825 -> gpsOffset = (int) num(d, valOff, 4, le);
                    default -> { }
                }
            }
            if (gpsOffset > 0) parseGps(item, d, base, base + gpsOffset, le);
        } catch (RuntimeException ignored) {
            // Malformed EXIF must never fail the element (N-05).
        }
    }

    private static void parseGps(Item item, byte[] d, int base, int p, boolean le) {
        if (p + 2 > d.length) return;
        int count = (int) num(d, p, 2, le);
        p += 2;
        double lat = Double.NaN, lon = Double.NaN;
        String latRef = "N", lonRef = "E";

        for (int k = 0; k < count && p + 12 <= d.length; k++, p += 12) {
            int tag = (int) num(d, p, 2, le);
            int type = (int) num(d, p + 2, 2, le);
            int n = (int) num(d, p + 4, 4, le);
            int valOff = p + 8;
            int size = typeSize(type) * n;
            int at = size <= 4 ? valOff : base + (int) num(d, valOff, 4, le);

            switch (tag) {
                case 1 -> latRef = ascii(d, at, n).trim();
                case 2 -> lat = dms(d, at, le);
                case 3 -> lonRef = ascii(d, at, n).trim();
                case 4 -> lon = dms(d, at, le);
                default -> { }
            }
        }
        if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
            if ("S".equalsIgnoreCase(latRef)) lat = -lat;
            if ("W".equalsIgnoreCase(lonRef)) lon = -lon;
            item.geoLocation(String.format("%.6f, %.6f", lat, lon));
            item.addMetadata("GPS", item.geoLocation());
        }
    }

    private static double dms(byte[] d, int at, boolean le) {
        if (at + 24 > d.length) return Double.NaN;
        double deg = rational(d, at, le);
        double min = rational(d, at + 8, le);
        double sec = rational(d, at + 16, le);
        return deg + min / 60.0 + sec / 3600.0;
    }

    private static double rational(byte[] d, int at, boolean le) {
        long n = num(d, at, 4, le);
        long den = num(d, at + 4, 4, le);
        return den == 0 ? 0 : (double) n / den;
    }

    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> 1;
        };
    }

    private static long num(byte[] d, int off, int len, boolean le) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            int b = d[off + (le ? i : len - 1 - i)] & 0xFF;
            v |= ((long) b) << (8 * i);
        }
        return v;
    }

    private static int be32(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static String ascii(byte[] d, int off, int n) {
        if (off < 0 || off + n > d.length || n <= 0) return "";
        int end = off + n;
        while (end > off && d[end - 1] == 0) end--;
        return new String(d, off, end - off, StandardCharsets.ISO_8859_1);
    }

    private static void put(Item item, String k, String v) {
        if (v != null && !v.isBlank()) item.addMetadata(k, v);
    }
}
