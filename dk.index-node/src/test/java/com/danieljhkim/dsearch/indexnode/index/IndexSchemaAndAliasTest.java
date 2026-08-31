package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.enums.FieldType;
import com.danieljhkim.dsearch.common.exception.SchemaMismatchException;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.schema.AnalyzerConfig;
import com.danieljhkim.dsearch.common.schema.EmbeddingModelIdentity;
import com.danieljhkim.dsearch.common.schema.FieldSchema;
import com.danieljhkim.dsearch.common.schema.IndexAlias;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.common.schema.IndexSchemaStore;
import com.danieljhkim.dsearch.common.schema.ReindexJob;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.RepresentativeQuery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexSchemaAndAliasTest {

    private static final TextEmbedder DIM3 = ignored -> new float[] {1.0f, 0.0f, 0.0f};
    private static final TextEmbedder DIM2 = ignored -> new float[] {1.0f, 0.0f};

    @TempDir
    Path tempDir;

    @Test
    void compatibleRollingStartupReopensThePersistedSchema() throws Exception {
        Path base = tempDir.resolve("compatible");
        try (IndexManager manager = manager(base, DIM3, schema("standard", "unspecified", 3))) {
            manager.indexDocumentDurably("catalog", document("doc-1", "rolling startup"));
        }
        try (IndexManager reopened = manager(base, DIM3, schema("standard", "unspecified", 3))) {
            assertEquals(
                    1,
                    reopened.searchDocument("catalog", "rolling", 10, 0, SearchType.BM25)
                            .getTotalHits());
            assertEquals("standard", reopened.inspectSchema("catalog").schema().analyzer().name());
            assertEquals(3, reopened.inspectSchema("catalog").schema().embedding().dimension());
        }
    }

    @Test
    void incompatibleAnalyzerChangeNamesThePropertyAndKeepsTheSourceUnservable() throws Exception {
        Path base = tempDir.resolve("analyzer");
        try (IndexManager manager = manager(base, DIM3, schema("standard", "unspecified", 3))) {
            manager.indexDocumentDurably("catalog", document("doc-1", "analyzer contract"));
        }
        try (IndexManager reopened = manager(base, DIM3, schema("keyword", "unspecified", 3))) {
            SchemaMismatchException mismatch =
                    assertThrows(SchemaMismatchException.class, () -> reopened.searchDocument(
                            "catalog", "analyzer", 10, 0, SearchType.BM25));
            assertEquals("analyzer.name", mismatch.getProperty());
            assertTrue(mismatch.getMessage().contains("analyzer.name"));
        }
    }

    @Test
    void incompatibleModelAndDimensionChangesNameTheEmbeddingProperty() throws Exception {
        Path base = tempDir.resolve("model");
        try (IndexManager manager = manager(base, DIM3, schema("standard", "model-a", 3))) {
            manager.indexDocumentDurably("catalog", document("doc-1", "embedding contract"));
        }
        try (IndexManager modelChange = manager(base, DIM3, schema("standard", "model-b", 3))) {
            SchemaMismatchException mismatch = assertThrows(
                    SchemaMismatchException.class,
                    () -> modelChange.searchDocument("catalog", "embedding", 10, 0, SearchType.BM25));
            assertEquals("embedding.modelId", mismatch.getProperty());
        }

        Path dimBase = tempDir.resolve("dimension");
        try (IndexManager manager = manager(dimBase, DIM3, schema("standard", "unspecified", 3))) {
            manager.indexDocumentDurably("catalog", document("doc-1", "dimension contract"));
        }
        try (IndexManager dimChange = manager(dimBase, DIM2, schema("standard", "unspecified", 2))) {
            SchemaMismatchException mismatch = assertThrows(
                    SchemaMismatchException.class,
                    () -> dimChange.searchDocument("catalog", "dimension", 10, 0, SearchType.BM25));
            assertEquals("embedding.dimension", mismatch.getProperty());
        }
    }

    @Test
    void reindexLeavesTheSourceActiveUntilSwapAndRollbackRestoresIt() throws Exception {
        Path base = tempDir.resolve("reindex");
        try (IndexManager manager = manager(base, DIM3, schema("standard", "unspecified", 3))) {
            manager.indexDocumentDurably("catalog", document("doc-1", "source document"));
            IndexManager.ReindexResult result = manager.reindex(
                    "catalog",
                    "catalog_2",
                    null,
                    List.of(RepresentativeQuery.newBuilder()
                            .setQuery("source")
                            .setSearchType(SearchType.BM25)
                            .setSize(5)
                            .build()));
            assertTrue(result.success());
            assertEquals("catalog", result.sourceIndex());
            assertEquals("catalog_2", result.targetIndex());
            assertEquals(1, result.sourceCount());
            assertEquals(1, result.targetCount());
            assertEquals(
                    1,
                    manager.searchDocument("catalog", "source", 10, 0, SearchType.BM25)
                            .getTotalHits());
            assertEquals("catalog", manager.resolvePhysicalIndex("catalog"));

            IndexAlias swapped = manager.swapAlias("catalog", "catalog_2");
            assertEquals("catalog_2", swapped.getIndexName());
            assertEquals("catalog", swapped.getPreviousIndexName());
            assertEquals(
                    1,
                    manager.searchDocument("catalog", "source", 10, 0, SearchType.BM25)
                            .getTotalHits());

            IndexAlias rolledBack = manager.rollbackAlias("catalog");
            assertEquals("catalog", rolledBack.getIndexName());
            assertEquals("catalog_2", rolledBack.getPreviousIndexName());
            assertEquals("catalog", manager.resolvePhysicalIndex("catalog"));
        }
    }

    @Test
    void interruptedReindexKeepsTheSourceAliasAndRefusesSwap() throws Exception {
        Path base = tempDir.resolve("interrupted");
        try (IndexManager manager = manager(base, DIM3, schema("standard", "unspecified", 3))) {
            manager.indexDocumentDurably("catalog", document("doc-1", "keep source live"));
            ReindexJob job = new ReindexJob();
            job.setJobId("interrupted-job");
            job.setSourceAlias("catalog");
            job.setSourceIndex("catalog");
            job.setTargetIndex("catalog_2");
            job.setStatus(ReindexJob.STATUS_INTERRUPTED);
            job.setSourceCount(1);
            job.setTargetCount(0);
            manager.aliasStore().saveJob(job);
            manager.createIndex("catalog_2", "catalog_2", null);

            assertEquals("catalog", manager.resolvePhysicalIndex("catalog"));
            assertEquals(
                    1,
                    manager.searchDocument("catalog", "keep", 10, 0, SearchType.BM25)
                            .getTotalHits());
            IllegalArgumentException refused =
                    assertThrows(IllegalArgumentException.class, () -> manager.swapAlias("catalog", "catalog_2"));
            assertTrue(refused.getMessage().contains("verified"));
        }
    }

    @Test
    void concurrentAliasReadersSeeACompleteGeneration() throws Exception {
        Path base = tempDir.resolve("readers");
        try (IndexManager manager = manager(base, DIM3, schema("standard", "unspecified", 3))) {
            manager.indexDocumentDurably("catalog", document("doc-1", "concurrent alias"));
            manager.reindex("catalog", "catalog_2", null, List.of());
            AtomicInteger successes = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            List<Future<?>> tasks = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    tasks.add(executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        for (int n = 0; n < 20; n++) {
                            assertEquals(
                                    1,
                                    manager.searchDocument("catalog", "concurrent", 10, 0, SearchType.BM25)
                                            .getTotalHits());
                            successes.incrementAndGet();
                        }
                        return null;
                    }));
                }
                start.countDown();
                manager.swapAlias("catalog", "catalog_2");
                for (Future<?> task : tasks) {
                    task.get(5, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }
            assertTrue(successes.get() >= 4);
            assertEquals("catalog_2", manager.resolvePhysicalIndex("catalog"));
        }
    }

    @Test
    void snapshotRestoreKeepsSchemaMetadata() throws Exception {
        Path live = tempDir.resolve("live");
        Path snapshot = tempDir.resolve("snapshot");
        IndexSchema persisted;
        try (IndexManager manager = manager(live, DIM3, schema("standard", "unspecified", 3))) {
            manager.indexDocumentDurably("catalog", document("doc-1", "snapshot metadata"));
            persisted = manager.inspectSchema("catalog").schema();
        }
        Path shard = live.resolve("shard-catalog");
        Path snapshotShard = snapshot.resolve("shard-catalog");
        Files.createDirectories(snapshotShard);
        IndexSchemaStore.copyTo(shard, snapshotShard);
        IndexSchema restored = new IndexSchemaStore().load(snapshotShard);
        assertEquals(persisted, restored);
        assertNotEquals(0, restored.embedding().dimension());
    }

    private static IndexManager manager(Path base, TextEmbedder embedder, IndexSchema schema) {
        List<FieldConfig> fields = List.of(titleConfig());
        return new IndexManager(base, 10, Duration.ofHours(1), fields, embedder, 0, schema);
    }

    private static FieldConfig titleConfig() {
        FieldConfig config = new FieldConfig();
        config.setName("title");
        config.setType(FieldType.STRING);
        config.setFilterable(true);
        config.setSortable(true);
        config.setHighlightable(true);
        config.setAnalyzer("standard");
        return config;
    }

    private static IndexSchema schema(String analyzer, String modelId, int dimension) {
        return IndexSchema.current(
                AnalyzerConfig.of(analyzer),
                List.of(new FieldSchema("title", FieldType.STRING, true, true, false, true, "standard")),
                EmbeddingModelIdentity.of(modelId, "unspecified", dimension));
    }

    private static SearchDocument document(String id, String title) {
        return new SearchDocument(id, Map.of("title", title, "content", title));
    }
}
