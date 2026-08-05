package com.danieljhkim.dsearch.ml.embedding;

@FunctionalInterface
public interface TextEmbedder {
    float[] embed(String text);
}
