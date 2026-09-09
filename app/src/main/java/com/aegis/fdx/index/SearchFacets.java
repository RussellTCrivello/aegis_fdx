package com.aegis.fdx.index;

import com.aegis.fdx.store.OperationTimings;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §12: breakdowns of a search result set by category — "of the 4,812 documents matching
 * this query, how many are PDFs, and whose are they".
 *
 * <h2>Why not the Lucene facet module</h2>
 * {@code lucene-facet} wants its own taxonomy index, written at indexing time, kept in
 * step with the main index and rebuilt when the dimensions change. This application
 * already indexes every dimension worth faceting on — extension, custodian, status,
 * media type, tag, OCR state — as untokenised {@link org.apache.lucene.document.StringField}
 * values. Counting those against a result set needs the postings that already exist, so
 * this class intersects them directly and the index gains no second structure to keep
 * consistent, no extra directory, and nothing new to corrupt.
 *
 * <p>The trade is that cost scales with the number of distinct values in a dimension
 * rather than being constant. That is the right trade here: these dimensions are
 * low-cardinality by nature — a case has a handful of statuses, dozens of custodians and
 * a few hundred extensions — and the measured cost is in
 * {@code docs/DATABASE_PERFORMANCE_REPORT.md}. A dimension with millions of distinct
 * values, such as file name, is not a facet and is not offered as one.
 *
 * <h2>Consistency with the result set</h2>
 * Facets are counted against the <em>same</em> query the results came from, over the same
 * reader, in the same call. They cannot drift from the result set the way a second query
 * issued a moment later can, and every count is a count of matching documents rather than
 * of anything sampled or estimated.
 */
public final class SearchFacets {

    /** The dimensions worth faceting: low cardinality, and meaningful to an examiner. */
    public static final List<String> DEFAULT_DIMENSIONS = List.of(
            LuceneIndex.F_EXT,
            LuceneIndex.F_CUSTODIAN,
            LuceneIndex.F_STATUS,
            LuceneIndex.F_TAG,
            LuceneIndex.F_OCR_APPLIED,
            LuceneIndex.F_DUPLICATE);

    private SearchFacets() {
    }

    /**
     * Counts each dimension's values across the documents matching {@code query}.
     *
     * @param topN maximum buckets returned per dimension, largest first
     * @return dimension name to ordered buckets; a dimension absent from the index
     *         simply yields no buckets rather than failing the search
     */
    public static Map<String, List<Bucket>> counts(IndexSearcher searcher, Query query,
                                                   List<String> dimensions, int topN)
            throws IOException {
        long t0 = System.nanoTime();
        try {
            return countAll(searcher, query, dimensions, topN);
        } finally {
            OperationTimings.record(OperationTimings.SEARCH_FACETS, System.nanoTime() - t0);
        }
    }

    private static Map<String, List<Bucket>> countAll(IndexSearcher searcher, Query query,
                                                      List<String> dimensions, int topN)
            throws IOException {
        // One pass to find the matching documents, then postings intersection per value.
        BitSet[] matches = collect(searcher, query);

        Map<String, List<Bucket>> out = new LinkedHashMap<>();
        for (String dim : dimensions) {
            List<Bucket> buckets = countDimension(searcher.getIndexReader(), matches, dim);
            buckets.sort(Comparator.comparingLong(Bucket::count).reversed()
                    .thenComparing(Bucket::value));
            out.put(dim, buckets.size() > topN ? new ArrayList<>(buckets.subList(0, topN)) : buckets);
        }
        return out;
    }

    /** Facets over the default dimensions. */
    public static Map<String, List<Bucket>> counts(IndexSearcher searcher, Query query, int topN)
            throws IOException {
        return counts(searcher, query, DEFAULT_DIMENSIONS, topN);
    }

    /** The matching document ids, as one bitset per segment. */
    private static BitSet[] collect(IndexSearcher searcher, Query query) throws IOException {
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        FixedBitSet[] sets = new FixedBitSet[leaves.size()];
        for (int i = 0; i < leaves.size(); i++) {
            sets[i] = new FixedBitSet(leaves.get(i).reader().maxDoc());
        }
        // CollectorManager is the supported entry point: the single-Collector
        // search(Query, Collector) overload is deprecated in Lucene 9 and slated
        // for removal in Lucene 10. Each worker thread gets its own collector via
        // newCollector(), and every leaf belongs to exactly one thread, so each
        // sets[ord] bitset is still mutated by a single thread.
        searcher.search(query, new CollectorManager<SimpleCollector, Void>() {
            @Override
            public SimpleCollector newCollector() {
                return new SimpleCollector() {
                    private int ord;

                    @Override
                    protected void doSetNextReader(LeafReaderContext context) {
                        this.ord = context.ord;
                    }

                    @Override
                    public void collect(int doc) {
                        sets[ord].set(doc);
                    }

                    @Override
                    public ScoreMode scoreMode() {
                        return ScoreMode.COMPLETE_NO_SCORES;
                    }
                };
            }

            @Override
            public Void reduce(Collection<SimpleCollector> collectors) {
                return null;
            }
        });
        return sets;
    }

    private static List<Bucket> countDimension(IndexReader reader, BitSet[] matches, String field)
            throws IOException {
        Map<String, long[]> tally = new LinkedHashMap<>();
        List<LeafReaderContext> leaves = reader.leaves();

        for (int ord = 0; ord < leaves.size(); ord++) {
            LeafReaderContext leaf = leaves.get(ord);
            BitSet hits = matches[ord];
            if (hits.cardinality() == 0) {
                continue;
            }
            Terms terms = leaf.reader().terms(field);
            if (terms == null) {
                continue;               // this dimension is not present in this segment
            }
            TermsEnum te = terms.iterator();
            PostingsEnum postings = null;
            BytesRef term;
            while ((term = te.next()) != null) {
                postings = te.postings(postings, PostingsEnum.NONE);
                long n = 0;
                int doc;
                while ((doc = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (hits.get(doc)) {
                        n++;
                    }
                }
                if (n > 0) {
                    tally.computeIfAbsent(term.utf8ToString(), k -> new long[1])[0] += n;
                }
            }
        }

        List<Bucket> out = new ArrayList<>(tally.size());
        tally.forEach((v, n) -> out.add(new Bucket(v, n[0])));
        return out;
    }

    /** One value of a dimension and how many matching documents carry it. */
    public record Bucket(String value, long count) {
    }
}
