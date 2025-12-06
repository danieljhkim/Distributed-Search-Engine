package com.danieljhkim.dsearch.common.exception;

public class ShardNotFoundException extends ServiceException {

    private final String shardId;

    public ShardNotFoundException(String shardId) {
        super("Shard not found: " + shardId);
        this.shardId = shardId;
    }

    public String getShardId() {
        return shardId;
    }
}
