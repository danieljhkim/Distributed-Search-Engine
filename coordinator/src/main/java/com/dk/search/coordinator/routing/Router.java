
package com.dk.search.coordinator.routing;

import com.dk.search.coordinator.cluster.ShardMap;

public class Router {
    private final ShardMap shardMap;

    public Router(ShardMap shardMap) {
        this.shardMap = shardMap;
    }

    public String routeRequest(int shardId) {
        return shardMap.getNodeForShard(shardId);
    }
}