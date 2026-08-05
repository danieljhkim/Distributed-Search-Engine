package com.danieljhkim.dsearch.coordinator.cluster;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.util.Objects;
import lombok.Getter;

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
    public void registerNode(NodeGroup.NodeInfo nodeInfo, NodeRole role) {
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
     * This avoids
     * exposing internal mutable state and always reflects the latest membership.
     */
    public AppConfig.NodeGroupConfig getNodeGroupConfig(NodeRole role) {
        NodeGroup group = resolveGroup(role);
        return group.toNodeGroupConfigClone();
    }

    public NodeGroup resolveGroup(NodeRole role) {
        Objects.requireNonNull(role, "role must not be null");
        return switch (role) {
            case NODE_ROLE_INDEX -> indexGroup;
            case NODE_ROLE_QUERY -> queryGroup;
            case NODE_ROLE_COORDINATOR -> coordinatorGroup;
            default -> null;
        };
    }

    public void updateNodeHealth(String nodeId, NodeRole role, boolean isHealthy) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        NodeGroup group = resolveGroup(role);
        NodeGroup.NodeInfo existingNode = group.getNode(nodeId);
        if (existingNode != null) {
            NodeGroup.NodeInfo updatedNode = new NodeGroup.NodeInfo(
                    existingNode.getNodeId(),
                    existingNode.getHost(),
                    existingNode.getPort(),
                    existingNode.getHealthPort(),
                    existingNode.getRole(),
                    isHealthy);
            group.addOrUpdateNode(updatedNode);
        }
    }
}
