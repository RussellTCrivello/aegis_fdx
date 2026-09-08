package com.aegis.fdx.engine;

import com.aegis.fdx.model.Item;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * F-15 query language, evaluated in-memory by the prototype and mapped 1:1 onto
 * Lucene {@code BooleanQuery} / {@code TermRangeQuery} in the production engine.
 *
 * <p>Supported: bare terms, "quoted phrases", wildcards {@code * ?}, fuzzy
 * {@code term~} / {@code term~2}, proximity {@code "a b"~5}, AND/OR/NOT with
 * parentheses, field queries {@code from: to: subject: type: path: tag: status:
 * custodian:}, date ranges {@code date:[2023-01-01 TO 2023-12-31]} and regular
 * expressions {@code /re/}.
 */
public final class QueryParser {

    private final String input;
    private int pos;

    private QueryParser(String input) {
        this.input = input;
    }

    public static Predicate<Item> parse(String query) {
        if (query == null || query.isBlank()) return it -> true;
        QueryParser p = new QueryParser(query.trim());
        try {
            Predicate<Item> pred = p.parseOr();
            return pred == null ? it -> true : pred;
        } catch (RuntimeException e) {
            // N-05: a malformed query degrades to a literal keyword search.
            String lit = query.toLowerCase(Locale.ROOT);
            return it -> haystack(it).contains(lit);
        }
    }

    // ---- grammar ---------------------------------------------------------

    private Predicate<Item> parseOr() {
        Predicate<Item> left = parseAnd();
        while (true) {
            skipWs();
            if (eat("OR ") || eat("|| ")) {
                Predicate<Item> right = parseAnd();
                Predicate<Item> l = left;
                left = it -> l.test(it) || right.test(it);
            } else {
                return left;
            }
        }
    }

    private Predicate<Item> parseAnd() {
        Predicate<Item> left = parseUnary();
        while (true) {
            skipWs();
            int save = pos;
            boolean explicit = eat("AND ") || eat("&& ");
            if (!explicit) {
                if (pos >= input.length() || peekIs(')') || lookingAt("OR ")) { pos = save; return left; }
            }
            Predicate<Item> right = parseUnary();
            Predicate<Item> l = left;
            left = it -> l.test(it) && right.test(it);
        }
    }

    private Predicate<Item> parseUnary() {
        skipWs();
        if (eat("NOT ") || eat("-") || eat("!")) {
            Predicate<Item> inner = parseUnary();
            return it -> !inner.test(it);
        }
        return parseAtom();
    }

    private Predicate<Item> parseAtom() {
        skipWs();
        if (peekIs('(')) {
            pos++;
            Predicate<Item> inner = parseOr();
            skipWs();
            if (peekIs(')')) pos++;
            return inner;
        }
        if (peekIs('/')) return regex();
        if (peekIs('"')) return phrase();

        String token = readToken();
        if (token.isEmpty()) throw new IllegalStateException("empty token");

        int colon = token.indexOf(':');
        if (colon > 0) {
            String field = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = token.substring(colon + 1);
            if (value.isEmpty()) { skipWs(); value = peekIs('"') ? readQuoted() : readToken(); }
            if (value.startsWith("[")) return dateRange(field, value);
            return fieldMatch(field, value);
        }
        return termMatch(token);
    }

