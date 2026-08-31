package com.danieljhkim.dsearch.ml.embedding;

import com.danieljhkim.dsearch.common.schema.EmbeddingModelIdentity;

@FunctionalInterface
public interface TextEmbedder {
    float[] embed(String text);

    default EmbeddingModelIdentity identity() {
        return EmbeddingModelIdentity.unspecified(0);
    }
}
