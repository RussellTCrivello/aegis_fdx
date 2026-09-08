import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.index.LuceneQueryBuilder;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * §11 / §22.2: Lucene indexing and search latency against the application's real
 * index, analyzer and query builder — not a synthetic Lucene benchmark.
 *
 * Usage: SearchBench <docs> [outputTsv]
 */
public final class SearchBench {

    static final String[] VOCAB = {
        "invoice", "payment", "transfer", "account", "settlement", "contract", "annex",
        "shipment", "customs", "declaration", "consignment", "vessel", "charter",
        "director", "board", "resolution", "minutes", "audit", "reconciliation",
        "quarterly", "confidential", "privileged", "draft", "final", "amended",
    };

    public static void main(String[] args) throws Exception {
        int docs = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        Path out = args.length > 1 ? Path.of(args[1]) : null;
        Path dir = Files.createTempDirectory("aegis-lucene");
        List<String> tsv = new ArrayList<>();

        System.out.printf("== Lucene benchmark: %,d documents ==%n%n", docs);

        try (LuceneIndex index = new LuceneIndex(dir.resolve("index"), 256)) {
            Random rnd = new Random(42);

            // ---- indexing throughput ----
            long t0 = System.nanoTime();
            for (int i = 0; i < docs; i++) {
                index.put(doc(i, rnd));
                if (i > 0 && i % 50_000 == 0) index.commit();
            }
            index.commit();
            double indexSec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("-- indexing: %,d docs in %.1fs = %,.0f docs/s%n",
                    docs, indexSec, docs / indexSec);
            tsv.add("index_docs_per_sec\tindexing\t" + String.format("%.0f", docs / indexSec));

            long size = dirSize(dir);
            System.out.printf("-- index size: %,d bytes (%.0f bytes/doc)%n", size, (double) size / docs);
            tsv.add("index_size_bytes\tindexing\t" + size);

            // ---- NRT visibility: how soon is a new document searchable? ----
            Item fresh = doc(docs + 1, rnd);
            fresh.extractedText("zzuniquetoken singular occurrence");
            long t = System.nanoTime();
            index.put(fresh);
            IndexSearcher s = index.searcher();          // NRT reader off the writer
            long hits = s.count(LuceneQueryBuilder.build("zzuniquetoken", index.analyzer()));
            double nrtMs = (System.nanoTime() - t) / 1e6;
            System.out.printf("-- NRT: new document searchable in %.1f ms (hits=%d)%n", nrtMs, hits);
            tsv.add("nrt_visibility_ms\tsearch\t" + String.format("%.1f", nrtMs));
            tsv.add("nrt_hits\tsearch\t" + hits);

            // ---- search latency by query type ----
            System.out.printf("%n%-22s %8s %8s %8s %8s %10s%n",
                    "query type", "p50 ms", "p95 ms", "p99 ms", "max ms", "hits");
            String[][] queries = {
                {"term", "invoice"},
                {"phrase", "\"quarterly audit\""},
                {"boolean", "invoice AND payment NOT draft"},
                {"wildcard", "settle*"},
                {"fuzzy", "invoce~"},
                {"proximity", "\"invoice payment\"~5"},
                {"field", "name:evidence"},
                {"prefix_common", "con*"},
            };
            for (String[] q : queries) {
                bench(index, q[0], q[1], tsv);
            }

            // ---- range search over a numeric field ----
            benchRange(index, tsv);

            // ---- §12 facets over a result set ----
            System.out.println();
            for (String[] q : new String[][]{{"facets(term)", "invoice"},
                                             {"facets(boolean)", "invoice AND payment"}}) {
                Query fq = LuceneQueryBuilder.build(q[1], index.analyzer());
                IndexSearcher fs = index.searcher();
                for (int i = 0; i < 5; i++) com.aegis.fdx.index.SearchFacets.counts(fs, fq, 10);
                int n = 25;
                double[] ms = new double[n];
                java.util.Map<String, java.util.List<com.aegis.fdx.index.SearchFacets.Bucket>> r = null;
                for (int i = 0; i < n; i++) {
                    long ft = System.nanoTime();
                    r = com.aegis.fdx.index.SearchFacets.counts(fs, fq, 10);
                    ms[i] = (System.nanoTime() - ft) / 1e6;
                }
                Arrays.sort(ms);
                System.out.printf("%-22s %8.2f %8.2f %8.2f %8.2f%n", q[0],
                        ms[n / 2], ms[(int) (n * .95)], ms[n - 1], ms[0]);
                tsv.add(q[0] + "_p50_ms\tfacets\t" + String.format("%.2f", ms[n / 2]));
                tsv.add(q[0] + "_p95_ms\tfacets\t" + String.format("%.2f", ms[(int) (n * .95)]));
                for (var e : r.entrySet()) {
                    StringBuilder sb = new StringBuilder();
                    for (var b : e.getValue()) sb.append(b.value()).append('=').append(b.count()).append(' ');
                    System.out.printf("      %-14s %s%n", e.getKey(), sb);
                    tsv.add("facet_values_" + e.getKey() + "\tfacets\t" + sb.toString().trim());
                }
            }
        }
        if (out != null) { Files.write(out, tsv); System.out.println("\nwrote " + out); }
    }

