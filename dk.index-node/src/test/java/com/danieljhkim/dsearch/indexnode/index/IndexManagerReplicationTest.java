package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.proto.common.SearchType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
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
            assertThrows(
                    IndexManager.StaleMutationException.class,
                    () -> manager.applyReplicatedIndex(
                            "tenant_r1", document("doc-1", "conflict"), "different-op", 10, 4));
            assertEquals(
                    0,
                    manager.searchDocument("tenant_r1", "old", 10, 0, SearchType.BM25)
                            .getTotalHits());
        }
    }

    @Test
    void primaryAllocatedGenerationIsMonotonicIdempotentAndDurableAcrossRestart() throws Exception {
        Path data = tempDir.resolve("primary-allocation");
        try (IndexManager manager = manager(data)) {
            var indexed = manager.applyReplicatedIndex(
                    "tenant_r1", document("doc-1", "first"), "op-1", 0, 3, "tenant", "n0", true);
            var deleted = manager.applyReplicatedDelete("tenant_r1", "doc-1", "op-2", 0, 3, "tenant", "n0", true);

            assertEquals(1L, indexed.committedGeneration());
            assertEquals(2L, deleted.committedGeneration());
        }

        try (IndexManager restarted = manager(data)) {
            var duplicate = restarted.applyReplicatedDelete("tenant_r1", "doc-1", "op-2", 0, 3, "tenant", "n0", true);
            var next = restarted.applyReplicatedIndex(
                    "tenant_r1", document("doc-1", "after restart"), "op-3", 0, 3, "tenant", "n0", true);

            assertTrue(duplicate.duplicate());
            assertEquals(2L, duplicate.committedGeneration());
            assertEquals(3L, next.committedGeneration());
            assertThrows(
                    IndexManager.StaleMutationException.class,
                    () -> restarted.applyReplicatedIndex("tenant_r1", document("doc-1", "stale"), "op-stale", 2, 3));
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

    @Test
    void crashAtEachCommitBoundaryPublishesDocumentAndFenceTogether() throws Exception {
        for (IndexManager.MutationCommitStage stage : IndexManager.MutationCommitStage.values()) {
            Path data = tempDir.resolve("crash-" + stage.name().toLowerCase());
            Process process = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            CrashMutationWriter.class.getName(),
                            data.toString(),
                            stage.name())
                    .redirectErrorStream(true)
                    .start();

            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "crash writer did not exit at " + stage);
            assertEquals(
                    17, process.exitValue(), new String(process.getInputStream().readAllBytes()));

            try (IndexManager restarted = manager(data)) {
                if (stage == IndexManager.MutationCommitStage.AFTER_COMMIT) {
                    assertEquals(
                            1,
                            restarted
                                    .searchDocument("tenant_r1", "new", 10, 0, SearchType.BM25)
                                    .getTotalHits());
                    assertTrue(restarted
                            .applyReplicatedIndex("tenant_r1", document("doc-1", "new"), "op-10", 10, 4)
                            .duplicate());
                    assertThrows(
                            IndexManager.StaleMutationException.class,
                            () -> restarted.applyReplicatedIndex("tenant_r1", document("doc-1", "old"), "op-9", 9, 4));
                } else {
                    assertEquals(
                            0,
                            restarted
                                    .searchDocument("tenant_r1", "new", 10, 0, SearchType.BM25)
                                    .getTotalHits());
                    assertFalse(restarted
                            .applyReplicatedIndex("tenant_r1", document("doc-1", "old"), "op-9", 9, 4)
                            .duplicate());
                }
            }
        }
    }

    @Test
    void uncertainPrecommitFailureWriteFencesShardAndRollsBackOnClose() throws Exception {
        Path data = tempDir.resolve("write-fenced");
        IndexManager manager = manager(data);
        manager.setMutationFaultInjector((physicalIndex, stage) -> {
            if (stage == IndexManager.MutationCommitStage.AFTER_MUTATION_APPLIED) {
                throw new IOException("simulated uncertain commit");
            }
        });

        assertThrows(
                IOException.class,
                () -> manager.applyReplicatedIndex("tenant_r1", document("doc-1", "uncommitted"), "op-10", 10, 4));
        assertFalse(manager.readiness().ready());
        assertEquals("shard_write_fenced", manager.readiness().reason());
        IOException fenced = assertThrows(
                IOException.class,
                () -> manager.indexDocumentDurably("tenant_r1", document("doc-2", "must-not-commit")));
        assertTrue(fenced.getMessage().contains("write-fenced"));
        manager.close();

        try (IndexManager restarted = manager(data)) {
            assertTrue(restarted.readiness().ready());
            assertEquals(
                    0,
                    restarted
                            .searchDocument("tenant_r1", "uncommitted", 10, 0, SearchType.BM25)
                            .getTotalHits());
            assertFalse(restarted
                    .applyReplicatedIndex("tenant_r1", document("doc-1", "older"), "op-9", 9, 4)
                    .duplicate());
        }
    }

    @Test
    void concurrentShardCommitsRetainEveryFenceAcrossRestart() throws Exception {
        Path data = tempDir.resolve("concurrent-shards");
        CyclicBarrier bothCommitsReady = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (IndexManager manager = manager(data)) {
            manager.indexDocumentDurably("tenant_a", document("seed-a", "seed"));
            manager.indexDocumentDurably("tenant_b", document("doc-b", "delete-me"));
            manager.setMutationFaultInjector((physicalIndex, stage) -> {
                if (stage == IndexManager.MutationCommitStage.AFTER_COMMIT_DATA_SET) {
                    try {
                        bothCommitsReady.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IOException("failed to overlap per-shard mutation commits", e);
                    }
                }
            });

            Future<IndexManager.MutationResult> indexed = executor.submit(
                    () -> manager.applyReplicatedIndex("tenant_a", document("doc-a", "committed-a"), "op-a", 10, 7));
            Future<IndexManager.MutationResult> deleted =
                    executor.submit(() -> manager.applyReplicatedDelete("tenant_b", "doc-b", "op-b", 20, 7));

            assertFalse(indexed.get(10, TimeUnit.SECONDS).duplicate());
            assertFalse(deleted.get(10, TimeUnit.SECONDS).duplicate());
        } finally {
            executor.shutdownNow();
        }

        try (IndexManager restarted = manager(data)) {
            assertEquals(
                    1,
                    restarted
                            .searchDocument("tenant_a", "committed", 10, 0, SearchType.BM25)
                            .getTotalHits());
            assertEquals(
                    0,
                    restarted
                            .searchDocument("tenant_b", "delete", 10, 0, SearchType.BM25)
                            .getTotalHits());
            assertTrue(restarted
                    .applyReplicatedIndex("tenant_a", document("doc-a", "committed-a"), "op-a", 10, 7)
                    .duplicate());
            assertTrue(restarted
                    .applyReplicatedDelete("tenant_b", "doc-b", "op-b", 20, 7)
                    .duplicate());
            assertThrows(
                    IndexManager.StaleMutationException.class,
                    () -> restarted.applyReplicatedIndex("tenant_b", document("doc-b", "stale"), "op-stale", 19, 7));
        }
    }

    @Test
    void incompleteLuceneMutationMetadataFailsClosed() throws Exception {
        Path data = tempDir.resolve("corrupt-commit-data");
        try (IndexManager manager = manager(data)) {
            manager.applyReplicatedIndex("tenant_r1", document("doc-1", "durable"), "op-1", 1, 3);
        }

        Path shard = data.resolve("shard-tenant_r1");
        try (var directory = FSDirectory.open(shard);
                var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            Map<String, String> corrupt = new HashMap<>(
                    DirectoryReader.listCommits(directory).getLast().getUserData());
            corrupt.put("dsearch.replication.count", "2");
            writer.setLiveCommitData(corrupt.entrySet());
            writer.commit();
        }

        RuntimeException failure = assertThrows(RuntimeException.class, () -> manager(data));
        assertTrue(failure.getMessage().contains("durable state"));
    }

    @Test
    void legacyLedgerIsValidatedAndMigratedIntoLuceneCommit() throws Exception {
        Path data = tempDir.resolve("legacy-migration");
        try (IndexManager manager = manager(data)) {
            manager.indexDocumentDurably("tenant_r1", document("doc-1", "durable"));
        }

        Properties legacy = new Properties();
        legacy.setProperty("mutation.count", "1");
        legacy.setProperty("mutation.0.key", "tenant_r1\0doc-1");
        legacy.setProperty("mutation.0.operationId", "op-1");
        legacy.setProperty("mutation.0.operationGeneration", "1");
        legacy.setProperty("mutation.0.placementGeneration", "3");
        legacy.setProperty("mutation.0.type", "INDEX");
        Path legacyFile = data.resolve("replication-mutations.properties");
        try (var output = Files.newOutputStream(legacyFile)) {
            legacy.store(output, "legacy test ledger");
        }

        try (IndexManager migrated = manager(data)) {
            assertTrue(migrated.applyReplicatedIndex("tenant_r1", document("doc-1", "durable"), "op-1", 1, 3)
                    .duplicate());
            assertFalse(Files.exists(legacyFile));
        }
        try (IndexManager restarted = manager(data)) {
            assertTrue(restarted
                    .applyReplicatedIndex("tenant_r1", document("doc-1", "durable"), "op-1", 1, 3)
                    .duplicate());
        }
    }

    public static final class CrashMutationWriter {
        private CrashMutationWriter() {}

        public static void main(String[] args) throws Exception {
            Path data = Path.of(args[0]);
            IndexManager.MutationCommitStage crashStage = IndexManager.MutationCommitStage.valueOf(args[1]);
            IndexManager manager = manager(data);
            manager.setMutationFaultInjector((physicalIndex, stage) -> {
                if (stage == crashStage) {
                    Runtime.getRuntime().halt(17);
                }
            });
            manager.applyReplicatedIndex("tenant_r1", document("doc-1", "new"), "op-10", 10, 4);
            throw new IllegalStateException("fault stage was not reached: " + crashStage);
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
