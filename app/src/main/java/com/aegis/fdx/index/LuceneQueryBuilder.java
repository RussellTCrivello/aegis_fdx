package com.aegis.fdx.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.QueryBuilder;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * F-15: compiles the AEGIS query syntax into native Lucene queries.
 *
 * <p>Deliberately hand-written rather than using Lucene's {@code QueryParser}: the
 * required grammar (bare regex literals {@code /re/}, {@code date:[a TO b]} over
 * {@link LongPoint}, field aliases) is not what the stock parser accepts, and the
 * milestone-1 semantics are already locked down by tests.
 *
 * <p>Grammar and precedence are identical to
 * {@code com.aegis.fdx.engine.QueryParser}; that class remains the in-memory
 * reference implementation used by the unit tests, and this one is its Lucene
 * counterpart. A differential test asserts the two agree.
 */
public final class LuceneQueryBuilder {

    private final Analyzer analyzer;
    private final QueryBuilder phraseBuilder;
    private final String input;
    private int pos;

    private LuceneQueryBuilder(String input, Analyzer analyzer) {
        this.input = input;
        this.analyzer = analyzer;
        this.phraseBuilder = new QueryBuilder(analyzer);
    }

    /**
     * Compiles a user query, rejecting anything it cannot represent exactly.
     *
     * <p>This deliberately does <em>not</em> fall back to a free-text search on error.
     * The previous blanket fallback meant a mistyped field, an invalid date or a
     * malformed regex quietly became a different search that still returned results, so
     * a reviewer had no way to tell a real finding from a typo. In a forensic tool the
     * wrong answer delivered confidently is worse than a refusal.
     *
     * @throws QuerySyntaxException if the query cannot be compiled; the message is
     *         reviewer-facing and {@link QuerySyntaxException#suggestions()} carries
     *         near-miss field names.
     */
    public static Query build(String query, Analyzer analyzer) {
        if (query == null || query.isBlank()) return new MatchAllDocsQuery();
        LuceneQueryBuilder b = new LuceneQueryBuilder(query.trim(), analyzer);
        try {
            Query q = b.parseOr();
            return q == null ? new MatchAllDocsQuery() : q;
        } catch (QuerySyntaxException e) {
            throw e;                                  // already reviewer-facing
        } catch (RuntimeException e) {
            // Unexpected internal failure: still refuse rather than silently reinterpret.
            throw new QuerySyntaxException(
                    "Could not parse query: " + e.getMessage(), query);
        }
    }

    /**
     * Compiles a query, or returns the failure instead of throwing.
     *
     * <p>For callers that want to render an inline error without try/catch — the search
     * bar validating as the user types, for instance.
     */
    public static Result compile(String query, Analyzer analyzer) {
        try {
            return new Result(build(query, analyzer), null);
        } catch (QuerySyntaxException e) {
            return new Result(null, e);
        }
    }

    /** Outcome of {@link #compile}: exactly one of {@code query} or {@code error} is set. */
    public record Result(Query query, QuerySyntaxException error) {
        public boolean ok() { return error == null; }
    }

    // ---- grammar (mirrors engine.QueryParser) --------------------------------

    private Query parseOr() {
        Query left = parseAnd();
        while (true) {
            skipWs();
            if (eat("OR ") || eat("|| ")) {
                Query right = parseAnd();
                left = new BooleanQuery.Builder()
                        .add(left, BooleanClause.Occur.SHOULD)
                        .add(right, BooleanClause.Occur.SHOULD)
                        .build();
            } else {
                return left;
            }
        }
    }

    private Query parseAnd() {
        Query left = parseUnary();
        while (true) {
            skipWs();
            int save = pos;
            boolean explicit = eat("AND ") || eat("&& ");
            if (!explicit) {
                if (pos >= input.length() || peekIs(')') || lookingAt("OR ")) { pos = save; return left; }
            }
            Query right = parseUnary();
            left = new BooleanQuery.Builder()
                    .add(left, BooleanClause.Occur.MUST)
                    .add(right, BooleanClause.Occur.MUST)
                    .build();
        }
    }

