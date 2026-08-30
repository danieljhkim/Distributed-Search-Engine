package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexManagerReadinessTest {

    private static final TextEmbedder EMBEDDER = text -> new float[] {1.0f};

    @TempDir
    Path tempDir;

    @Test
    void readinessRequiresAnOpenWritableLuceneDirectoryWithEnoughDisk() throws Exception {
        IndexManager manager = new IndexManager(tempDir, 1, Duration.ofHours(1), null, EMBEDDER, 0);
        try {
            assertTrue(manager.readiness().ready());
        } finally {
            manager.close();
        }

        HealthHttpServer.Readiness closed = manager.readiness();
        assertEquals("index_manager_closed", closed.reason());
    }

    @Test
    void readinessReportsDiskThresholdFailure() throws Exception {
        try (IndexManager manager = new IndexManager(tempDir, 1, Duration.ofHours(1), null, EMBEDDER, Long.MAX_VALUE)) {
            HealthHttpServer.Readiness readiness = manager.readiness();

            assertTrue(!readiness.ready());
            assertEquals("disk_space_below_threshold", readiness.reason());
        }
    }
}
