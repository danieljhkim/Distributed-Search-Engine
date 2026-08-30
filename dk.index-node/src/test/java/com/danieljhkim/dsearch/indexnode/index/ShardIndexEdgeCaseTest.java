package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.exception.ParseGoneWrongException;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShardIndexEdgeCaseTest {

    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};

    @TempDir
    Path tempDir;

    @Test
    void invalidQueryIsTranslatedAndFacetShortcutsReturnEmpty() throws IOException {
        try (ShardIndex index = new ShardIndex("0", tempDir, Map.of(), FAKE_EMBEDDER)) {
            assertThrows(ParseGoneWrongException.class, () -> index.search("[", 10, 0));
            assertTrue(index.computeFacets(new MatchAllDocsQuery(), null).isEmpty());
            assertTrue(index.computeFacets(new MatchAllDocsQuery(), List.of()).isEmpty());
        }
    }

    @Test
    void configuredNumericTypesAndUnconfiguredValuesSurviveRetrieval() throws IOException {
        List<FieldConfig> configs = List.of(
                fieldConfig("int", FieldType.INTEGER, true, true, true),
                fieldConfig("long", FieldType.LONG, true, true, true),
                fieldConfig("date", FieldType.DATE, true, true, true),
                fieldConfig("double", FieldType.DOUBLE, true, true, true),
                fieldConfig("stored", FieldType.STRING, true, false, false),
                fieldConfig("bad-int", FieldType.INTEGER, true, true, true));
        Map<String, FieldConfig> fieldConfigMap =
                configs.stream().collect(Collectors.toMap(FieldConfig::getName, Function.identity()));

        try (ShardIndex index = new ShardIndex("0", tempDir, fieldConfigMap, FAKE_EMBEDDER)) {
            index.index(new SearchDocument(
                    "doc-1",
                    Map.of(
                            "title", "Typed document",
                            "content", "typed content",
                            "int", "3",
                            "long", "3000000000",
                            "date", "1700000000",
                            "double", "4.5",
                            "stored", "stored value",
                            "bad-int", "not numeric")));
            index.commit();

            SearchResult result = index.search(
                    "typed",
                    10,
                    0,
                    null,
                    false,
                    List.of(FacetRequest.newBuilder()
                            .setField("stored")
                            .setSize(5)
                            .build()));
            assertEquals(1, result.getTotalHits());
            assertEquals("stored value", result.getHits().get(0).getFields().get("stored"));
        }
    }

    @Test
    void semanticSearchHandlesEmptyEmbeddingsAndUnparseableHighlightQueries() throws IOException {
        try (ShardIndex index = new ShardIndex("0", tempDir.resolve("semantic"), Map.of(), FAKE_EMBEDDER)) {
            index.index(new SearchDocument("doc-1", Map.of("title", "Semantic", "content", "semantic content")));
            index.commit();

            SearchResult result = index.semanticSearch("[", 10, 0, null, true, null);
            assertEquals(1, result.getTotalHits());
            assertTrue(result.getHits().get(0).getHighlightedFields() == null);
        }

        TextEmbedder emptyEmbedder = ignored -> null;
        try (ShardIndex index = new ShardIndex("0", tempDir.resolve("empty"), Map.of(), emptyEmbedder)) {
            SearchResult result = index.semanticSearch("anything", 10, 0);
            assertEquals(0, result.getTotalHits());
            assertTrue(result.getHits().isEmpty());
        }
    }

    @Test
    void initializationAndClosedIndexFailuresAreObservable() throws IOException {
        Path file = tempDir.resolve("not-a-directory");
        Files.writeString(file, "not a directory");
        assertThrows(RuntimeException.class, () -> new ShardIndex("0", file, Map.of(), FAKE_EMBEDDER));

        ShardIndex index = new ShardIndex("0", tempDir.resolve("closed"), Map.of(), FAKE_EMBEDDER);
        index.close();
        assertThrows(RuntimeException.class, () -> index.index(new SearchDocument("doc", Map.of("content", "x"))));
        assertThrows(RuntimeException.class, () -> index.commit());
    }

    @Test
    void failedConstructorReleasesWriteLockBeforeRethrowing() throws IOException {
        Path base = tempDir.resolve("lock-release");
        NullPointerException thrown =
                assertThrows(NullPointerException.class, () -> new ShardIndex("0", base, Map.of(), null));
        assertEquals("embeddingService", thrown.getMessage());

        try (ShardIndex index = new ShardIndex("0", base, Map.of(), FAKE_EMBEDDER)) {
            assertEquals("0", index.getShardId());
        }
    }

    private static FieldConfig fieldConfig(
            String name, FieldType type, boolean filterable, boolean sortable, boolean facetable) {
        FieldConfig config = new FieldConfig();
        config.setName(name);
        config.setType(type);
        config.setFilterable(filterable);
        config.setSortable(sortable);
        config.setFacetable(facetable);
        return config;
    }
}
