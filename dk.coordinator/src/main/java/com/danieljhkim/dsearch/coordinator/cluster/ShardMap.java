package com.danieljhkim.dsearch.coordinator.cluster;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

@Getter
public class ShardMap {

	private final int numShards;
	private final Map<String, String> shardToNodeId = new HashMap<>();

	public ShardMap(int numShards) {
		this.numShards = numShards;
	}

	public ShardMap() {
		this.numShards = 0;
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