package com.dk.search.indexnode.index;

import com.dk.search.common.model.SearchDocument;
import com.dk.search.common.model.SearchResult;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IndexManager implements Closeable {

    private final Path baseDir;
    private final Map<String, ShardIndex> shardIndexes = new ConcurrentHashMap<>();

    public IndexManager(Path baseDir) {
        this.baseDir = baseDir;
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