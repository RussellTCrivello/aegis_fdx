package com.aegis.fdx.ai.model;

import java.util.List;

/**
 * Turns text into vectors locally, for semantic retrieval.
 *
 * <p>Optional: the agent works without it, using the existing keyword index. When
 * present it supplements that index rather than replacing it.
 */
public interface EmbeddingProvider {

    String modelId();

    /** @return one vector per input, in the same order */
    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        List<float[]> out = embed(List.of(text));
        if (out.isEmpty()) {
            throw new ModelException(ModelException.Kind.BAD_RESPONSE,
                    "embedding provider returned nothing");
        }
        return out.get(0);
    }

    boolean isAvailable();

    /** Cosine similarity; both vectors must be the same length. */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0d;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
