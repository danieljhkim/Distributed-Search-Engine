package com.danieljhkim.dsearch.common.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingModelIdentity(String modelId, String engine, String digest, int dimension) {

    public static final String UNSPECIFIED = "unspecified";

    public EmbeddingModelIdentity {
        modelId = normalizeIdentity(modelId);
        engine = normalizeIdentity(engine);
        digest = digest == null || digest.isBlank() ? digestOf(modelId, engine, dimension) : digest.trim();
        if (dimension < 0) {
            throw new IllegalArgumentException("embedding dimension must not be negative");
        }
    }

    public static EmbeddingModelIdentity of(String modelId, String engine, int dimension) {
        String normalizedId = normalizeIdentity(modelId);
        String normalizedEngine = normalizeIdentity(engine);
        return new EmbeddingModelIdentity(
                normalizedId, normalizedEngine, digestOf(normalizedId, normalizedEngine, dimension), dimension);
    }

    public static EmbeddingModelIdentity unspecified(int dimension) {
        return of(UNSPECIFIED, UNSPECIFIED, dimension);
    }

    public EmbeddingModelIdentity withDimension(int newDimension) {
        return of(modelId, engine, newDimension);
    }

    public static String digestOf(String modelId, String engine, int dimension) {
        String canonical = normalizeIdentity(modelId) + '\n' + normalizeIdentity(engine) + '\n' + dimension;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to identity embedding models", e);
        }
    }

    private static String normalizeIdentity(String value) {
        if (value == null || value.isBlank()) {
            return UNSPECIFIED;
        }
        return value.trim();
    }

    public boolean sameContract(EmbeddingModelIdentity other) {
        Objects.requireNonNull(other, "other");
        return modelId.equals(other.modelId)
                && engine.equals(other.engine)
                && digest.equals(other.digest)
                && dimension == other.dimension;
    }
}
