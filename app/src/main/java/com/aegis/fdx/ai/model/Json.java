package com.aegis.fdx.ai.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader/writer.
 *
 * <p>Deliberately hand-written: the application ships offline with a fixed dependency
 * set, and the AI layer only needs to exchange simple objects with a local runtime.
 * Adding a JSON library for that would be a heavier change than the problem warrants.
 *
 * <p>Supports objects, arrays, strings, numbers, booleans and null — the whole of the
 * wire format used here.
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- writing

    /** Escapes and quotes a string. */
    public static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------- reading

    /**
     * Parses a JSON document.
     *
     * @return {@code Map<String,Object>}, {@code List<Object>}, {@code String},
     *         {@code Double}, {@code Boolean} or null
     */
    public static Object parse(String json) {
        if (json == null) {
            return null;
        }
        Cursor c = new Cursor(json);
        c.ws();
        Object v = c.value();
        return v;
    }

    /** Parses and casts to an object, or returns an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    /** Dotted-path lookup: {@code get(root, "message.content")}. */
    @SuppressWarnings("unchecked")
    public static Object get(Object root, String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = ((Map<String, Object>) m).get(part);
            } else {
                return null;
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    public static String getString(Object root, String path) {
        Object v = get(root, path);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(Object root, String path) {
        Object v = get(root, path);
        return (v instanceof List) ? (List<Object>) v : List.of();
    }

    private static final class Cursor {
        private final String s;
        private int i;

        Cursor(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            ws();
            if (i >= s.length()) {
                return null;
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (i < s.length()) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') {
                    break;
                }
                String k = string();
                ws();
                if (i < s.length() && s.charAt(i) == ':') {
                    i++;
                }
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                }
                break;
            }
            return m;
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (i < s.length()) {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                }
                break;
            }
            return l;
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object literal(String word, Object val) {
            if (s.startsWith(word, i)) {
                i += word.length();
                return val;
            }
            i++;
            return null;
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            try {
                return Double.valueOf(s.substring(start, i));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
