package com.danieljhkim.dsearch.common.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.exception.SchemaMismatchException;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexSchemaCompatibilityTest {

    @Test
    void identicalSchemasAreCompatible() {
        IndexSchema schema = schema("standard", "model-a", 384, field("title", "standard"));
        assertNull(IndexSchemaCompatibility.findMismatch(schema, schema));
    }

    @Test
    void analyzerMismatchNamesTheProperty() {
        SchemaMismatchException mismatch = IndexSchemaCompatibility.findMismatch(
                schema("standard", "model-a", 384, field("title", "standard")),
                schema("keyword", "model-a", 384, field("title", "standard")));
        assertEquals("analyzer.name", mismatch.getProperty());
        assertTrue(mismatch.getMessage().contains("analyzer.name"));
    }

    @Test
    void fieldAnalyzerMismatchNamesTheField() {
        SchemaMismatchException mismatch = IndexSchemaCompatibility.findMismatch(
                schema("standard", "model-a", 384, field("title", "standard")),
                schema("standard", "model-a", 384, field("title", "keyword")));
        assertEquals("fields.title.analyzer", mismatch.getProperty());
    }

    @Test
    void embeddingModelMismatchNamesModelId() {
        SchemaMismatchException mismatch = IndexSchemaCompatibility.findMismatch(
                schema("standard", "model-a", 384, field("title", "standard")),
                schema("standard", "model-b", 384, field("title", "standard")));
        assertEquals("embedding.modelId", mismatch.getProperty());
    }

    @Test
    void embeddingDimensionMismatchNamesDimension() {
        SchemaMismatchException mismatch = IndexSchemaCompatibility.findMismatch(
                schema("standard", "model-a", 384, field("title", "standard")),
                schema("standard", "model-a", 768, field("title", "standard")));
        assertEquals("embedding.dimension", mismatch.getProperty());
        assertThrows(
                SchemaMismatchException.class,
                () -> IndexSchemaCompatibility.requireCompatible(
                        schema("standard", "model-a", 384, field("title", "standard")),
                        schema("standard", "model-a", 768, field("title", "standard"))));
    }

    private static FieldSchema field(String name, String analyzer) {
        return new FieldSchema(name, FieldType.STRING, true, true, false, true, analyzer);
    }

    private static IndexSchema schema(String analyzer, String modelId, int dimension, FieldSchema field) {
        return IndexSchema.current(
                AnalyzerConfig.of(analyzer), List.of(field), EmbeddingModelIdentity.of(modelId, "PyTorch", dimension));
    }
}