    private Query parseUnary() {
        skipWs();
        if (eat("NOT ") || eat("-") || eat("!")) {
            Query inner = parseUnary();
            return new BooleanQuery.Builder()
                    .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                    .add(inner, BooleanClause.Occur.MUST_NOT)
                    .build();
        }
        return parseAtom();
    }

    private Query parseAtom() {
        skipWs();
        if (peekIs('(')) {
            pos++;
            Query inner = parseOr();
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
            if (value.startsWith("[")) return dateRange(field, value);
            return fieldQuery(field, value);
        }
        return freeTerm(token);
    }

    private Query regex() {
        pos++;
        StringBuilder sb = new StringBuilder();
        boolean closed = false;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '/') { closed = true; pos++; break; }
            sb.append(c);
            pos++;
        }
        if (!closed) {
            throw new QuerySyntaxException(
                    "Unterminated regular expression; add the closing '/'.", sb.toString());
        }
        if (sb.length() == 0) {
            throw new QuerySyntaxException("Empty regular expression.", "//");
        }
        try {
            // Term-level regex cannot span token boundaries (INV-88213 analyzes to
            // "inv" + "88213"), so evaluate the pattern against stored text instead.
            return StoredTextRegexQuery.of(sb.toString(), LuceneIndex.F_TEXT_ALL);
        } catch (RuntimeException e) {
            // A malformed pattern previously matched nothing, which is indistinguishable
            // from a valid pattern with no hits.
            throw new QuerySyntaxException(
                    "Invalid regular expression /" + sb + "/: " + e.getMessage(), sb.toString());
        }
    }

    private Query phrase() {
        int open = pos;
        String text = readQuoted();
        if (pos > input.length() || (pos >= input.length() && !input.endsWith("\""))) {
            throw new QuerySyntaxException(
                    "Unterminated phrase; add the closing double quote.",
                    input.substring(Math.min(open, input.length())));
        }
        skipWs();
        int slop = 0;
        if (peekIs('~')) { pos++; slop = readInt(3); }
        Query q = phraseBuilder.createPhraseQuery(LuceneIndex.F_ALL, text, slop);
        return q == null ? new MatchNoDocsQuery() : q;
    }

    private Query freeTerm(String token) {
        if (token.endsWith("~") || token.matches(".*~\\d+$")) {
            int tilde = token.lastIndexOf('~');
            String term = token.substring(0, tilde).toLowerCase(Locale.ROOT);
            int edits = tilde + 1 < token.length()
                    ? parseEdits(token.substring(tilde + 1), token) : 2;
            return new FuzzyQuery(new Term(LuceneIndex.F_ALL, term), edits);
        }
        if (token.indexOf('*') >= 0 || token.indexOf('?') >= 0) {
            guardWildcard(token);
            return new WildcardQuery(new Term(LuceneIndex.F_ALL, token.toLowerCase(Locale.ROOT)));
        }
        Query q = phraseBuilder.createBooleanQuery(LuceneIndex.F_ALL, token, BooleanClause.Occur.MUST);
        return q == null ? new MatchNoDocsQuery() : q;
    }

    private Query fieldQuery(String alias, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new QuerySyntaxException(
                    "Field '" + alias + ":' has no value.", alias);
        }
        String field = resolve(alias);
        boolean keyword = KEYWORD_FIELDS.contains(field);
        String value = keyword ? rawValue : rawValue.toLowerCase(Locale.ROOT);

        if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
            guardWildcard(value);
            // Keyword fields keep case; text fields are lowercased by the analyzer.
            return new WildcardQuery(new Term(field, value));
        }
        if (value.endsWith("~") || value.matches(".*~\\d+$")) {
            int tilde = value.lastIndexOf('~');
            int edits = tilde + 1 < value.length()
                    ? parseEdits(value.substring(tilde + 1), value) : 2;
            return new FuzzyQuery(new Term(field, value.substring(0, tilde)), edits);
        }
        if (keyword) {
            return new TermQuery(new Term(field, rawValue));
        }
        Query q = phraseBuilder.createPhraseQuery(field, rawValue);
        if (q == null) q = phraseBuilder.createBooleanQuery(field, rawValue, BooleanClause.Occur.MUST);
        return q == null ? new MatchNoDocsQuery() : q;
    }

    private Query dateRange(String alias, String firstChunk) {
        StringBuilder sb = new StringBuilder(firstChunk);
        while (pos < input.length() && sb.indexOf("]") < 0) sb.append(input.charAt(pos++));
        String body = sb.toString().replace("[", "").replace("]", "").trim();
        String[] parts = body.split("(?i)\\s+TO\\s+");

        if (parts.length != 2) {
            throw new QuerySyntaxException(
                    "Date range must be written as [START TO END], e.g. "
                    + "date:[2023-01-01 TO 2023-12-31]. Use * for an open end.", body);
        }

        long lo = parseDate(parts[0], Long.MIN_VALUE);
        long hi = parseDate(parts[1], Long.MAX_VALUE);
        // Inclusive upper bound: extend to the end of that day.
        if (hi != Long.MAX_VALUE) hi += 86_399_999L;

        // An inverted range can never match. Silently returning nothing would look
        // identical to "no responsive documents", which is a very different finding.
        if (lo > hi) {
            throw new QuerySyntaxException(
                    "Date range start is after its end (" + parts[0].trim()
                    + " > " + parts[1].trim() + "); this can never match.", body);
        }

        String field = switch (alias) {
            case "sent" -> LuceneIndex.F_SENT;
            case "created" -> LuceneIndex.F_CREATED;
            default -> LuceneIndex.F_MODIFIED;
        };
        return LongPoint.newRangeQuery(field, lo, hi);
    }

    /**
     * Parses one end of a date range. {@code *} means "open"; anything else must be a
     * valid ISO date.
     *
     * <p>An unparseable date is rejected rather than defaulted. Previously
     * {@code date:[NOTADATE TO 2023-12-31]} silently became an unbounded lower bound and
     * matched every item before that date — a large, invisible over-collection.
     */
    /**
     * Validates a fuzzy edit distance. Lucene supports 0-2; anything larger was
     * previously clamped silently, so {@code term~99} behaved as {@code term~2} without
     * telling the reviewer their threshold was ignored.
     */
    private static int parseEdits(String raw, String token) {
        int n;
        try {
            n = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new QuerySyntaxException(
                    "Fuzzy distance in '" + token + "' must be a whole number.", token);
        }
        if (n < 0 || n > FuzzyQuery.defaultMaxEdits) {
            throw new QuerySyntaxException(
                    "Fuzzy distance in '" + token + "' must be between 0 and "
                    + FuzzyQuery.defaultMaxEdits + " (got " + n + ").", token);
        }
        return n;
    }

    /**
     * Rejects wildcard patterns with no literal characters. A bare {@code *} enumerates
     * every term in the index and can stall the UI on a large case.
     */
    private static void guardWildcard(String pattern) {
        String literals = pattern.replace("*", "").replace("?", "");
        if (literals.isEmpty()) {
            throw new QuerySyntaxException(
                    "Wildcard '" + pattern + "' has no literal characters; "
                    + "add at least one to keep the search bounded.", pattern);
        }
    }

    private static long parseDate(String s, long fallback) {
        if (s == null || s.isBlank() || "*".equals(s.trim())) return fallback;
        try {
            return LocalDate.parse(s.trim()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            throw new QuerySyntaxException(
                    "Invalid date '" + s.trim() + "'. Expected ISO format YYYY-MM-DD, or *.",
                    s.trim());
        }
    }

    // ---- field aliases -------------------------------------------------------

    private static final List<String> KEYWORD_FIELDS = List.of(
            LuceneIndex.F_EXT, LuceneIndex.F_TAG, LuceneIndex.F_STATUS,
            LuceneIndex.F_MD5, LuceneIndex.F_SHA256, LuceneIndex.F_CUSTODIAN,
            LuceneIndex.F_ID, LuceneIndex.F_PARENT, LuceneIndex.F_HAS_ATT,
            LuceneIndex.F_DUPLICATE);

    /**
     * Every alias the query language accepts. Used both to resolve a field and to
     * validate one, so the two can never drift apart.
     */
    static final java.util.Set<String> KNOWN_ALIASES = java.util.Set.of(
            "from", "to", "cc", "copy", "subject", "type", "ext", "path", "container",
            "tag", "status", "custodian", "name", "notes", "note", "md5", "sha256",
            "sha", "hasattachments", "messageid", "meta", "date", "sent", "created",
            // Approved grammar aliases mapped onto already-indexed fields.
            "content", "text", "bcc", "hash", "id", "source", "parent", "duplicate");

    /** Levenshtein-based near misses, so a typo gets a usable hint. */
    static java.util.List<String> suggestFields(String alias) {
        String a = alias.toLowerCase(Locale.ROOT);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : KNOWN_ALIASES) {
            if (editDistance(a, k) <= 2) out.add(k);
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static int editDistance(String a, String b) {
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

    private static String resolve(String alias) {
        return switch (alias) {
            case "from" -> LuceneIndex.F_FROM;
            case "to" -> LuceneIndex.F_TO;
            case "cc", "copy" -> LuceneIndex.F_CC;
            case "subject" -> LuceneIndex.F_SUBJECT;
            case "type", "ext" -> LuceneIndex.F_EXT;
            case "path" -> LuceneIndex.F_PATH;
            case "container" -> LuceneIndex.F_CONTAINER;
            case "tag" -> LuceneIndex.F_TAG;
            case "status" -> LuceneIndex.F_STATUS;
            case "custodian" -> LuceneIndex.F_CUSTODIAN;
            case "name" -> LuceneIndex.F_NAME;
            case "notes", "note" -> LuceneIndex.F_NOTES;
            case "md5" -> LuceneIndex.F_MD5;
            case "sha256", "sha" -> LuceneIndex.F_SHA256;
            case "hasattachments" -> LuceneIndex.F_HAS_ATT;
            case "messageid" -> LuceneIndex.F_MESSAGE_ID;
            case "meta" -> LuceneIndex.F_META;
            // "content"/"text" are explicit spellings of the default full-text field.
            case "content", "text" -> LuceneIndex.F_ALL;
            // BCC is not separately indexed; fold into CC so the query is honest about
            // what it searches rather than rejecting a documented field.
            case "bcc" -> LuceneIndex.F_CC;
            // "hash" is an alias for SHA-256, the primary evidence hash.
            case "hash" -> LuceneIndex.F_SHA256;
            case "id" -> LuceneIndex.F_ID;
            case "source" -> LuceneIndex.F_PATH;
            case "parent" -> LuceneIndex.F_PARENT;
            case "duplicate" -> LuceneIndex.F_DUPLICATE;
            // An unrecognised field is a hard error. Falling back to a full-text
            // search here would silently change the meaning of the reviewer's query.
            default -> throw new QuerySyntaxException(
                    "Unknown search field '" + alias + "'.", alias, suggestFields(alias));
        };
    }

    // ---- lexer ---------------------------------------------------------------

    private void skipWs() { while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++; }
    private boolean peekIs(char c) { return pos < input.length() && input.charAt(pos) == c; }
    private boolean lookingAt(String s) { return input.regionMatches(true, pos, s, 0, s.length()); }

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
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && input.charAt(pos) != '"') sb.append(input.charAt(pos++));
        if (pos < input.length()) pos++;
        return sb.toString();
    }

    private String readToken() {
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c) || c == ')') break;
            if (c == '"' && sb.isEmpty()) break;
            sb.append(c);
            pos++;
            if (c == ':') break;
        }
        String t = sb.toString();
        if (t.endsWith(":")) {
            skipWs();
            String val = peekIs('"') ? readQuoted() : readValueToken();
            return t + val;
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
