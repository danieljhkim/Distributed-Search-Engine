package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.common.SearchType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexManagerBufferingTest {

    private static final String SHARD_ID = "0";
    private static final Duration LONG_FLUSH_INTERVAL = Duration.ofHours(1);
    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};

    @TempDir
    Path tempDir;

    @Test
    void bufferedIndexingIsHiddenUntilFlush() throws IOException {
        try (IndexManager manager = new IndexManager(tempDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER)) {
            manager.indexDocument(SHARD_ID, document("doc-1", "Alpha", "alpha buffered content"));

            assertTotalHits(manager, "alpha", 0);

            manager.commitAll();

            assertTotalHits(manager, "alpha", 1);
        }
    }

    @Test
    void bufferedDeleteIsHiddenUntilFlush() throws IOException {
        try (IndexManager manager = new IndexManager(tempDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER)) {
            manager.indexDocument(SHARD_ID, document("doc-1", "Alpha", "alpha buffered content"));
            manager.commitAll();
            assertTotalHits(manager, "alpha", 1);

            manager.deleteDocument(SHARD_ID, "doc-1");

            assertTotalHits(manager, "alpha", 1);

            manager.commitAll();

            assertTotalHits(manager, "alpha", 0);
        }
    }

    @Test
    void bufferedDeleteAndReindexPreserveOperationOrder() throws IOException {
        try (IndexManager manager = new IndexManager(tempDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER)) {
            manager.indexDocument(SHARD_ID, document("doc-1", "Alpha", "alpha content"));
            manager.commitAll();

            manager.deleteDocument(SHARD_ID, "doc-1");
            manager.indexDocument(SHARD_ID, document("doc-1", "Bravo", "bravo content"));
            manager.commitAll();

            assertTotalHits(manager, "alpha", 0);
            assertTotalHits(manager, "bravo", 1);
        }
    }

    @Test
    void closeFlushesBufferedOperationsBeforeClosingShardResources() throws IOException {
        IndexManager manager = new IndexManager(tempDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER);
        manager.indexDocument(SHARD_ID, document("doc-1", "Shutdown", "shutdown flush content"));

        manager.close();

        try (IndexManager reopened = new IndexManager(tempDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER)) {
            assertTotalHits(reopened, "shutdown", 1);
        }
    }

    @Test
    void closeFlushesBufferedDeletesBeforeClosingShardResources() throws IOException {
        IndexManager manager = new IndexManager(tempDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER);
        manager.indexDocument(SHARD_ID, document("doc-1", "Shutdown", "shutdown delete content"));
        manager.commitAll();
        manager.deleteDocument(SHARD_ID, "doc-1");

        manager.close();

        try (IndexManager reopened = new IndexManager(tempDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER)) {
            assertTotalHits(reopened, "shutdown", 0);
        }
    }

    @Test
    void fakeEmbeddingProviderCanBeInjectedWithoutLoadingDjlModels() throws IOException {
        CountingEmbedder embedder = new CountingEmbedder();
        try (IndexManager manager = new IndexManager(tempDir, 1, LONG_FLUSH_INTERVAL, null, embedder)) {
            manager.indexDocument(SHARD_ID, document("doc-1", "Semantic", "semantic vector content"));
            assertEquals(1, embedder.calls());

            SearchResult result = manager.searchDocument(SHARD_ID, "semantic", 10, 0, SearchType.SEMANTIC);

            assertEquals(1, result.getTotalHits());
            assertEquals(2, embedder.calls());
        }
    }

    private static SearchDocument document(String id, String title, String content) {
        return new SearchDocument(id, Map.of("title", title, "content", content));
    }

    private static void assertTotalHits(IndexManager manager, String query, long expectedTotalHits) throws IOException {
        SearchResult result = manager.searchDocument(SHARD_ID, query, 10, 0, SearchType.BM25);
        assertEquals(expectedTotalHits, result.getTotalHits());
    }

    private static final class CountingEmbedder implements TextEmbedder {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            return new float[] {1.0f, 0.0f, 0.0f};
        }

        int calls() {
            return calls.get();
        }
    }
}
