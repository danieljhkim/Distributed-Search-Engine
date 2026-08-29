package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.indexnode.grpc.IndexServiceImpl;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    void durableDeleteIsVisibleBeforeReturning() throws IOException {
        try (IndexManager manager = new IndexManager(tempDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER)) {
            manager.indexDocument(SHARD_ID, document("doc-1", "Alpha", "alpha durable delete content"));
            manager.commitAll();

            manager.deleteDocumentDurably(SHARD_ID, "doc-1");

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

    @Test
    void durableAcknowledgementSurvivesAbruptProcessExit() throws Exception {
        Path indexDir = tempDir.resolve("crash-index");
        Path acknowledgementFile = tempDir.resolve("acknowledged");
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        CrashWriter.class.getName(),
                        indexDir.toString(),
                        acknowledgementFile.toString())
                .redirectErrorStream(true)
                .start();

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "crash writer did not exit");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        assertEquals("acknowledged", Files.readString(acknowledgementFile));

        try (IndexManager reopened = new IndexManager(indexDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER)) {
            assertTotalHits(reopened, "durable", 1);
        }
    }

    @Test
    void singleIndexCommitFailureReturnsGrpcErrorWithoutSuccessResponse() throws IOException {
        try (FailingCommitIndexManager manager = new FailingCommitIndexManager(tempDir.resolve("single-failure"), 1)) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            RecordingObserver<IndexDocumentResponse> observer = new RecordingObserver<>();

            service.indexDocument(
                    IndexDocumentRequest.newBuilder()
                            .setPartitionId(SHARD_ID)
                            .setDocument(protoDocument("doc-1", "failure content"))
                            .build(),
                    observer);

            assertNull(observer.value);
            assertFalse(observer.completed);
            StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.error);
            assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
        }
    }

    @Test
    void bulkIndexReportsEachDurableSuccessAndRetryableFailure() throws IOException {
        try (FailingCommitIndexManager manager = new FailingCommitIndexManager(tempDir.resolve("bulk-failure"), 2)) {
            IndexServiceImpl service = new IndexServiceImpl(manager);
            RecordingObserver<BulkIndexDocumentResponse> observer = new RecordingObserver<>();

            service.bulkIndexDocument(
                    BulkIndexDocumentRequest.newBuilder()
                            .setPartitionId(SHARD_ID)
                            .addDocuments(protoDocument("doc-1", "first durable content"))
                            .addDocuments(protoDocument("", "failed content"))
                            .addDocuments(protoDocument("doc-3", "third durable content"))
                            .build(),
                    observer);

            assertNull(observer.error);
            assertTrue(observer.completed);
            BulkIndexDocumentResponse response = observer.value;
            assertFalse(response.getSuccess());
            assertEquals(java.util.List.of("doc-1", "doc-3"), response.getIdsList());
            assertEquals(3, response.getResultsCount());
            assertTrue(response.getResults(0).getSuccess());
            assertEquals(1, response.getResults(1).getRequestIndex());
            assertFalse(response.getResults(1).getId().isBlank());
            assertFalse(response.getResults(1).getSuccess());
            assertFalse(response.getResults(1).getError().isBlank());
            assertTrue(response.getResults(2).getSuccess());

            String retryId = response.getResults(1).getId();
            manager.indexDocumentDurably(SHARD_ID, document(retryId, "Retry", "failed content"));
            assertTotalHits(manager, "failed", 1);
        }
    }

    @Test
    void constructorRejectsInvalidBufferAndFlushBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexManager(tempDir.resolve("zero-buffer"), 0, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexManager(tempDir.resolve("zero-interval"), 1, Duration.ZERO, null, FAKE_EMBEDDER));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexManager(
                        tempDir.resolve("negative-interval"), 1, Duration.ofSeconds(-1), null, FAKE_EMBEDDER));
        assertThrows(
                NullPointerException.class,
                () -> new IndexManager(tempDir.resolve("null-embedder"), 1, LONG_FLUSH_INTERVAL, null, null));
    }

    @Test
    void backgroundSchedulerFlushesPendingOperationsAfterInterval() throws Exception {
        try (IndexManager manager =
                new IndexManager(tempDir.resolve("scheduled"), 100, Duration.ofMillis(25), null, FAKE_EMBEDDER)) {
            manager.indexDocument(SHARD_ID, document("doc-1", "Scheduled", "scheduled content"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            long hits = 0;
            while (hits == 0 && System.nanoTime() < deadline) {
                hits = manager.searchDocument(SHARD_ID, "scheduled", 10, 0, SearchType.BM25)
                        .getTotalHits();
                if (hits == 0) {
                    Thread.sleep(20);
                }
            }
            assertEquals(1, hits);
        }
    }

    @Test
    void concurrentDurableWritesRemainVisibleExactlyOnce() throws Exception {
        try (IndexManager manager =
                        new IndexManager(tempDir.resolve("concurrent"), 100, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER);
                ExecutorService executor = Executors.newFixedThreadPool(4)) {
            java.util.List<Future<?>> writes = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int id = i;
                writes.add(executor.submit(() -> {
                    try {
                        manager.indexDocumentDurably(
                                SHARD_ID, document("doc-" + id, "Concurrent", "concurrent content"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> write : writes) {
                write.get(5, TimeUnit.SECONDS);
            }
            assertEquals(
                    12,
                    manager.searchDocument(SHARD_ID, "concurrent", 20, 0, SearchType.BM25)
                            .getTotalHits());
        }
    }

    @Test
    void emptyEmbeddingDoesNotCreateSemanticHits() throws IOException {
        TextEmbedder emptyEmbedder = ignored -> new float[0];
        try (IndexManager manager =
                new IndexManager(tempDir.resolve("empty-embedding"), 1, LONG_FLUSH_INTERVAL, null, emptyEmbedder)) {
            manager.indexDocumentDurably(SHARD_ID, document("doc-1", "No vector", "content without a vector"));
            SearchResult result = manager.searchDocument(SHARD_ID, "content", 10, 0, SearchType.SEMANTIC);
            assertEquals(0, result.getTotalHits());
            assertTrue(result.getHits().isEmpty());
        }
    }

    private static SearchDocument document(String id, String title, String content) {
        return new SearchDocument(id, Map.of("title", title, "content", content));
    }

    private static Document protoDocument(String id, String content) {
        return Document.newBuilder()
                .setId(id)
                .addFields(Field.newBuilder().setName("content").setValue(content))
                .build();
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

    public static final class CrashWriter {
        private CrashWriter() {}

        public static void main(String[] args) throws Exception {
            Path indexDir = Path.of(args[0]);
            Path acknowledgementFile = Path.of(args[1]);
            IndexManager manager = new IndexManager(indexDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER);
            RecordingObserver<IndexDocumentResponse> observer = new RecordingObserver<>();
            new IndexServiceImpl(manager)
                    .indexDocument(
                            IndexDocumentRequest.newBuilder()
                                    .setPartitionId(SHARD_ID)
                                    .setDocument(protoDocument("doc-1", "durable crash content"))
                                    .build(),
                            observer);
            if (observer.error != null
                    || !observer.completed
                    || observer.value == null
                    || !observer.value.getSuccess()) {
                throw new IllegalStateException("index request was not durably acknowledged", observer.error);
            }
            Files.writeString(acknowledgementFile, "acknowledged");
            Runtime.getRuntime().halt(0);
        }
    }

    private static final class FailingCommitIndexManager extends IndexManager {
        private final int failingCommitAttempt;
        private final AtomicInteger commitAttempts = new AtomicInteger();

        FailingCommitIndexManager(Path baseDir, int failingCommitAttempt) {
            super(baseDir, 10, LONG_FLUSH_INTERVAL, null, FAKE_EMBEDDER);
            this.failingCommitAttempt = failingCommitAttempt;
        }

        @Override
        void commitShard(ShardIndex shardIndex) throws IOException {
            if (commitAttempts.incrementAndGet() == failingCommitAttempt) {
                throw new IOException("simulated commit failure");
            }
            super.commitShard(shardIndex);
        }
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
