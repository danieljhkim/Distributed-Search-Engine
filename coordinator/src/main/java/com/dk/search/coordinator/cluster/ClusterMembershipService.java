
package com.dk.search.coordinator.cluster;

import lombok.Getter;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Getter
public class ClusterMembershipService {
    private final Map<String, ClusterNodeInfo> nodes = new ConcurrentHashMap<>();

    public void registerNode(ClusterNodeInfo nodeInfo) {
        nodes.put(nodeInfo.nodeId(), nodeInfo);
    }

    public record ClusterNodeInfo(String nodeId, String host, int port, String role) {}
}