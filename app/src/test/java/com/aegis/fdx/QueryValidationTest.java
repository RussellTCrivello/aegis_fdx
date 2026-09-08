package com.aegis.fdx;

import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.index.LuceneQueryBuilder;
import com.aegis.fdx.index.QuerySyntaxException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * Query-compiler validation (approved audit scope).
 *
 * <p>The governing rule: <b>a query the compiler cannot represent exactly must be
 * rejected with an explanation, never silently reinterpreted.</b>
 *
 * <p>This matters more in a forensic tool than in a general search box. The original
 * implementation wrapped the whole parse in {@code catch (RuntimeException)} and fell
 * back to a free-text search, so {@code custodain:Smith} (a one-letter typo) quietly
 * became a full-text search for "smith" and returned a plausible, wrongly-scoped hit set.
 * A reviewer had no signal that their filter had been discarded — they would reasonably
 * report the result as the custodian's complete document set.
 */
public final class QueryValidationTest {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    private static Analyzer analyzer;

    public static void main(String[] args) throws Exception {
        analyzer = LuceneIndex.newAnalyzer();

        section("Unknown fields are rejected");
        rejects("bogusfield:Smith", "unknown field");
        rejects("custodain:Smith", "one-letter typo of custodian");
        rejects("sender:alice", "plausible but unsupported alias");
        rejects("statuss:Error", "typo of status");
        rejects("zzzz:1", "nonsense field");
        suggests("custodain:Smith", "custodian");
        suggests("frm:alice", "from");
        suggests("tags:X", "tag");

        section("Approved grammar fields all resolve");
        for (String q : new String[]{
                "content:invoice", "name:contract", "path:/tmp/x", "type:pdf",
                "from:a@b.c", "to:d@e.f", "cc:g@h.i", "bcc:j@k.l", "subject:hello",
                "tag:Responsive", "hash:abc", "sha256:abc", "md5:abc",
                "status:INDEXED", "duplicate:true", "source:/x", "parent:E-1",
                "custodian:Smith", "id:E-1", "notes:foo", "meta:bar",
                "hasattachments:true", "messageid:<x@y>"}) {
            accepts(q);
        }
        accepts("date:[2023-01-01 TO 2023-12-31]");
        accepts("date:[* TO 2023-12-31]");
        accepts("date:[2023-01-01 TO *]");

        section("Date validation");
        rejects("date:[NOTADATE TO 2023-12-31]", "unparseable start date");
        rejects("date:[2023-01-01 TO GARBAGE]", "unparseable end date");
        rejects("date:[2023-13-45 TO 2023-99-99]", "out-of-range components");
        rejects("date:[2023-12-31 TO 2023-01-01]", "inverted range can never match");
        rejects("date:[2023-01-01]", "missing TO clause");

        section("Fuzzy distance validation");
        rejects("term~99", "edit distance above Lucene's maximum");
        rejects("term~5", "edit distance above maximum");
        rejects("custodian:Smith~7", "field fuzzy above maximum");
        accepts("term~");
        accepts("term~1");
        accepts("term~2");

        section("Wildcard guards");
        rejects("*", "bare wildcard enumerates the whole index");
        rejects("?", "bare single-char wildcard");
        rejects("**", "wildcards with no literals");
        accepts("settle*");
        accepts("agree?ent");
        accepts("name:contract*");

        section("Regex validation");
        accepts("/INV-[0-9]+/");
        rejects("/unclosed[0-9/", "malformed character class");
        rejects("/(((/", "unclosed group");
        rejects("/valid[0-9]+", "missing closing delimiter");

        section("Malformed structure");
        rejects("\"unterminated", "unterminated phrase");
        rejects("from:", "field with no value");

        section("Valid queries still compile");
        accepts("settlement");
        accepts("\"wire transfer\"");
        accepts("settlement AND payment");
        accepts("settlement OR payment");
        accepts("NOT settlement");
        accepts("(settlement OR payment) AND type:pdf");
        accepts("custodian:Smith AND date:[2023-01-01 TO 2023-12-31]");
        accepts("\"wire funds\"~9");
        accepts("");

        section("compile() reports instead of throwing");
        var bad = LuceneQueryBuilder.compile("bogusfield:x", analyzer);
        check("compile() flags a bad query without throwing", !bad.ok() && bad.error() != null);
        check("compile() carries a reviewer-facing message",
                bad.error().displayMessage().contains("Unknown search field"));
        var good = LuceneQueryBuilder.compile("custodian:Smith", analyzer);
        check("compile() returns a query for valid input", good.ok() && good.query() != null);

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (!failures.isEmpty()) {
            System.out.println("\nFailures:");
            failures.forEach(f -> System.out.println("  - " + f));
        }
        if (failed > 0) System.exit(1);
    }

    // =====================================================================

    /** A query that must compile cleanly. */
    private static void accepts(String q) {
        try {
            Query built = LuceneQueryBuilder.build(q, analyzer);
            check("accepts  " + display(q), built != null);
        } catch (QuerySyntaxException e) {
            check("accepts  " + display(q) + "  [rejected: " + e.getMessage() + "]", false);
        }
    }

    /** A query that must be refused rather than silently reinterpreted. */
    private static void rejects(String q, String why) {
        try {
            Query built = LuceneQueryBuilder.build(q, analyzer);
            check("rejects  " + display(q) + "  (" + why + ")  [got: " + built + "]", false);
        } catch (QuerySyntaxException e) {
            check("rejects  " + display(q) + "  (" + why + ")", true);
        }
    }

    /** An unknown field close to a real one must hint at the real one. */
    private static void suggests(String q, String expected) {
        try {
            LuceneQueryBuilder.build(q, analyzer);
            check("suggests " + expected + " for " + display(q) + "  [no error raised]", false);
        } catch (QuerySyntaxException e) {
            check("suggests " + expected + " for " + display(q),
                    e.suggestions().contains(expected));
        }
    }

    private static String display(String q) {
        return "\"" + q + "\"";
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("-- " + title + " " + "-".repeat(Math.max(0, 54 - title.length())));
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + what);
        } else {
            failed++;
            failures.add(what);
            System.out.println("  FAIL  " + what);
        }
    }
}
