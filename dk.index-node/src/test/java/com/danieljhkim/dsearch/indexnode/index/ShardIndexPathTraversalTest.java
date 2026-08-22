package com.danieljhkim.dsearch.indexnode.index;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for a path-traversal vulnerability where an attacker-controlled partitionId
 * escaped the configured index base directory via {@code ../} segments, allowing Lucene to write
 * index segments and a write.lock file outside baseDir.
 */
class ShardIndexPathTraversalTest {

    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};

    @TempDir
    Path tempDir;

    @Test
    void rejectsPartitionIdThatEscapesBaseDirAndCreatesNothingOutsideIt() throws IOException {
        Path baseDir = tempDir.resolve("data").resolve("index");
        Files.createDirectories(baseDir);

        // Step 1: an ordinary-looking partitionId. This legitimately creates shard-evil under
        // baseDir, and its "shard-" prefix is what a naive ".." count would need to consume.
        try (ShardIndex first = new ShardIndex("evil", baseDir, null, FAKE_EMBEDDER)) {
            assertTrue(Files.isDirectory(baseDir.resolve("shard-evil")));
        }

        // Step 2: reusing the "evil" segment plus enough ".." components escapes baseDir entirely
        // (mirrors the confirmed exploit: "evil" then "evil/../../../ESCAPED").
        String maliciousPartitionId = "evil/../../../ESCAPED";
        assertThrows(
                IllegalArgumentException.class,
                () -> new ShardIndex(maliciousPartitionId, baseDir, null, FAKE_EMBEDDER));

        Path escapedDir = tempDir.resolve("ESCAPED");
        assertFalse(Files.exists(escapedDir), "no directory or file should be created outside baseDir");
    }

    @Test
    void validPartitionIdsStillResolveUnderShardPrefixedDirectory() throws IOException {
        Path baseDir = tempDir.resolve("data").resolve("index");
        Files.createDirectories(baseDir);

        try (ShardIndex index = new ShardIndex("partition-123", baseDir, null, FAKE_EMBEDDER)) {
            assertTrue(Files.isDirectory(baseDir.resolve("shard-partition-123")));
        }
    }
}
