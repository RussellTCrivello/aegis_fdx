package com.aegis.fdx;

import com.aegis.fdx.index.LuceneIndex;
import com.aegis.fdx.index.LuceneQueryBuilder;
import com.aegis.fdx.index.SearchFacets;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §12 / §30: facet counts must agree exactly with the result set they describe.
 *
 * <p>A facet that disagrees with its own result list is worse than no facet: it tells an
 * examiner that a filter will return documents that it will not, or hides documents that
 * are there. So the assertions here compare facet counts against the count the same
 * query actually returns, rather than against expected constants.
 */
final class SearchFacetsTest {

    private static Item item(String id, String ext, String custodian,
                             ItemStatus status, String text) {
        Item it = new Item(id, id + "." + ext);
        it.extension(ext);
        it.custodian(custodian);
        it.status(status);
        it.size(1000);
        it.extractedText(text);
        return it;
    }

    private static LuceneIndex populated(Path dir) throws Exception {
        LuceneIndex index = new LuceneIndex(dir.resolve("index"), 64);
        index.put(item("E-1", "pdf", "A. Farouk", ItemStatus.INDEXED, "invoice payment alpha"));
        index.put(item("E-2", "pdf", "A. Farouk", ItemStatus.INDEXED, "invoice refund beta"));
        index.put(item("E-3", "docx", "B. Nowak", ItemStatus.INDEXED, "invoice contract gamma"));
        index.put(item("E-4", "docx", "B. Nowak", ItemStatus.ERROR, "unrelated delta"));
        index.put(item("E-5", "jpg", "C. Ibrahim", ItemStatus.UNSUPPORTED, "invoice scan epsilon"));
        index.commit();
        return index;
    }

    @Test
    @DisplayName("facet counts sum to the number of matching documents")
    void facetsAgreeWithResultSet(@TempDir Path dir) throws Exception {
        try (LuceneIndex index = populated(dir)) {
            IndexSearcher s = index.searcher();
            Query q = LuceneQueryBuilder.build("invoice", index.analyzer());
            long total = s.count(q);
            assertEquals(4, total, "four documents mention the term");

            Map<String, List<SearchFacets.Bucket>> f = SearchFacets.counts(s, q, 20);

            long byExt = f.get(LuceneIndex.F_EXT).stream()
                    .mapToLong(SearchFacets.Bucket::count).sum();
            assertEquals(total, byExt, "extension buckets must account for every hit");

            long byCustodian = f.get(LuceneIndex.F_CUSTODIAN).stream()
                    .mapToLong(SearchFacets.Bucket::count).sum();
            assertEquals(total, byCustodian);
        }
    }

    @Test
    @DisplayName("each bucket equals what filtering by that value actually returns")
    void bucketsMatchTheFilteredQuery(@TempDir Path dir) throws Exception {
        try (LuceneIndex index = populated(dir)) {
            IndexSearcher s = index.searcher();
            Query q = LuceneQueryBuilder.build("invoice", index.analyzer());

            for (SearchFacets.Bucket b : SearchFacets.counts(s, q, 20).get(LuceneIndex.F_EXT)) {
                Query filtered = new org.apache.lucene.search.BooleanQuery.Builder()
                        .add(q, org.apache.lucene.search.BooleanClause.Occur.MUST)
                        .add(new org.apache.lucene.search.TermQuery(
                                new org.apache.lucene.index.Term(LuceneIndex.F_EXT, b.value())),
                                org.apache.lucene.search.BooleanClause.Occur.MUST)
                        .build();
                assertEquals(s.count(filtered), b.count(),
                        "facet " + b.value() + " must predict its own filtered result");
            }
        }
    }

