package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.proto.common.SearchType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexManagerReplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void duplicateDeliveryIsIdempotentAndReorderedOperationIsRejected() throws Exception {
        try (IndexManager manager = manager()) {
            var applied = manager.applyReplicatedIndex("tenant_r1", document("doc-1", "new"), "op-10", 10, 4);
            var duplicate = manager.applyReplicatedIndex("tenant_r1", document("doc-1", "new"), "op-10", 10, 4);

            assertFalse(applied.duplicate());
            assertTrue(duplicate.duplicate());
            assertEquals(
                    1,
                    manager.searchDocument("tenant_r1", "new", 10, 0, SearchType.BM25)
                            .getTotalHits());
            assertThrows(
                    IndexManager.StaleMutationException.class,
                    () -> manager.applyReplicatedIndex("tenant_r1", document("doc-1", "old"), "op-9", 9, 4));
            assertEquals(
                    0,
                    manager.searchDocument("tenant_r1", "old", 10, 0, SearchType.BM25)
                            .getTotalHits());
        }
    }

    @Test
    void stalePlacementGenerationCannotWriteAfterNewGeneration() throws Exception {
        try (IndexManager manager = manager()) {
            manager.applyReplicatedIndex("tenant_r1", document("doc-1", "current"), "op-1", 1, 8);

            assertThrows(
                    IndexManager.StaleMutationException.class,
                    () -> manager.applyReplicatedDelete("tenant_r1", "doc-1", "op-2", 2, 7));
            assertEquals(
                    1,
                    manager.searchDocument("tenant_r1", "current", 10, 0, SearchType.BM25)
                            .getTotalHits());
        }
    }

    @Test
    void mutationGenerationAndDeleteSurviveRestart() throws Exception {
        Path data = tempDir.resolve("restart");
        try (IndexManager manager = manager(data)) {
            manager.applyReplicatedIndex("tenant_r1", document("doc-1", "durable"), "op-1", 1, 3);
            manager.applyReplicatedDelete("tenant_r1", "doc-1", "op-2", 2, 3);
        }

        try (IndexManager restarted = manager(data)) {
            var duplicate = restarted.applyReplicatedDelete("tenant_r1", "doc-1", "op-2", 2, 3);
            assertTrue(duplicate.duplicate());
            assertThrows(
                    IndexManager.StaleMutationException.class,
                    () -> restarted.applyReplicatedIndex("tenant_r1", document("doc-1", "lost"), "op-1", 1, 3));
            assertEquals(
                    0,
                    restarted
                            .searchDocument("tenant_r1", "durable", 10, 0, SearchType.BM25)
                            .getTotalHits());
        }
    }

    private IndexManager manager() {
        return manager(tempDir.resolve("data"));
    }

    private static IndexManager manager(Path path) {
        return new IndexManager(path, 10, Duration.ofHours(1), null, ignored -> new float[] {1.0f, 0.0f});
    }

    private static SearchDocument document(String id, String content) {
        return new SearchDocument(id, Map.of("title", content, "content", content));
    }
}