    private Predicate<Item> regex() {
        pos++; // opening /
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && input.charAt(pos) != '/') sb.append(input.charAt(pos++));
        if (pos < input.length()) pos++;
        try {
            Pattern pat = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
            return it -> pat.matcher(haystack(it)).find();
        } catch (PatternSyntaxException e) {
            return it -> false;
        }
    }

    private Predicate<Item> phrase() {
        String text = readQuoted();
        skipWs();
        if (peekIs('~')) {                       // proximity: "a b"~N
            pos++;
            int slop = readInt(3);
            String[] words = text.toLowerCase(Locale.ROOT).split("\\s+");
            return it -> withinSlop(haystack(it), words, slop);
        }
        String needle = text.toLowerCase(Locale.ROOT);
        return it -> haystack(it).contains(needle);
    }

    private Predicate<Item> termMatch(String token) {
        if (token.endsWith("~") || token.matches(".*~\\d+$")) {   // fuzzy
            int tilde = token.lastIndexOf('~');
            String term = token.substring(0, tilde).toLowerCase(Locale.ROOT);
            int maxEdits = tilde + 1 < token.length()
                    ? Integer.parseInt(token.substring(tilde + 1)) : 2;
            return it -> anyWord(haystack(it), w -> editDistance(w, term) <= maxEdits);
        }
        if (token.indexOf('*') >= 0 || token.indexOf('?') >= 0) { // wildcard
            Pattern pat = Pattern.compile(wildcardToRegex(token), Pattern.CASE_INSENSITIVE);
            return it -> anyWord(haystack(it), w -> pat.matcher(w).matches());
        }
        String needle = token.toLowerCase(Locale.ROOT);
        return it -> haystack(it).contains(needle);
    }

    private Predicate<Item> fieldMatch(String field, String rawValue) {
        String value = rawValue.toLowerCase(Locale.ROOT);
        boolean wild = value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
        Pattern pat = wild ? Pattern.compile(wildcardToRegex(value), Pattern.CASE_INSENSITIVE) : null;

        return it -> {
            String raw = switch (field) {
                case "from" -> nz(it.from());
                case "to" -> nz(it.to());
                case "cc", "copy" -> nz(it.cc());
                case "subject" -> nz(it.subject());
                case "type", "ext" -> nz(it.extension()) + " " + nz(it.mediaType());
                case "path" -> nz(it.sourcePath()) + " " + nz(it.containerPath());
                case "tag" -> String.join(" ", it.tags());
                case "status" -> it.status().label();
                case "custodian" -> nz(it.custodian());
                case "name" -> nz(it.name());
                case "md5" -> nz(it.md5());
                case "sha256" -> nz(it.sha256());
                case "hasattachments" -> String.valueOf(it.hasAttachments());
                default -> haystack(it);
            };
            String actual = raw.toLowerCase(Locale.ROOT);
            return pat != null ? pat.matcher(actual).find() : actual.contains(value);
        };
    }

    private Predicate<Item> dateRange(String field, String firstChunk) {
        StringBuilder sb = new StringBuilder(firstChunk);
        while (pos < input.length() && sb.indexOf("]") < 0) sb.append(input.charAt(pos++));
        String body = sb.toString().replace("[", "").replace("]", "").trim();
        String[] parts = body.split("(?i)\\s+TO\\s+");
        Instant lo = parseDate(parts.length > 0 ? parts[0] : null, Instant.MIN);
        Instant hi = parseDate(parts.length > 1 ? parts[1] : null, Instant.MAX);
        return it -> {
            Instant v = "sent".equals(field) ? it.sentDate()
                    : "created".equals(field) ? it.created() : it.modified();
            if (v == null) v = it.created();
            return v != null && !v.isBefore(lo) && !v.isAfter(hi);
        };
    }

    private static Instant parseDate(String s, Instant fallback) {
        if (s == null || s.isBlank() || "*".equals(s)) return fallback;
        try {
            return LocalDate.parse(s.trim()).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- helpers ---------------------------------------------------------

    static String haystack(Item it) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(nz(it.name())).append(' ').append(nz(it.subject())).append(' ')
          .append(nz(it.from())).append(' ').append(nz(it.to())).append(' ')
          .append(nz(it.cc())).append(' ').append(nz(it.sourcePath())).append(' ')
          .append(nz(it.custodian())).append(' ').append(nz(it.extractedText())).append(' ')
          .append(nz(it.notes())).append(' ').append(String.join(" ", it.tags()));
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean anyWord(String text, Predicate<String> p) {
        for (String w : text.split("[^\\p{L}\\p{N}_@.]+")) {
            if (!w.isEmpty() && p.test(w)) return true;
        }
        return false;
    }

    private static boolean withinSlop(String text, String[] words, int slop) {
        if (words.length < 2) return words.length == 0 || text.contains(words[0]);
        List<String> toks = new ArrayList<>();
        for (String w : text.split("[^\\p{L}\\p{N}_@.]+")) if (!w.isEmpty()) toks.add(w);
        for (int i = 0; i < toks.size(); i++) {
            if (!toks.get(i).equals(words[0])) continue;
            int cursor = i, matched = 1;
            for (int w = 1; w < words.length; w++) {
                int found = -1;
                for (int j = cursor + 1; j < Math.min(toks.size(), cursor + slop + 2); j++) {
                    if (toks.get(j).equals(words[w])) { found = j; break; }
                }
                if (found < 0) break;
                cursor = found; matched++;
            }
            if (matched == words.length) return true;
        }
        return false;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    static String wildcardToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private void skipWs() { while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++; }
    private boolean peekIs(char c) { return pos < input.length() && input.charAt(pos) == c; }

    private boolean lookingAt(String s) {
        return input.regionMatches(true, pos, s, 0, s.length());
    }

    private boolean eat(String s) {
        if (lookingAt(s)) { pos += s.length(); return true; }
        return false;
    }

    private int readInt(int def) {
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) sb.append(input.charAt(pos++));
        return sb.isEmpty() ? def : Integer.parseInt(sb.toString());
    }

    private String readQuoted() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && input.charAt(pos) != '"') sb.append(input.charAt(pos++));
        if (pos < input.length()) pos++;
        return sb.toString();
    }

    private String readToken() {
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c) || c == ')' ) break;
            if (c == '"' && sb.isEmpty()) break;
            sb.append(c);
            pos++;
            if (c == ':') break;   // let caller grab the value (may be quoted)
        }
        String t = sb.toString();
        if (t.endsWith(":")) {
            skipWs();
            String val = peekIs('"') ? '"' + readQuoted() + '"' : readValueToken();
            return t + val.replace("\"", "");
        }
        return t;
    }

    private String readValueToken() {
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c) && sb.indexOf("[") < 0) break;
            if (c == ')') break;
            sb.append(c);
            pos++;
            if (c == ']') break;
        }
        return sb.toString();
    }
}
