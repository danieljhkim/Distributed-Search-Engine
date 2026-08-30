package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexManagerInitRetryTest {

    private static final Duration LONG_FLUSH_INTERVAL = Duration.ofHours(1);

    @TempDir
    Path tempDir;

    @Test
    void failedSecondShardRollsBackLocksAndOwnedEmbedderSoRetryBecomesReady() throws Exception {
        Path baseDir = tempDir.resolve("indexes");
        Files.createDirectories(baseDir.resolve("shard-0"));
        Files.createDirectories(baseDir.resolve("shard-1"));

        TrackingEmbedder firstAttemptEmbedder = new TrackingEmbedder();
        RuntimeException firstFailure;
        try (HeldWriteLock ignored = new HeldWriteLock(baseDir.resolve("shard-1"))) {
            firstFailure = assertThrows(
                    RuntimeException.class,
                    () -> new IndexManager(baseDir, 1, LONG_FLUSH_INTERVAL, null, firstAttemptEmbedder, true, 0));
        }

        assertEquals("Failed to initialize ShardIndex for shard 1", firstFailure.getMessage());
        assertInstanceOf(LockObtainFailedException.class, firstFailure.getCause());
        assertEquals(1, firstAttemptEmbedder.closeCount());

        TrackingEmbedder retryEmbedder = new TrackingEmbedder();
        try (IndexManager manager = new IndexManager(baseDir, 1, LONG_FLUSH_INTERVAL, null, retryEmbedder, true, 0)) {
            assertTrue(manager.readiness().ready());
            assertEquals(0, retryEmbedder.closeCount());
        }
        assertEquals(1, retryEmbedder.closeCount());
    }

    private static final class TrackingEmbedder implements TextEmbedder, Closeable {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public float[] embed(String text) {
            return new float[] {1.0f};
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        int closeCount() {
            return closeCount.get();
        }
    }

    private static final class HeldWriteLock implements Closeable {
        private final Directory directory;
        private final IndexWriter writer;

        HeldWriteLock(Path shardDir) throws IOException {
            Files.createDirectories(shardDir);
            this.directory = FSDirectory.open(shardDir);
            IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            this.writer = new IndexWriter(directory, config);
        }

        @Override
        public void close() throws IOException {
            writer.close();
            directory.close();
        }
    }
}
