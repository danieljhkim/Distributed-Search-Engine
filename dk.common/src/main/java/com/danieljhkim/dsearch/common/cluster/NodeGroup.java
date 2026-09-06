package com.danieljhkim.dsearch.common.cluster;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.shard.ReplicaPlacement;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;

/**
 * In-memory group of nodes with shared role, routing strategy, and component
 * label.
 */
@Getter
public class NodeGroup {
    private final NodeRole role;
    private final String componentLabel;
    private final RoutingStrategy routingStrategy;
    private final int replicationFactor;
    private final ReplicaPlacement.DurabilityPolicy durabilityPolicy;
    private final ReplicaPlacement.ReadConsistency readConsistency;
    private final String topologyEpoch;
    private final long topologyVersion;
    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    private NodeGroup(
            NodeRole role,
            String componentLabel,
            RoutingStrategy routingStrategy,
            int replicationFactor,
            ReplicaPlacement.DurabilityPolicy durabilityPolicy,
            ReplicaPlacement.ReadConsistency readConsistency,
            String topologyEpoch,
            long topologyVersion) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.componentLabel = componentLabel;
        this.routingStrategy = routingStrategy;
        this.replicationFactor = replicationFactor;
        this.durabilityPolicy = durabilityPolicy;
        this.readConsistency = readConsistency;
        this.topologyEpoch = topologyEpoch;
        this.topologyVersion = Math.max(1L, topologyVersion);
    }

    /**
     * Construct a NodeGroup from an AppConfig.NodeGroupConfig, cloning its nodes.
     */
    public static NodeGroup fromConfig(NodeRole role, AppConfig.NodeGroupConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        NodeGroup group = new NodeGroup(
                role,
                config.getComponentLabel(),
                config.getRoutingStrategy(),
                Math.max(1, config.getReplicationFactor()),
                ReplicaPlacement.DurabilityPolicy.parse(config.getDurabilityPolicy()),
                ReplicaPlacement.ReadConsistency.parse(config.getReadConsistency()),
                "static",
                1L);
        if (config.getNodes() != null) {
            for (AppConfig.NodeConfig nodeConfig : config.getNodes()) {
                NodeInfo nodeInfo = new NodeInfo(
                        nodeConfig.getId(),
                        nodeConfig.getHost(),
                        nodeConfig.getPort(),
                        nodeConfig.getHealthPort(),
                        role.name(),
                        true);
                group.nodes.put(nodeInfo.getNodeId(), nodeInfo);
            }
        }
        return group;
    }

    public static NodeGroup fromResponse(GetClusterInfoResponse resp, NodeRole role) {
        Objects.requireNonNull(resp, "resp must not be null");
        NodeGroup group = new NodeGroup(
                role,
                resp.getComponentLabel(),
                RoutingStrategy.valueOf(resp.getRoutingStrategy()),
                Math.max(1, resp.getReplicationFactor()),
                ReplicaPlacement.DurabilityPolicy.parse(resp.getDurabilityPolicy()),
                ReplicaPlacement.ReadConsistency.parse(resp.getReadConsistency()),
                resp.getTopologyEpoch(),
                resp.getTopologyVersion());
        for (com.danieljhkim.dsearch.proto.cluster.NodeInfo nodeProto : resp.getNodesList()) {
            NodeInfo nodeInfo = new NodeInfo(
                    nodeProto.getNodeId(),
                    nodeProto.getHost(),
                    nodeProto.getPort(),
                    nodeProto.getHealthPort(),
                    nodeProto.getRole().name(),
                    true);
            group.nodes.put(nodeInfo.getNodeId(), nodeInfo);
        }
        return group;
    }

    public void addOrUpdateNode(NodeInfo nodeInfo) {
        nodes.put(nodeInfo.getNodeId(), nodeInfo);
    }

    public void removeNode(String nodeId) {
        nodes.remove(nodeId);
    }

    public List<NodeInfo> getAllNodes() {
        return List.copyOf(nodes.values());
    }

    public NodeInfo getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * Build a deep-cloned AppConfig.NodeGroupConfig representation of this group.
     */
    public AppConfig.NodeGroupConfig toNodeGroupConfigClone() {
        AppConfig.NodeGroupConfig clone = new AppConfig.NodeGroupConfig();
        clone.setComponentLabel(componentLabel);
        clone.setRoutingStrategy(routingStrategy);
        clone.setReplicationFactor(replicationFactor);
        clone.setDurabilityPolicy(durabilityPolicy.name().toLowerCase());
        clone.setReadConsistency(readConsistency.name().toLowerCase());

        List<AppConfig.NodeConfig> nodeConfigs = getAllNodes().stream()
                .map(nodeInfo -> {
                    AppConfig.NodeConfig cfg = new AppConfig.NodeConfig();
                    cfg.setId(nodeInfo.getNodeId());
                    cfg.setHost(nodeInfo.getHost());
                    cfg.setPort(nodeInfo.getPort());
                    cfg.setHealthPort(nodeInfo.getHealthPort());
                    return cfg;
                })
                .toList();

        clone.setNodes(nodeConfigs);
        return clone;
    }

    @Getter
    public static class NodeInfo {
        private final String nodeId;
        private final String host;
        private final int port;
        private final int healthPort;
        private final String role;
        private final boolean isHealthy;
        private final List<String> partitions;

        public NodeInfo(String nodeId, String host, int port, int healthPort, String role, boolean isHealthy) {
            this(nodeId, host, port, healthPort, role, isHealthy, null);
        }

        // for index nodes only
        public NodeInfo(
                String nodeId,
                String host,
                int port,
                int healthPort,
                String role,
                boolean isHealthy,
                List<String> partitions) {
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
            this.host = Objects.requireNonNull(host, "host must not be null");
            this.port = port;
            this.healthPort = healthPort;
            this.role = Objects.requireNonNull(role, "role must not be null");
            this.isHealthy = isHealthy;
            this.partitions = partitions != null ? List.copyOf(partitions) : List.of();
        }
    }
}
