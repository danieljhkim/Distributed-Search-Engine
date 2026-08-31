package com.danieljhkim.dsearch.common.schema;

import com.danieljhkim.dsearch.common.exception.SchemaMismatchException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class IndexSchemaCompatibility {

    private IndexSchemaCompatibility() {}

    public static void requireCompatible(IndexSchema persisted, IndexSchema runtime) {
        SchemaMismatchException mismatch = findMismatch(persisted, runtime);
        if (mismatch != null) {
            throw mismatch;
        }
    }

    public static SchemaMismatchException findMismatch(IndexSchema persisted, IndexSchema runtime) {
        Objects.requireNonNull(persisted, "persisted");
        Objects.requireNonNull(runtime, "runtime");
        if (persisted.compatibilityVersion() != runtime.compatibilityVersion()) {
            return SchemaMismatchException.of(
                    "compatibilityVersion",
                    String.valueOf(persisted.compatibilityVersion()),
                    String.valueOf(runtime.compatibilityVersion()));
        }
        if (!persisted.analyzer().name().equals(runtime.analyzer().name())) {
            return SchemaMismatchException.of(
                    "analyzer.name",
                    persisted.analyzer().name(),
                    runtime.analyzer().name());
        }
        Map<String, FieldSchema> persistedFields = byName(persisted);
        Map<String, FieldSchema> runtimeFields = byName(runtime);
        for (FieldSchema runtimeField : runtimeFields.values()) {
            FieldSchema persistedField = persistedFields.get(runtimeField.name());
            if (persistedField == null) {
                return SchemaMismatchException.of("fields." + runtimeField.name(), "missing", "present");
            }
            SchemaMismatchException fieldMismatch = fieldMismatch(persistedField, runtimeField);
            if (fieldMismatch != null) {
                return fieldMismatch;
            }
        }
        for (FieldSchema persistedField : persistedFields.values()) {
            if (!runtimeFields.containsKey(persistedField.name())) {
                return SchemaMismatchException.of("fields." + persistedField.name(), "present", "missing");
            }
        }
        EmbeddingModelIdentity persistedEmbedding = persisted.embedding();
        EmbeddingModelIdentity runtimeEmbedding = runtime.embedding();
        if (!persistedEmbedding.modelId().equals(runtimeEmbedding.modelId())) {
            return SchemaMismatchException.of(
                    "embedding.modelId", persistedEmbedding.modelId(), runtimeEmbedding.modelId());
        }
        if (!persistedEmbedding.engine().equals(runtimeEmbedding.engine())) {
            return SchemaMismatchException.of(
                    "embedding.engine", persistedEmbedding.engine(), runtimeEmbedding.engine());
        }
        if (persistedEmbedding.dimension() != runtimeEmbedding.dimension()) {
            return SchemaMismatchException.of(
                    "embedding.dimension",
                    String.valueOf(persistedEmbedding.dimension()),
                    String.valueOf(runtimeEmbedding.dimension()));
        }
        if (!persistedEmbedding.digest().equals(runtimeEmbedding.digest())) {
            return SchemaMismatchException.of(
                    "embedding.digest", persistedEmbedding.digest(), runtimeEmbedding.digest());
        }
        return null;
    }

    private static SchemaMismatchException fieldMismatch(FieldSchema persisted, FieldSchema runtime) {
        String prefix = "fields." + runtime.name() + ".";
        if (persisted.type() != runtime.type()) {
            return SchemaMismatchException.of(
                    prefix + "type", String.valueOf(persisted.type()), String.valueOf(runtime.type()));
        }
        if (persisted.filterable() != runtime.filterable()) {
            return SchemaMismatchException.of(
                    prefix + "filterable",
                    String.valueOf(persisted.filterable()),
                    String.valueOf(runtime.filterable()));
        }
        if (persisted.sortable() != runtime.sortable()) {
            return SchemaMismatchException.of(
                    prefix + "sortable", String.valueOf(persisted.sortable()), String.valueOf(runtime.sortable()));
        }
        if (persisted.facetable() != runtime.facetable()) {
            return SchemaMismatchException.of(
                    prefix + "facetable", String.valueOf(persisted.facetable()), String.valueOf(runtime.facetable()));
        }
        if (persisted.highlightable() != runtime.highlightable()) {
            return SchemaMismatchException.of(
                    prefix + "highlightable",
                    String.valueOf(persisted.highlightable()),
                    String.valueOf(runtime.highlightable()));
        }
        if (!persisted.analyzer().equals(runtime.analyzer())) {
            return SchemaMismatchException.of(prefix + "analyzer", persisted.analyzer(), runtime.analyzer());
        }
        return null;
    }

    private static Map<String, FieldSchema> byName(IndexSchema schema) {
        Map<String, FieldSchema> fields = new LinkedHashMap<>();
        for (FieldSchema field : schema.fields()) {
            fields.put(field.name(), field);
        }
        return fields;
    }
}
