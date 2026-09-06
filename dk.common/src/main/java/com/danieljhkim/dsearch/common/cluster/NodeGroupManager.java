package com.danieljhkim.dsearch.common.cluster;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoRequest;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import lombok.Setter;

/** Resolves node groups from either explicit static mode or authoritative coordinator topology. */
public class NodeGroupManager {
    private static final Logger LOGGER = Logger.getLogger(NodeGroupManager.class.getName());
    private static final int MIN_SUPPORTED_CONTRACT_VERSION = 1;
    private static final int MAX_SUPPORTED_CONTRACT_VERSION = 2;

    private final AppConfig defaultConfig;
    private final Clock clock;
    private final Map<NodeRole, AcceptedTopology> acceptedTopologies = new ConcurrentHashMap<>();
    private String acceptedEpoch;
    private long acceptedVersion;

    @Setter
    private NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> coordinatorManager;

    private final NodeGroup indexGroup;
    private final NodeGroup queryGroup;
    private final NodeGroup coordinatorGroup;

    public NodeGroupManager() throws RuntimeException, IOException {
        this(ConfigLoader.load());
    }

    public NodeGroupManager(AppConfig defaultConfig) {
        this(defaultConfig, Clock.systemUTC());
    }

    NodeGroupManager(AppConfig defaultConfig, Clock clock) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.coordinatorGroup = loadStaticNodeGroup(NodeRole.NODE_ROLE_COORDINATOR);
        this.indexGroup = loadStaticNodeGroup(NodeRole.NODE_ROLE_INDEX);
        this.queryGroup = loadStaticNodeGroup(NodeRole.NODE_ROLE_QUERY);
    }

    /**
     * Node group exactly as declared in the configuration file, rebuilt on every call. This is
     * intentionally distinct from a coordinator topology accepted for bounded-staleness use.
     */
    public NodeGroup getConfiguredNodeGroup(NodeRole role) {
        return loadStaticNodeGroup(role);
    }

    public AppConfig getDefaultConfig() {
        return defaultConfig;
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

    /**
     * Resolve topology under an explicit availability policy.
     *
     * <p>Static configuration is used only when discovery is disabled. With discovery enabled, a
     * client fails closed until it has accepted a versioned coordinator response. During a later
     * coordinator outage it may reuse that response only for {@code maxStalenessSeconds}; it never
     * silently revives the operator's static node list.
     */
    public NodeGroup getNodeGroup(NodeRole role) {
        if (!isServiceDiscoveryEnabled()) {
            return getStaticNodeGroupConfig(role);
        }
        AcceptedTopology previous = acceptedTopologies.get(role);
        if (coordinatorManager == null) {
            return acceptedOrFail(role, previous, "coordinator client is not configured", null);
        }

        long minimumVersion = acceptedVersion();
        GetClusterInfoRequest request = GetClusterInfoRequest.newBuilder()
                .setRole(role)
                .setMinTopologyVersion(minimumVersion)
                .build();
        try {
            GetClusterInfoResponse response = coordinatorManager
                    .nextClient()
                    .withDeadlineAfter(requestTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .getClusterInfo(request);
            NodeGroup group = NodeGroup.fromResponse(response, role);
            acceptResponse(role, response);
            acceptedTopologies.put(
                    role,
                    new AcceptedTopology(
                            group, response.getTopologyEpoch(), response.getTopologyVersion(), clock.instant()));
            return group;
        } catch (Exception e) {
            return acceptedOrFail(role, previous, "coordinator request failed", e);
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

    public boolean isServiceDiscoveryEnabled() {
        return defaultConfig.getServiceDiscovery() != null
                && defaultConfig.getServiceDiscovery().isEnabled();
    }

    public AppConfig.ServiceDiscoveryConfig getServiceDiscoveryConfig() {
        return defaultConfig.getServiceDiscovery();
    }

    public boolean hasCoordinatorManager() {
        return coordinatorManager != null;
    }

    private synchronized void acceptResponse(NodeRole role, GetClusterInfoResponse response) {
        if (response.getContractVersion() < MIN_SUPPORTED_CONTRACT_VERSION
                || response.getContractVersion() > MAX_SUPPORTED_CONTRACT_VERSION) {
            throw new IllegalStateException("Unsupported coordinator topology contract version "
                    + response.getContractVersion() + " for role " + role);
        }
        if (response.getTopologyEpoch().isBlank() || response.getTopologyVersion() < 1) {
            throw new IllegalStateException("Coordinator returned an invalid topology epoch or version for " + role);
        }
        if (acceptedEpoch != null && !acceptedEpoch.equals(response.getTopologyEpoch())) {
            throw new IllegalStateException("Coordinator topology epoch changed from " + acceptedEpoch + " to "
                    + response.getTopologyEpoch() + " for " + role);
        }
        if (response.getTopologyVersion() < acceptedVersion) {
            throw new IllegalStateException("Coordinator topology version regressed from " + acceptedVersion + " to "
                    + response.getTopologyVersion() + " for " + role);
        }
        acceptedEpoch = response.getTopologyEpoch();
        acceptedVersion = response.getTopologyVersion();
    }

    private synchronized long acceptedVersion() {
        return acceptedVersion;
    }

    private NodeGroup acceptedOrFail(NodeRole role, AcceptedTopology previous, String reason, Exception cause) {
        if (previous != null) {
            Duration age = Duration.between(previous.acceptedAt(), clock.instant());
            Duration limit = Duration.ofSeconds(Math.max(0, maxStalenessSeconds()));
            if (!age.isNegative() && age.compareTo(limit) <= 0) {
                LOGGER.warning(() -> "Authoritative coordinator unavailable for " + role + "; using topology "
                        + previous.version() + " for bounded-staleness window (age=" + age.toSeconds()
                        + "s, limit=" + limit.toSeconds() + "s). Cause: " + describe(reason, cause));
                return previous.group();
            }
        }
        throw new IllegalStateException(
                "Authoritative coordinator topology unavailable for " + role + ": " + describe(reason, cause), cause);
    }

    private int maxStalenessSeconds() {
        AppConfig.ServiceDiscoveryConfig config = defaultConfig.getServiceDiscovery();
        return config == null ? 0 : config.getMaxStalenessSeconds();
    }

    private long requestTimeoutMillis() {
        AppConfig.RequestLimitsConfig limits = defaultConfig.getRequestLimits();
        return Math.max(1L, limits != null ? limits.getRequestTimeoutMillis() : 3000L);
    }

    private static String describe(String reason, Exception cause) {
        return cause == null ? reason : reason + " (" + cause + ")";
    }

    private record AcceptedTopology(NodeGroup group, String epoch, long version, Instant acceptedAt) {}
}
