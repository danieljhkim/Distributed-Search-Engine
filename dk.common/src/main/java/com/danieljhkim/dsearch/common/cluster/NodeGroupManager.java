package com.danieljhkim.dsearch.common.cluster;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoRequest;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.io.IOException;
import java.util.Objects;
import java.util.logging.Logger;
import lombok.Setter;

public class NodeGroupManager {
    private static final Logger LOGGER = Logger.getLogger(NodeGroupManager.class.getName());
    private final AppConfig defaultConfig;

    @Setter
    private NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> coordinatorManager;

    private NodeGroup indexGroup;
    private NodeGroup queryGroup;
    private NodeGroup coordinatorGroup;

    public NodeGroupManager() throws RuntimeException, IOException {
        this(ConfigLoader.load());
    }

    public NodeGroupManager(AppConfig defaultConfig) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
        this.coordinatorGroup = loadStaticNodeGroup(NodeRole.NODE_ROLE_COORDINATOR);
        this.indexGroup = loadStaticNodeGroup(NodeRole.NODE_ROLE_INDEX);
        this.queryGroup = loadStaticNodeGroup(NodeRole.NODE_ROLE_QUERY);
    }

    /**
     * Node group exactly as declared in the configuration file, rebuilt on every
     * call.
     *
     * <p>Unlike {@link #getStaticNodeGroupConfig(NodeRole)}, which is overwritten
     * by the last successful discovery response, this is stable for the lifetime
     * of the config file. Document ownership is derived from it so that the
     * {@code (partitionId, documentId) -> node} mapping survives restarts and
     * health changes.
     */
    public NodeGroup getConfiguredNodeGroup(NodeRole role) {
        return loadStaticNodeGroup(role);
    }

    private NodeGroup loadStaticNodeGroup(NodeRole role) {
        AppConfig.NodeGroupConfig config =
                switch (role) {
                    case NODE_ROLE_COORDINATOR -> defaultConfig.getCoordinatorNodes();
                    case NODE_ROLE_INDEX -> defaultConfig.getIndexNodes();
                    case NODE_ROLE_QUERY -> defaultConfig.getQueryNodes();
                    default -> throw new IllegalArgumentException("Unsupported role: " + role);
                };
        return NodeGroup.fromConfig(role, config);
    }

    public NodeGroup getNodeGroup(NodeRole role) {
        if (role == NodeRole.NODE_ROLE_COORDINATOR) {
            // hack: for coordinator role, use static config.
            return coordinatorGroup;
        }
        // If service discovery is disabled just use the static config from
        // app-config.yaml
        if (!isServiceDiscoveryEnabled()
                || coordinatorManager == null
                || defaultConfig == null
                || defaultConfig.getCoordinatorNodes() == null) {
            return getStaticNodeGroupConfig(role);
        }
        GetClusterInfoRequest request =
                GetClusterInfoRequest.newBuilder().setRole(role).build();
        try {
            GetClusterInfoResponse response = coordinatorManager.nextClient().getClusterInfo(request);
            NodeGroup group = NodeGroup.fromResponse(response, role);
            updateNodeGroup(group, role);
            return group;
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to fetch cluster info from coordinator for role " + role
                    + ". Falling back to static configuration. Cause: " + e.toString());
            return getStaticNodeGroupConfig(role);
        }
    }

    public NodeGroup getStaticNodeGroupConfig(NodeRole role) {
        return switch (role) {
            case NODE_ROLE_INDEX -> indexGroup;
            case NODE_ROLE_QUERY -> queryGroup;
            case NODE_ROLE_COORDINATOR -> coordinatorGroup;
            default -> throw new IllegalArgumentException("Unsupported role: " + role);
        };
    }

    private void updateNodeGroup(NodeGroup group, NodeRole role) {
        if (role == NodeRole.NODE_ROLE_COORDINATOR) {
            this.coordinatorGroup = group;
        } else if (role == NodeRole.NODE_ROLE_INDEX) {
            this.indexGroup = group;
        } else if (role == NodeRole.NODE_ROLE_QUERY) {
            this.queryGroup = group;
        }
    }

    public boolean isServiceDiscoveryEnabled() {
        if (defaultConfig == null || defaultConfig.getServiceDiscovery() == null) {
            return false;
        }
        return defaultConfig.getServiceDiscovery().isEnabled();
    }

    public AppConfig.ServiceDiscoveryConfig getServiceDiscoveryConfig() {
        return defaultConfig.getServiceDiscovery();
    }

    public boolean hasCoordinatorManager() {
        return coordinatorManager != null;
    }
}
