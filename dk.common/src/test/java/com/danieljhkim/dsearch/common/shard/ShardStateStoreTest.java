package com.danieljhkim.dsearch.common.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShardStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void loadReturnsEmptySnapshotWhenStateFileIsMissing() throws IOException {
        ShardStateStore store = new ShardStateStore(tempDir.resolve("missing").resolve("shards.json"));

        ShardStateStore.ShardDocSnapshot snapshot = store.load();

        assertNotNull(snapshot);
        assertTrue(snapshot.getNodes().isEmpty());
    }

    @Test
    void loadPropagatesCorruptedStateFile() throws IOException {
        Path stateFile = tempDir.resolve("corrupted-shards.json");
        Files.writeString(stateFile, "{not-json");
        ShardStateStore store = new ShardStateStore(stateFile);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void saveAndLoadRoundTripPreservesShardIdsAndNodeAssignments() throws IOException {
        Path stateFile = tempDir.resolve("nested").resolve("shards.json");
        ShardStateStore store = new ShardStateStore(stateFile);
        ShardStateStore.ShardDocSnapshot snapshot = new ShardStateStore.ShardDocSnapshot();
        snapshot.getNodes().add(nodeEntry("node-a", Map.of("shard-0", 12L, "shard-1", 3L)));
        snapshot.getNodes().add(nodeEntry("node-b", Map.of("shard-2", 8L)));

        store.save(snapshot);

        ShardStateStore.ShardDocSnapshot loaded = new ShardStateStore(stateFile).load();
        Map<String, ShardStateStore.NodeEntry> nodesById = loaded.getNodes().stream()
                .collect(Collectors.toMap(ShardStateStore.NodeEntry::getNodeId, Function.identity()));

        assertNotNull(loaded.getGeneratedAt());
        assertEquals(Set.of("node-a", "node-b"), nodesById.keySet());
        assertEquals(
                Map.of("shard-0", 12L, "shard-1", 3L), nodesById.get("node-a").getShards());
        assertEquals(Map.of("shard-2", 8L), nodesById.get("node-b").getShards());
    }

    private static ShardStateStore.NodeEntry nodeEntry(String nodeId, Map<String, Long> shards) {
        ShardStateStore.NodeEntry entry = new ShardStateStore.NodeEntry();
        entry.setNodeId(nodeId);
        entry.setShards(shards);
        return entry;
    }
}