    static void bench(LuceneIndex index, String name, String qs, List<String> tsv)
            throws Exception {
        Query q = LuceneQueryBuilder.build(qs, index.analyzer());
        IndexSearcher s = index.searcher();
        for (int i = 0; i < 20; i++) s.search(q, 50);            // warm
        int n = 200;
        double[] ms = new double[n];
        long hits = 0;
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            var td = s.search(q, 50);
            ms[i] = (System.nanoTime() - t) / 1e6;
            hits = td.totalHits.value;
        }
        Arrays.sort(ms);
        System.out.printf("%-22s %8.2f %8.2f %8.2f %8.2f %,10d%n", name,
                ms[n / 2], ms[(int) (n * .95)], ms[(int) (n * .99)], ms[n - 1], hits);
        tsv.add(name + "_p50_ms\tsearch\t" + String.format("%.2f", ms[n / 2]));
        tsv.add(name + "_p95_ms\tsearch\t" + String.format("%.2f", ms[(int) (n * .95)]));
        tsv.add(name + "_p99_ms\tsearch\t" + String.format("%.2f", ms[(int) (n * .99)]));
        tsv.add(name + "_hits\tsearch\t" + hits);
    }

    static void benchRange(LuceneIndex index, List<String> tsv) throws Exception {
        IndexSearcher s = index.searcher();
        Query q = org.apache.lucene.document.LongPoint.newRangeQuery(
                LuceneIndex.F_SIZE, 10_000, 400_000);
        for (int i = 0; i < 20; i++) s.search(q, 50);
        int n = 200;
        double[] ms = new double[n];
        long hits = 0;
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            var td = s.search(q, 50);
            ms[i] = (System.nanoTime() - t) / 1e6;
            hits = td.totalHits.value;
        }
        Arrays.sort(ms);
        System.out.printf("%-22s %8.2f %8.2f %8.2f %8.2f %,10d%n", "range(size)",
                ms[n / 2], ms[(int) (n * .95)], ms[(int) (n * .99)], ms[n - 1], hits);
        tsv.add("range_p50_ms\tsearch\t" + String.format("%.2f", ms[n / 2]));
        tsv.add("range_p95_ms\tsearch\t" + String.format("%.2f", ms[(int) (n * .95)]));
        tsv.add("range_p99_ms\tsearch\t" + String.format("%.2f", ms[(int) (n * .99)]));
    }

    /** A document with realistic, varied text rather than N identical rows. */
    static Item doc(int i, Random rnd) {
        Item it = new Item("E-" + i, "evidence-" + i + ".pdf");
        it.extension(DbBench.EXTS[rnd.nextInt(DbBench.EXTS.length)]);
        it.custodian(DbBench.CUST[rnd.nextInt(DbBench.CUST.length)]);
        it.size(rnd.nextInt(900_000) + 1000);
        it.status(ItemStatus.INDEXED);
        it.sourcePath("/evidence/vol" + (i % 7) + "/file" + i);
        StringBuilder sb = new StringBuilder(600);
        int words = 60 + rnd.nextInt(140);
        for (int w = 0; w < words; w++) {
            sb.append(VOCAB[rnd.nextInt(VOCAB.length)]).append(' ');
        }
        it.extractedText(sb.toString());
        return it;
    }

    static long dirSize(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { return 0; }
            }).sum();
        }
    }
}
