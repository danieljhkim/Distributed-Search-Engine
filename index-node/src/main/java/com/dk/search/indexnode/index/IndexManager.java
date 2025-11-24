package com.dk.search.indexnode.index;

import com.dk.search.common.model.SearchDocument;
import com.dk.search.common.model.SearchResult;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class IndexManager implements Closeable {

    private static final String SHARD_PREFIX = "shard-";

    private final Path baseDir;
    private final Map<String, ShardIndex> shardIndexes = new ConcurrentHashMap<>();

    public IndexManager(Path baseDir) {
        this.baseDir = baseDir;
        loadExistingShards();
    }

    /**
     * On startup, scan baseDir for shard directories (shard-0, shard-1, ...)
     * and create ShardIndex instances for each one.
     */
    private void loadExistingShards() {
        try {
            Files.createDirectories(baseDir);
            try (Stream<Path> paths = Files.list(baseDir)) {
                paths
                        .filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.startsWith(SHARD_PREFIX))
                        .forEach(dirName -> {
                            String shardId = dirName.substring(SHARD_PREFIX.length());
                            // This will also auto-create empty indices if needed (per your ShardIndex logic)
                            ShardIndex shardIndex = new ShardIndex(shardId, baseDir);
                            shardIndexes.put(shardId, shardIndex);
                        });
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load existing shard indexes from " + baseDir, e);
        }
    }

    private ShardIndex getOrCreateShard(String shardId) {
        return shardIndexes.computeIfAbsent(shardId, id -> new ShardIndex(id, baseDir));
    }

    public void indexDocument(String shardId, SearchDocument doc) throws IOException {
        ShardIndex shardIndex = getOrCreateShard(shardId);
        shardIndex.index(doc);
        shardIndex.commit();
    }

    public void deleteDocument(String shardId, String docId) throws IOException {
        ShardIndex shardIndex = shardIndexes.get(shardId);
        if (shardIndex != null) {
            shardIndex.delete(docId);
            shardIndex.commit();
        }
    }

    public SearchResult searchDocument(String shardId, String query, int limit, int from) throws IOException {
        ShardIndex shardIndex = shardIndexes.get(shardId);
        if (shardIndex == null) {
            throw new IllegalArgumentException("Unknown shardId: " + shardId);
        }
        return shardIndex.search(query, limit, from);
    }

    public void commitAll() throws IOException {
        for (ShardIndex shardIndex : shardIndexes.values()) {
            shardIndex.commit();
        }
    }

    @Override
    public void close() throws IOException {
        for (ShardIndex shardIndex : shardIndexes.values()) {
            shardIndex.commit();
            shardIndex.close();
        }
    }
}