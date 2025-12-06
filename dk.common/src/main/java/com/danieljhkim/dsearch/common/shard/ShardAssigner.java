package com.danieljhkim.dsearch.common.shard;

public class ShardAssigner {
    public ShardId assign(String documentId) {
        // Logic to assign a shard based on document ID
        return new ShardId(documentId);
    }
}