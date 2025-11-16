package com.dk.search.common.exception;

public class ShardNotFoundException extends RuntimeException {

    Integer shardId;

    public ShardNotFoundException() {
        super("Shard not found.");
    }

    public ShardNotFoundException(String message) {
        super(message);
    }

    public ShardNotFoundException(String message, Integer shardId) {
        super(message);
        this.shardId = shardId;
    }

    public ShardNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public ShardNotFoundException(Throwable cause) {
        super(cause);
    }
}
