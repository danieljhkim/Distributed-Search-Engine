package com.danieljhkim.dsearch.coordinator.cluster;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ClusterMembershipService {

    private final NodeGroup indexGroup;
    private final NodeGroup queryGroup;
    private final NodeGroup coordinatorGroup;

    public ClusterMembershipService(AppConfig appConfig) {
        Objects.requireNonNull(appConfig, "appConfig must not be null");
        Objects.requireNonNull(appConfig.getIndexNodes(), "appConfig.indexNodes must not be null");
        Objects.requireNonNull(appConfig.getQueryNodes(), "appConfig.queryNodes must not be null");

        this.indexGroup = NodeGroup.fromConfig(NodeRole.NODE_ROLE_INDEX, appConfig.getIndexNodes());
        this.queryGroup = NodeGroup.fromConfig(NodeRole.NODE_ROLE_QUERY, appConfig.getQueryNodes());
        this.coordinatorGroup = NodeGroup.fromConfig(NodeRole.NODE_ROLE_COORDINATOR, appConfig.getCoordinatorNodes());
    }

    /**
     * Register or update a node in the appropriate NodeGroup.
     */
    public void registerNode(NodeInfo nodeInfo, NodeRole role) {
        Objects.requireNonNull(nodeInfo, "nodeInfo must not be null");
        NodeGroup group = resolveGroup(role);
        group.addOrUpdateNode(nodeInfo);
    }

    /**
     * Remove a node from the appropriate NodeGroup.
     */
    public void removeNode(String nodeId, NodeRole role) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        NodeGroup group = resolveGroup(role);
        group.removeNode(nodeId);
    }

    /**
     * Returns a cloned NodeGroupConfig built from the current in-memory NodeGroup.
     * This avoids exposing internal mutable state and always reflects the latest membership.
     */
    public AppConfig.NodeGroupConfig getNodeGroupConfig(NodeRole role) {
        NodeGroup group = resolveGroup(role);
        return group.toNodeGroupConfigClone();
    }

    private NodeGroup resolveGroup(NodeRole role) {
        Objects.requireNonNull(role, "role must not be null");
        return switch (role) {
            case NODE_ROLE_INDEX -> indexGroup;
            case NODE_ROLE_QUERY -> queryGroup;
            case NODE_ROLE_COORDINATOR -> coordinatorGroup;
            default -> throw new IllegalArgumentException("Unsupported NodeRole: " + role);
        };
    }

    public void updateNodeHealth(String nodeId, NodeRole role, boolean isHealthy) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        NodeGroup group = resolveGroup(role);
        NodeInfo existingNode = group.nodes.get(nodeId);
        if (existingNode != null) {
            NodeInfo updatedNode = new NodeInfo(
                    existingNode.nodeId(),
                    existingNode.host(),
                    existingNode.port(),
                    existingNode.healthPort(),
                    existingNode.role(),
                    isHealthy
            );
            group.addOrUpdateNode(updatedNode);
        }
    }

    /**
     * Lightweight in-memory representation of a node.
     */
    public record NodeInfo(String nodeId, String host, int port, int healthPort, String role, boolean isHealthy) {
        public NodeInfo {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            Objects.requireNonNull(host, "host must not be null");
            Objects.requireNonNull(role, "role must not be null");
        }
    }

    /**
     * In-memory group of nodes with shared role, routing strategy, and component label.
     */
    @Getter
    public static class NodeGroup {
        private final NodeRole role;
        private final String componentLabel;
        private final RoutingStrategy routingStrategy;
        private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

        private NodeGroup(NodeRole role, String componentLabel, RoutingStrategy routingStrategy) {
            this.role = Objects.requireNonNull(role, "role must not be null");
            this.componentLabel = componentLabel;
            this.routingStrategy = routingStrategy;
        }

        /**
         * Construct a NodeGroup from an AppConfig.NodeGroupConfig, cloning its nodes.
         */
        public static NodeGroup fromConfig(NodeRole role, AppConfig.NodeGroupConfig config) {
            Objects.requireNonNull(config, "config must not be null");
            NodeGroup group = new NodeGroup(
                    role,
                    config.getComponentLabel(),
                    config.getRoutingStrategy()
            );
            if (config.getNodes() != null) {
                for (AppConfig.NodeConfig nodeConfig : config.getNodes()) {
                    NodeInfo nodeInfo = new NodeInfo(
                            nodeConfig.getId(),
                            nodeConfig.getHost(),
                            nodeConfig.getPort(),
                            nodeConfig.getHealthPort(),
                            role.name(),
                            true
                    );
                    group.nodes.put(nodeInfo.nodeId(), nodeInfo);
                }
            }
            return group;
        }

        public void addOrUpdateNode(NodeInfo nodeInfo) {
            nodes.put(nodeInfo.nodeId(), nodeInfo);
        }

        public void removeNode(String nodeId) {
            nodes.remove(nodeId);
        }

        public List<NodeInfo> getAllNodes() {
            return List.copyOf(nodes.values());
        }

        /**
         * Build a deep-cloned AppConfig.NodeGroupConfig representation of this group.
         */
        public AppConfig.NodeGroupConfig toNodeGroupConfigClone() {
            AppConfig.NodeGroupConfig clone = new AppConfig.NodeGroupConfig();
            clone.setComponentLabel(componentLabel);
            clone.setRoutingStrategy(routingStrategy);

            List<AppConfig.NodeConfig> nodeConfigs = getAllNodes().stream()
                    .map(nodeInfo -> {
                        AppConfig.NodeConfig cfg = new AppConfig.NodeConfig();
                        cfg.setId(nodeInfo.nodeId());
                        cfg.setHost(nodeInfo.host());
                        cfg.setPort(nodeInfo.port());
                        cfg.setHealthPort(nodeInfo.healthPort());
                        return cfg;
                    })
                    .toList();

            clone.setNodes(nodeConfigs);
            return clone;
        }
    }
}