package com.dk.search.indexnode.index;

import com.dk.search.common.model.SearchDocument;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IndexManager implements Closeable {

    private final Path baseDir;
    private final Map<Integer, ShardIndex> shardIndexes = new ConcurrentHashMap<>();

    public IndexManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    private ShardIndex getOrCreateShard(int shardId) {
        return shardIndexes.computeIfAbsent(shardId, id -> new ShardIndex(id, baseDir));
    }

    public void indexDocument(int shardId, SearchDocument doc) throws IOException {
        ShardIndex shardIndex = getOrCreateShard(shardId);
        shardIndex.index(doc);
        shardIndex.commit();
    }

    public void deleteDocument(int shardId, String docId) throws IOException {
        ShardIndex shardIndex = shardIndexes.get(shardId);
        if (shardIndex != null) {
            shardIndex.delete(docId);
            shardIndex.commit();
        }
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