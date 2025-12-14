package com.danieljhkim.dsearch.coordinator.routing;

import com.danieljhkim.dsearch.coordinator.cluster.ShardMap;

public class Router {
	private final ShardMap shardMap;

	public Router(ShardMap shardMap) {
		this.shardMap = shardMap;
	}

	public String routeRequest(String shardId) {
		return shardMap.getNodeForShard(shardId);
	}
}