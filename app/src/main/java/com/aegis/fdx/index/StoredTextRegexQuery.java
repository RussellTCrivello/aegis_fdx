package com.aegis.fdx.index;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * F-15 regular-expression search over <em>stored text</em> rather than index terms.
 *
 * <p>Lucene's native {@link org.apache.lucene.search.RegexpQuery} matches whole
 * analyzed terms, so a pattern that straddles a token boundary — {@code INV-\d{5}},
 * which {@code StandardAnalyzer} splits into {@code inv} and {@code 88213} — can
 * never match. Investigators expect the pattern to run against the document text
 * as it reads, so this query does exactly that.
 *
 * <p>Implemented as a {@link TwoPhaseIterator} with a high match cost: the
 * approximation supplies candidate documents (cheaply narrowed by an optional
 * prefilter) and the verification step applies {@link Pattern} to the stored field.
 * Lucene therefore runs the cheap clauses of a boolean query first and only
 * regex-checks the survivors.
 */
public final class StoredTextRegexQuery extends Query {

    private final String field;
    private final Pattern pattern;
    private final Query approximation;

    public StoredTextRegexQuery(String field, Pattern pattern, Query approximation) {
        this.field = Objects.requireNonNull(field);
        this.pattern = Objects.requireNonNull(pattern);
        this.approximation = approximation;
    }

    public static StoredTextRegexQuery of(String regex, String field) {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        return new StoredTextRegexQuery(field, p, null);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
            throws IOException {
        final Weight approxWeight = approximation == null
                ? null
                : searcher.createWeight(searcher.rewrite(approximation), ScoreMode.COMPLETE_NO_SCORES, 1f);

        return new ConstantScoreWeight(this, boost) {

            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                final var storedFields = context.reader().storedFields();
                final int maxDoc = context.reader().maxDoc();

                final DocIdSetIterator approxIterator;
                if (approxWeight != null) {
                    Scorer s = approxWeight.scorer(context);
                    if (s == null) return null;
                    approxIterator = s.iterator();
                } else {
                    approxIterator = DocIdSetIterator.all(maxDoc);
                }

                TwoPhaseIterator tpi = new TwoPhaseIterator(approxIterator) {
                    @Override
                    public boolean matches() throws IOException {
                        String value = storedFields.document(approximation().docID()).get(field);
                        return value != null && pattern.matcher(value).find();
                    }

                    @Override
                    public float matchCost() {
                        // Stored-field fetch plus a regex scan: deliberately expensive
                        // so Lucene orders this clause last.
                        return 5000f;
                    }
                };
                return new ConstantScoreScorer(this, score(), scoreMode, tpi);
            }

            @Override
            public boolean isCacheable(LeafReaderContext ctx) {
                return false;   // stored-field access is not cacheable
            }
        };
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    @Override
    public String toString(String defaultField) {
        return "storedRegex(" + field + ":/" + pattern.pattern() + "/)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StoredTextRegexQuery q)) return false;
        return field.equals(q.field)
                && pattern.pattern().equals(q.pattern.pattern())
                && Objects.equals(approximation, q.approximation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, pattern.pattern(), approximation);
    }
}