    @Test
    @DisplayName("buckets are ordered by descending count")
    void bucketsAreOrdered(@TempDir Path dir) throws Exception {
        try (LuceneIndex index = populated(dir)) {
            IndexSearcher s = index.searcher();
            List<SearchFacets.Bucket> ext = SearchFacets.counts(
                    s, new MatchAllDocsQuery(), 20).get(LuceneIndex.F_EXT);
            for (int i = 1; i < ext.size(); i++) {
                assertTrue(ext.get(i - 1).count() >= ext.get(i).count(),
                        "buckets must be ordered largest first");
            }
            // pdf and docx both have two documents; ties break alphabetically, so
            // docx leads. Asserting the tie-break explicitly keeps the ordering
            // deterministic rather than dependent on segment iteration order.
            assertEquals(2, ext.get(0).count());
            assertEquals("docx", ext.get(0).value());
        }
    }

    @Test
    @DisplayName("topN truncates without disturbing the ordering")
    void topNTruncates(@TempDir Path dir) throws Exception {
        try (LuceneIndex index = populated(dir)) {
            IndexSearcher s = index.searcher();
            List<SearchFacets.Bucket> ext = SearchFacets.counts(
                    s, new MatchAllDocsQuery(), 1).get(LuceneIndex.F_EXT);
            assertEquals(1, ext.size());
            assertEquals("docx", ext.get(0).value(), "topN must keep the same leader");
        }
    }

    @Test
    @DisplayName("a query matching nothing yields empty facets, not an error")
    void emptyResultSet(@TempDir Path dir) throws Exception {
        try (LuceneIndex index = populated(dir)) {
            IndexSearcher s = index.searcher();
            Query q = LuceneQueryBuilder.build("zzzznothingmatchesthis", index.analyzer());
            assertEquals(0, s.count(q));
            Map<String, List<SearchFacets.Bucket>> f = SearchFacets.counts(s, q, 10);
            assertNotNull(f);
            for (List<SearchFacets.Bucket> buckets : f.values()) {
                assertTrue(buckets.isEmpty(), "no hits means no buckets");
            }
        }
    }

    @Test
    @DisplayName("an empty index faceted without failing")
    void emptyIndex(@TempDir Path dir) throws Exception {
        try (LuceneIndex index = new LuceneIndex(dir.resolve("index"), 64)) {
            index.commit();
            Map<String, List<SearchFacets.Bucket>> f =
                    SearchFacets.counts(index.searcher(), new MatchAllDocsQuery(), 10);
            for (List<SearchFacets.Bucket> buckets : f.values()) {
                assertTrue(buckets.isEmpty());
            }
        }
    }

    @Test
    @DisplayName("facets see documents added since the last commit (NRT)")
    void facetsAreNearRealTime(@TempDir Path dir) throws Exception {
        try (LuceneIndex index = populated(dir)) {
            index.put(item("E-6", "pdf", "A. Farouk", ItemStatus.INDEXED, "invoice zeta"));
            // deliberately no commit: the NRT reader must still see it
            IndexSearcher s = index.searcher();
            Query q = LuceneQueryBuilder.build("invoice", index.analyzer());
            List<SearchFacets.Bucket> ext = SearchFacets.counts(s, q, 20).get(LuceneIndex.F_EXT);
            long pdf = ext.stream().filter(b -> b.value().equals("pdf"))
                    .mapToLong(SearchFacets.Bucket::count).findFirst().orElse(-1);
            assertEquals(3, pdf, "an uncommitted document must be counted");
            assertEquals(s.count(q), ext.stream()
                    .mapToLong(SearchFacets.Bucket::count).sum());
        }
    }

    @Test
    @DisplayName("status facets reflect the mixed states of a real case")
    void statusFacets(@TempDir Path dir) throws Exception {
        try (LuceneIndex index = populated(dir)) {
            IndexSearcher s = index.searcher();
            Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
            for (SearchFacets.Bucket b : SearchFacets.counts(
                    s, new MatchAllDocsQuery(), 20).get(LuceneIndex.F_STATUS)) {
                byStatus.put(b.value(), b.count());
            }
            assertEquals(3L, byStatus.get(ItemStatus.INDEXED.label()));
            assertEquals(1L, byStatus.get(ItemStatus.ERROR.label()));
            assertEquals(1L, byStatus.get(ItemStatus.UNSUPPORTED.label()));
        }
    }
}
