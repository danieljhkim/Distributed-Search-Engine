package com.dk.search.coordinator.cluster;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;


@Getter
public class ShardMap {

    private final int numShards;
    private final Map<String, String> shardToNodeId = new HashMap<>();

    public ShardMap(int numShards) {
        this.numShards = numShards;
    }

    public void assignShard(String shardId, String nodeId) {
        shardToNodeId.put(shardId, nodeId);
    }

    public void clearAssignments() {
        shardToNodeId.clear();
    }

    public String getNodeForShard(String shardId) {
        return shardToNodeId.get(shardId);
    }
}