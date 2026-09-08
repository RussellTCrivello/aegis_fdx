package com.aegis.fdx;

import com.aegis.fdx.engine.CaseStore;
import com.aegis.fdx.engine.Filters;
import com.aegis.fdx.engine.QueryParser;
import com.aegis.fdx.engine.SearchHit;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * Zero-dependency test runner for the query grammar (F-15) and store behaviour.
 * Mirrors AT-07: a fixed battery of queries must return exactly the expected sets.
 *
 * <p>Run: {@code java -cp <classes> com.aegis.fdx.QueryParserTest}
 * Under Gradle these become JUnit 5 cases; the logic is identical.
 */
public final class QueryParserTest {

    private static int passed, failed;

    public static void main(String[] args) {
        CaseStore store = fixture();

        // --- keyword / phrase ---
        expect(store, "settlement", 4, "keyword (2 bodies + 2 subjects)");
        expect(store, "\"wire transfer\"", 1, "exact phrase");
        expect(store, "\"wire instructions\"", 0, "phrase must be contiguous");

        // --- wildcard ---
        expect(store, "settle*", 4, "trailing wildcard");
        expect(store, "agree?ent", 4, "single-char wildcard");

        // --- fuzzy ---
        expect(store, "agreemnt~2", 4, "fuzzy edit distance 2");
        expect(store, "sattlement~1", 4, "fuzzy edit distance 1");

        // --- proximity ---
        expect(store, "\"wire funds\"~9", 1, "proximity within slop (gap = 8 tokens)");
        expect(store, "\"wire funds\"~4", 0, "proximity outside slop");

        // --- boolean ---
        expect(store, "settlement AND payment", 1, "AND");
        expect(store, "settlement OR invoice", 5, "OR");
        expect(store, "settlement NOT payment", 3, "NOT");
        expect(store, "(settlement OR invoice) AND NOT payment", 4, "grouping");

        // --- fields ---
        expect(store, "from:alice", 1, "field from");
        expect(store, "subject:settlement", 2, "field subject");
        expect(store, "type:pdf", 2, "field type");
        expect(store, "tag:Responsive", 1, "field tag");
        expect(store, "custodian:\"M. Weber\"", 2, "quoted field value");
        expect(store, "path:evidence.zip", 1, "field path");

        // --- date range ---
        expect(store, "date:[2024-01-01 TO 2024-01-31]", 2, "date range");
        expect(store, "date:[2024-06-01 TO 2024-12-31]", 1, "date range later");

        // --- regex ---
        expect(store, "/INV-\\d{5}/", 1, "regex invoice number");
        expect(store, "/EUR\\s?[\\d,]+/", 1, "regex currency");

        // --- robustness (N-05) ---
        expect(store, "((unclosed", 0, "malformed query degrades, no crash");

        // --- F-20 hidden exclusion ---
        check(store.search("hidden-doc", null, false).isEmpty(), "hidden excluded by default");
        check(store.search("hidden-doc", null, true).size() == 1, "hidden included on request");

        // --- F-16 filters ---
        Filters f = new Filters();
        f.types.add("pdf");
        check(store.search("", f, false).size() == 2, "type filter");
        f.clear();
        f.statuses.add(ItemStatus.ERROR);
        check(store.search("", f, false).size() == 1, "status filter");

        // --- F-05 duplicates ---
        check(store.duplicates().size() == 1, "one duplicate cluster detected");
        check(store.duplicates().values().iterator().next().size() == 2, "cluster has 2 members");

        // --- F-23 threads ---
        check(store.emailThreads().get("Settlement agreement").size() == 2,
                "RE: normalised into one thread");

        // --- edit distance unit ---
        check(dist("kitten", "sitting") == 3, "levenshtein kitten/sitting");
        check(dist("abc", "abc") == 0, "levenshtein identical");

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static int dist(String a, String b) {
        try {
            var m = QueryParser.class.getDeclaredMethod("editDistance", String.class, String.class);
            m.setAccessible(true);
            return (int) m.invoke(null, a, b);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void expect(CaseStore store, String query, int n, String label) {
        List<SearchHit> hits = store.search(query, null, false);
        check(hits.size() == n, label + "  [" + query + "] expected " + n + " got " + hits.size());
    }

    private static void check(boolean cond, String label) {
        if (cond) { passed++; System.out.println("  PASS  " + label); }
        else { failed++; System.out.println("  FAIL  " + label); }
    }

    private static CaseStore fixture() {
        CaseStore s = new CaseStore("test");

        Item a = mk("E-1", "settlement_agreement.pdf", "pdf", "A. Farouk",
                "2024-01-10T10:00:00Z",
                "Settlement agreement between the parties. Payment of 240,000 EUR is due.");
        a.sha256("aaa");
        a.tags().add("Responsive");
        s.add(a);

        Item b = mk("E-2", "draft_settlement.pdf", "pdf", "M. Weber",
                "2024-01-20T10:00:00Z",
                "Draft settlement agreement, unsigned. Circulated for comment only.");
        b.sha256("bbb");
        s.add(b);

        Item c = mk("E-3", "invoice_scan.tiff", "tiff", "L. Nguyen",
                "2024-06-15T10:00:00Z",
                "INVOICE INV-88213 TOTAL EUR 4,150 VENDOR NORDWIND LOGISTIK");
        c.sha256("ccc");
        s.add(c);

        Item d = mk("E-4", "message_01.eml", "eml", "A. Farouk",
                "2024-02-01T10:00:00Z",
                "Please action the wire transfer today so we can release the funds tomorrow.");
        d.mediaType("message/rfc822");
        d.from("alice@example.test");
        d.to("bob@example.test");
        d.subject("Settlement agreement");
        d.sentDate(Instant.parse("2024-02-01T10:00:00Z"));
        d.sha256("ddd");
        s.add(d);

        Item e = mk("E-5", "message_02.eml", "eml", "S. Okafor",
                "2024-02-02T10:00:00Z", "Confirming receipt.");
        e.mediaType("message/rfc822");
        e.from("bob@example.test");
        e.subject("RE: Settlement agreement");
        e.sha256("eee");
        s.add(e);

        Item f = mk("E-6", "corrupt.docx", "docx", "J. Kowalski",
                "2024-03-01T10:00:00Z", "");
        f.status(ItemStatus.ERROR);
        f.errors().add("Truncated OOXML central directory");
        f.sha256("fff");
        s.add(f);

        Item g = mk("E-7", "secret.txt", "txt", "A. Farouk",
                "2024-03-05T10:00:00Z", "hidden-doc contents");
        g.tags().add("Hidden");
        g.sha256("ggg");
        s.add(g);

        Item h1 = mk("E-8", "dupe.txt", "txt", "A. Farouk", "2024-04-01T10:00:00Z", "same bytes");
        h1.sha256("dup-hash");
        h1.containerPath("evidence.zip → dupe.txt");
        s.add(h1);
        Item h2 = mk("E-9", "dupe_copy.txt", "txt", "M. Weber", "2024-04-01T10:00:00Z", "same bytes");
        h2.sha256("dup-hash");
        s.add(h2);

        return s;
    }

    private static Item mk(String id, String name, String ext, String cust, String when, String text) {
        Item i = new Item(id, name);
        i.extension(ext);
        i.mediaType("application/" + ext);
        i.custodian(cust);
        i.modified(Instant.parse(when));
        i.created(Instant.parse(when));
        i.extractedText(text);
        i.sourcePath("E:/evidence/" + name);
        i.status(ItemStatus.INDEXED);
        i.size(1024);
        return i;
    }
}
