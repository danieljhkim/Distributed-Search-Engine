package com.danieljhkim.dsearch.coordinator.cluster;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.shard.ReplicaPlacement;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.ReplicaRepairState;
import com.danieljhkim.dsearch.proto.cluster.ReplicaRepairStatus;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authoritative, versioned coordinator membership.
 *
 * <p>Every topology-changing mutation is serialized, increments a monotonic version, and is
 * atomically persisted before it is returned to an RPC caller. Heartbeats renew leases without
 * changing the topology version. The persisted epoch and version let clients reject a coordinator
 * that has lost durable state.
 */
public class ClusterMembershipService {

    public static final int CONTRACT_VERSION = 2;

    private static final int LEGACY_STATE_FORMAT_VERSION = 1;
    private static final String STATE_FORMAT_VERSION_KEY = "state.format.version";
    private static final String BACKUP_SUFFIX = ".bak";
    private static final Counter LEASE_EVENTS = Counter.build()
            .name("dsearch_topology_lease_events_total")
            .help("Coordinator membership lease events by bounded event")
            .labelNames("event")
            .register();
    private static final Gauge MEMBERSHIP = Gauge.build()
            .name("dsearch_topology_members")
            .help("Coordinator topology members by role and health state")
            .labelNames("role", "state")
            .register();
    private static final Gauge UNDER_REPLICATED = Gauge.build()
            .name("dsearch_replication_under_replicated_shards")
            .help("Logical shards with fewer eligible replicas than configured")
            .register();
    private static final Gauge FAILOVER = Gauge.build()
            .name("dsearch_replication_failover_shards")
            .help("Logical shards whose primary is currently ineligible")
            .register();

    private final NodeGroup indexGroup;
    private final NodeGroup queryGroup;
    private final NodeGroup coordinatorGroup;
    private final Duration leaseDuration;
    private final Path stateFile;
    private final Clock clock;
    private final Map<MemberKey, Long> lastSeenMillis = new HashMap<>();
    private final Map<String, NodeGroup.NodeInfo> placementNodes;
    private final int replicationFactor;
    private final ReplicaPlacement.DurabilityPolicy durabilityPolicy;
    private final ReplicaPlacement.ReadConsistency readConsistency;
    private final Map<String, ReplicaRepairState> replicaNodeStates = new HashMap<>();
    private final Map<String, ReplicaRepairStatus> repairStatuses = new HashMap<>();
    private boolean repairsPaused;

    private String topologyEpoch;
    private long topologyVersion;

    public ClusterMembershipService(AppConfig appConfig) {
        this(appConfig, configuredStateFile(appConfig), Clock.systemUTC());
    }

    public ClusterMembershipService(AppConfig appConfig, Path stateFile, Clock clock) {
        Objects.requireNonNull(appConfig, "appConfig must not be null");
        Objects.requireNonNull(appConfig.getIndexNodes(), "appConfig.indexNodes must not be null");
        Objects.requireNonNull(appConfig.getQueryNodes(), "appConfig.queryNodes must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stateFile = stateFile;
        AppConfig.NodeGroupConfig configuredIndexNodes = appConfig.getIndexNodes();
        this.placementNodes = configuredIndexNodes.getNodes().stream()
                .map(node -> new NodeGroup.NodeInfo(
                        node.getId(),
                        node.getHost(),
                        node.getPort(),
                        node.getHealthPort(),
                        NodeRole.NODE_ROLE_INDEX.name(),
                        true))
                .collect(Collectors.toMap(NodeGroup.NodeInfo::getNodeId, node -> node));
        this.replicationFactor = Math.max(1, configuredIndexNodes.getReplicationFactor());
        if (!placementNodes.isEmpty() && replicationFactor > placementNodes.size()) {
            throw new IllegalArgumentException("indexNodes.replicationFactor " + replicationFactor
                    + " exceeds the configured eligible node count " + placementNodes.size());
        }
        this.durabilityPolicy = ReplicaPlacement.DurabilityPolicy.parse(configuredIndexNodes.getDurabilityPolicy());
        this.readConsistency = ReplicaPlacement.ReadConsistency.parse(configuredIndexNodes.getReadConsistency());
        placementNodes
                .keySet()
                .forEach(nodeId -> replicaNodeStates.put(
                        nodeId,
                        replicationFactor <= 1
                                ? ReplicaRepairState.REPLICA_REPAIR_STATE_READY
                                : ReplicaRepairState.REPLICA_REPAIR_STATE_CHECKING));
        if (readConsistency == ReplicaPlacement.ReadConsistency.ACKNOWLEDGED
                && durabilityPolicy != ReplicaPlacement.DurabilityPolicy.ALL) {
            throw new IllegalArgumentException(
                    "readConsistency=acknowledged requires durabilityPolicy=all so every failover target has every acknowledged write");
        }
        AppConfig.ServiceDiscoveryConfig discovery = appConfig.getServiceDiscovery();
        int expirySeconds = discovery == null ? 30 : Math.max(1, discovery.getNodeExpirySeconds());
        this.leaseDuration = Duration.ofSeconds(expirySeconds);

        boolean recover = stateFile != null && Files.exists(stateFile);
        this.indexGroup = NodeGroup.fromConfig(
                NodeRole.NODE_ROLE_INDEX, recover ? emptyConfig(appConfig.getIndexNodes()) : appConfig.getIndexNodes());
        this.queryGroup = NodeGroup.fromConfig(
                NodeRole.NODE_ROLE_QUERY, recover ? emptyConfig(appConfig.getQueryNodes()) : appConfig.getQueryNodes());
        this.coordinatorGroup = NodeGroup.fromConfig(
                NodeRole.NODE_ROLE_COORDINATOR,
                recover ? emptyConfig(appConfig.getCoordinatorNodes()) : appConfig.getCoordinatorNodes());

        if (recover) {
            loadState();
            if (placementNodes.isEmpty()) {
                indexGroup.getAllNodes().forEach(node -> placementNodes.put(node.getNodeId(), node));
            }
        } else {
            this.topologyEpoch = UUID.randomUUID().toString();
            this.topologyVersion = 1;
            long now = clock.millis();
            seedLeases(indexGroup, NodeRole.NODE_ROLE_INDEX, now);
            seedLeases(queryGroup, NodeRole.NODE_ROLE_QUERY, now);
            seedLeases(coordinatorGroup, NodeRole.NODE_ROLE_COORDINATOR, now);
            persistState();
        }
        updateMembershipMetrics();
    }

    /** Register or replace a node and renew its lease. */
    public synchronized void registerNode(NodeGroup.NodeInfo nodeInfo, NodeRole role) {
        Objects.requireNonNull(nodeInfo, "nodeInfo must not be null");
        NodeGroup group = requireGroup(role);
        NodeGroup.NodeInfo existing = group.getNode(nodeInfo.getNodeId());
        NodeGroup.NodeInfo healthyNode = copyWithHealth(nodeInfo, true);
        if (role == NodeRole.NODE_ROLE_INDEX) {
            placementNodes.putIfAbsent(nodeInfo.getNodeId(), healthyNode);
            if (existing == null || !existing.isHealthy()) {
                replicaNodeStates.put(
                        nodeInfo.getNodeId(),
                        replicationFactor <= 1
                                ? ReplicaRepairState.REPLICA_REPAIR_STATE_READY
                                : ReplicaRepairState.REPLICA_REPAIR_STATE_CHECKING);
            }
        }
        boolean topologyChanged = existing == null || !sameMember(existing, healthyNode) || !existing.isHealthy();
        group.addOrUpdateNode(healthyNode);
        lastSeenMillis.put(new MemberKey(role, nodeInfo.getNodeId()), clock.millis());
        persistMutation(topologyChanged);
        LEASE_EVENTS.labels("register").inc();
        updateMembershipMetrics();
    }

    /**
     * Registration may cross into a new epoch, where versions are intentionally incomparable. In
     * the same epoch, reject a coordinator whose available topology is older than the registering
     * node has already observed before mutating membership.
     */
    public synchronized void assertRegistrationTopology(String observedEpoch, long observedVersion) {
        if (observedEpoch != null && !observedEpoch.isBlank() && topologyEpoch.equals(observedEpoch)) {
            assertVersionAvailable(observedVersion);
        }
    }

    /** Renew a registered node's lease; heartbeats never create membership. */
    public synchronized long heartbeat(
            String nodeId, NodeRole role, String observedTopologyEpoch, long observedTopologyVersion) {
        assertEpochAvailable(observedTopologyEpoch);
        assertVersionAvailable(observedTopologyVersion);
        NodeGroup group = requireGroup(role);
        NodeGroup.NodeInfo existing = group.getNode(nodeId);
        if (existing == null) {
            throw new NoSuchElementException("Node is not registered: " + role + "/" + nodeId);
        }
        boolean topologyChanged = !existing.isHealthy();
        if (topologyChanged) {
            group.addOrUpdateNode(copyWithHealth(existing, true));
        }
        lastSeenMillis.put(new MemberKey(role, nodeId), clock.millis());
        persistMutation(topologyChanged);
        LEASE_EVENTS.labels("renew").inc();
        updateMembershipMetrics();
        return topologyVersion;
    }

    /** Remove leases that have not been renewed before the configured deadline. */
    public synchronized List<String> expireNodes() {
        long cutoff = clock.millis() - leaseDuration.toMillis();
        List<MemberKey> expired = lastSeenMillis.entrySet().stream()
                .filter(entry -> entry.getKey().role() != NodeRole.NODE_ROLE_COORDINATOR)
                .filter(entry -> entry.getValue() < cutoff)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing((MemberKey key) -> key.role().getNumber())
                        .thenComparing(MemberKey::nodeId))
                .toList();
        if (expired.isEmpty()) {
            return List.of();
        }
        for (MemberKey key : expired) {
            requireGroup(key.role()).removeNode(key.nodeId());
            lastSeenMillis.remove(key);
        }
        persistMutation(true);
        LEASE_EVENTS.labels("expired").inc(expired.size());
        updateMembershipMetrics();
        return expired.stream().map(key -> key.role() + "/" + key.nodeId()).toList();
    }

    /**
     * Record the coordinator's active HTTP probe. Probes affect routability but never renew the
     * node-owned membership lease; only registration and heartbeat do that.
     */
    public synchronized void recordHealthCheck(String nodeId, NodeRole role, boolean healthy) {
        NodeGroup group = requireGroup(role);
        NodeGroup.NodeInfo existing = group.getNode(nodeId);
        if (existing == null) {
            return;
        }
        boolean topologyChanged = existing.isHealthy() != healthy;
        if (topologyChanged) {
            group.addOrUpdateNode(copyWithHealth(existing, healthy));
        }
        persistMutation(topologyChanged);
        LEASE_EVENTS.labels(healthy ? "health_restored" : "health_failed").inc();
        updateMembershipMetrics();
    }

    /** Compatibility entry point for existing callers and tests. */
    public void updateNodeHealth(String nodeId, NodeRole role, boolean isHealthy) {
        recordHealthCheck(nodeId, role, isHealthy);
    }

    public synchronized void removeNode(String nodeId, NodeRole role) {
        NodeGroup group = requireGroup(role);
        if (group.getNode(nodeId) == null) {
            return;
        }
        group.removeNode(nodeId);
        lastSeenMillis.remove(new MemberKey(role, nodeId));
        persistMutation(true);
        LEASE_EVENTS.labels("removed").inc();
        updateMembershipMetrics();
    }

    /**
     * Best-effort graceful removal guarded by the topology observed by the caller. Missing members
     * are treated as already deregistered so shutdown remains idempotent.
     */
    public synchronized long deregisterNode(
            String nodeId, NodeRole role, String observedTopologyEpoch, long observedTopologyVersion) {
        assertEpochAvailable(observedTopologyEpoch);
        assertVersionAvailable(observedTopologyVersion);
        removeNode(nodeId, role);
        return topologyVersion;
    }

    public synchronized AppConfig.NodeGroupConfig getNodeGroupConfig(NodeRole role) {
        return requireGroup(role).toNodeGroupConfigClone();
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

    public NodeGroup getIndexGroup() {
        return indexGroup;
    }

    public NodeGroup getQueryGroup() {
        return queryGroup;
    }

    public NodeGroup getCoordinatorGroup() {
        return coordinatorGroup;
    }

    public synchronized List<NodeGroup.NodeInfo> healthyNodes(NodeRole role) {
        return requireGroup(role).getAllNodes().stream()
                .filter(NodeGroup.NodeInfo::isHealthy)
                .filter(node -> role != NodeRole.NODE_ROLE_INDEX || isReplicaEligible(node.getNodeId()))
                .sorted(Comparator.comparing(NodeGroup.NodeInfo::getNodeId))
                .toList();
    }

    public synchronized void assertVersionAvailable(long minimumVersion) {
        if (minimumVersion > topologyVersion) {
            throw new StaleTopologyException(minimumVersion, topologyVersion);
        }
    }

    public synchronized void assertEpochAvailable(String observedEpoch) {
        if (observedEpoch != null && !observedEpoch.isBlank() && !topologyEpoch.equals(observedEpoch)) {
            throw new StaleTopologyEpochException(observedEpoch, topologyEpoch);
        }
    }

    public synchronized long getTopologyVersion() {
        return topologyVersion;
    }

    public synchronized String getTopologyEpoch() {
        return topologyEpoch;
    }

    public long getLeaseDurationMillis() {
        return leaseDuration.toMillis();
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public ReplicaPlacement.DurabilityPolicy getDurabilityPolicy() {
        return durabilityPolicy;
    }

    public ReplicaPlacement.ReadConsistency getReadConsistency() {
        return readConsistency;
    }

    public synchronized List<ReplicaPlacement.ReplicaSet> replicaSets() {
        List<String> eligibleNodeIds = placementNodes.keySet().stream().sorted().toList();
        return eligibleNodeIds.stream()
                .map(primary -> ReplicaPlacement.forPrimary(
                        "topology", primary, eligibleNodeIds, replicationFactor, topologyVersion, durabilityPolicy))
                .toList();
    }

    public synchronized boolean isReplicaEligible(String nodeId) {
        NodeGroup.NodeInfo node = indexGroup.getNode(nodeId);
        return node != null
                && node.isHealthy()
                && (replicationFactor <= 1
                        || replicaNodeStates.getOrDefault(nodeId, ReplicaRepairState.REPLICA_REPAIR_STATE_CHECKING)
                                == ReplicaRepairState.REPLICA_REPAIR_STATE_READY);
    }

    public synchronized boolean isIndexNodeHealthy(String nodeId) {
        NodeGroup.NodeInfo node = indexGroup.getNode(nodeId);
        return node != null && node.isHealthy();
    }

    public synchronized ReplicaRepairState replicaRepairState(String nodeId) {
        return replicaNodeStates.getOrDefault(nodeId, ReplicaRepairState.REPLICA_REPAIR_STATE_CHECKING);
    }

    public synchronized void updateReplicaNodeState(String nodeId, ReplicaRepairState state) {
        Objects.requireNonNull(state, "state must not be null");
        boolean wasEligible = isReplicaEligible(nodeId);
        replicaNodeStates.put(nodeId, state);
        boolean nowEligible = isReplicaEligible(nodeId);
        persistMutation(wasEligible != nowEligible);
        updateMembershipMetrics();
    }

    public synchronized void recordRepairStatus(ReplicaRepairStatus status) {
        repairStatuses.put(status.getRepairId(), status);
        if (repairStatuses.size() > 256) {
            repairStatuses.values().stream()
                    .min(Comparator.comparing(ReplicaRepairStatus::getUpdatedAtEpochMillis))
                    .ifPresent(oldest -> repairStatuses.remove(oldest.getRepairId()));
        }
    }

    public synchronized List<ReplicaRepairStatus> repairStatuses() {
        return repairStatuses.values().stream()
                .sorted(Comparator.comparing(ReplicaRepairStatus::getUpdatedAtEpochMillis)
                        .reversed())
                .limit(256)
                .toList();
    }

    public synchronized boolean repairsPaused() {
        return repairsPaused;
    }

    public synchronized void setRepairsPaused(boolean paused) {
        repairsPaused = paused;
        persistMutation(false);
    }

    public synchronized boolean retryRepair(String repairId) {
        ReplicaRepairStatus current = repairStatuses.get(repairId);
        if (current == null) {
            return false;
        }
        repairStatuses.put(
                repairId,
                current.toBuilder()
                        .setState(ReplicaRepairState.REPLICA_REPAIR_STATE_CHECKING)
                        .setLastError("")
                        .setUpdatedAtEpochMillis(clock.millis())
                        .build());
        replicaNodeStates.put(current.getTargetNodeId(), ReplicaRepairState.REPLICA_REPAIR_STATE_CHECKING);
        return true;
    }

    public NodeGroup.NodeInfo configuredPlacementNode(String nodeId) {
        return placementNodes.get(nodeId);
    }

    public synchronized int underReplicatedShardCount() {
        return (int) replicaSets().stream()
                .filter(set ->
                        set.nodeIds().stream().filter(this::isReplicaEligible).count() < replicationFactor)
                .count();
    }

    private NodeGroup requireGroup(NodeRole role) {
        NodeGroup group = resolveGroup(role);
        if (group == null) {
            throw new IllegalArgumentException("Unsupported role: " + role);
        }
        return group;
    }

    private void persistMutation(boolean topologyChanged) {
        if (topologyChanged) {
            topologyVersion++;
        }
        persistState();
    }

    private void updateMembershipMetrics() {
        updateMembershipMetrics("index", indexGroup);
        updateMembershipMetrics("query", queryGroup);
        updateMembershipMetrics("coordinator", coordinatorGroup);
        if (!placementNodes.isEmpty()) {
            UNDER_REPLICATED.set(underReplicatedShardCount());
            FAILOVER.set(replicaSets().stream()
                    .filter(set -> !isReplicaEligible(set.primaryNodeId()))
                    .count());
        }
    }

    private static void updateMembershipMetrics(String role, NodeGroup group) {
        List<NodeGroup.NodeInfo> nodes = group.getAllNodes();
        MEMBERSHIP.labels(role, "total").set(nodes.size());
        MEMBERSHIP
                .labels(role, "healthy")
                .set(nodes.stream().filter(NodeGroup.NodeInfo::isHealthy).count());
    }

    private void seedLeases(NodeGroup group, NodeRole role, long now) {
        if (group == null) {
            return;
        }
        for (NodeGroup.NodeInfo node : group.getAllNodes()) {
            lastSeenMillis.put(new MemberKey(role, node.getNodeId()), now);
        }
    }

    private void loadState() {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            properties.load(input);
            validateStateFormat(properties);
            this.topologyEpoch = required(properties, "topology.epoch");
            this.topologyVersion = Long.parseLong(required(properties, "topology.version"));
            this.repairsPaused = Boolean.parseBoolean(properties.getProperty("repairs.paused", "false"));
            int memberCount = Integer.parseInt(required(properties, "member.count"));
            for (int i = 0; i < memberCount; i++) {
                String prefix = "member." + i + ".";
                NodeRole role = NodeRole.valueOf(required(properties, prefix + "role"));
                String nodeId = required(properties, prefix + "id");
                NodeGroup.NodeInfo node = new NodeGroup.NodeInfo(
                        nodeId,
                        required(properties, prefix + "host"),
                        Integer.parseInt(required(properties, prefix + "port")),
                        Integer.parseInt(required(properties, prefix + "healthPort")),
                        role.name(),
                        Boolean.parseBoolean(required(properties, prefix + "healthy")));
                requireGroup(role).addOrUpdateNode(node);
                lastSeenMillis.put(
                        new MemberKey(role, nodeId), Long.parseLong(required(properties, prefix + "lastSeenMillis")));
            }
            if (topologyVersion < 1 || topologyEpoch.isBlank()) {
                throw new IllegalStateException("Coordinator state has an invalid epoch or topology version");
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to recover authoritative coordinator state from " + stateFile, e);
        }
    }

    private void persistState() {
        if (stateFile == null) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty(STATE_FORMAT_VERSION_KEY, Integer.toString(CONTRACT_VERSION));
        properties.setProperty("topology.epoch", topologyEpoch);
        properties.setProperty("topology.version", Long.toString(topologyVersion));
        properties.setProperty("repairs.paused", Boolean.toString(repairsPaused));
        List<MemberKey> members = new ArrayList<>(lastSeenMillis.keySet());
        members.sort(
                Comparator.comparing((MemberKey key) -> key.role().getNumber()).thenComparing(MemberKey::nodeId));
        properties.setProperty("member.count", Integer.toString(members.size()));
        for (int i = 0; i < members.size(); i++) {
            MemberKey key = members.get(i);
            NodeGroup.NodeInfo node = requireGroup(key.role()).getNode(key.nodeId());
            String prefix = "member." + i + ".";
            properties.setProperty(prefix + "role", key.role().name());
            properties.setProperty(prefix + "id", node.getNodeId());
            properties.setProperty(prefix + "host", node.getHost());
            properties.setProperty(prefix + "port", Integer.toString(node.getPort()));
            properties.setProperty(prefix + "healthPort", Integer.toString(node.getHealthPort()));
            properties.setProperty(prefix + "healthy", Boolean.toString(node.isHealthy()));
            properties.setProperty(prefix + "lastSeenMillis", Long.toString(lastSeenMillis.get(key)));
        }

        try {
            ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            properties.store(serialized, "dsearch coordinator topology; write via ClusterMembershipService only");
            byte[] contents = serialized.toByteArray();
            writeAtomically(stateFile, contents);
            writeAtomically(stateFile.resolveSibling(stateFile.getFileName() + BACKUP_SUFFIX), contents);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist authoritative coordinator state to " + stateFile, e);
        }
    }

    private static void writeAtomically(Path target, byte[] contents) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IllegalStateException("Coordinator state target must have a parent directory: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve(absoluteTarget.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel =
                    FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(contents);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary, absoluteTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IllegalStateException(
                        "Coordinator state filesystem does not support atomic replacement for " + absoluteTarget, e);
            }
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static Path configuredStateFile(AppConfig appConfig) {
        String environmentPath = System.getenv("COORDINATOR_STATE_FILE");
        if (environmentPath != null && !environmentPath.isBlank()) {
            return Path.of(environmentPath);
        }
        AppConfig.ServiceDiscoveryConfig discovery = appConfig.getServiceDiscovery();
        if (discovery == null
                || discovery.getCoordinatorStateFile() == null
                || discovery.getCoordinatorStateFile().isBlank()) {
            return null;
        }
        return Path.of(discovery.getCoordinatorStateFile());
    }

    private static AppConfig.NodeGroupConfig emptyConfig(AppConfig.NodeGroupConfig source) {
        AppConfig.NodeGroupConfig empty = new AppConfig.NodeGroupConfig();
        if (source != null) {
            empty.setComponentLabel(source.getComponentLabel());
            empty.setRoutingStrategy(source.getRoutingStrategy());
            empty.setReplicationFactor(source.getReplicationFactor());
            empty.setDurabilityPolicy(source.getDurabilityPolicy());
            empty.setReadConsistency(source.getReadConsistency());
        }
        empty.setNodes(List.of());
        return empty;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Coordinator state is missing " + key);
        }
        return value;
    }

    private static void validateStateFormat(Properties properties) {
        String serializedVersion = properties.getProperty(STATE_FORMAT_VERSION_KEY);
        int stateFormatVersion = LEGACY_STATE_FORMAT_VERSION;
        if (serializedVersion != null) {
            try {
                stateFormatVersion = Integer.parseInt(serializedVersion);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "Coordinator state has an invalid format version: " + serializedVersion, e);
            }
        }
        if (stateFormatVersion != LEGACY_STATE_FORMAT_VERSION && stateFormatVersion != CONTRACT_VERSION) {
            throw new IllegalStateException("Coordinator state format version " + stateFormatVersion
                    + " is incompatible with supported versions " + LEGACY_STATE_FORMAT_VERSION + " and "
                    + CONTRACT_VERSION);
        }
    }

    private static NodeGroup.NodeInfo copyWithHealth(NodeGroup.NodeInfo node, boolean healthy) {
        return new NodeGroup.NodeInfo(
                node.getNodeId(), node.getHost(), node.getPort(), node.getHealthPort(), node.getRole(), healthy);
    }

    private static boolean sameMember(NodeGroup.NodeInfo left, NodeGroup.NodeInfo right) {
        return left.getNodeId().equals(right.getNodeId())
                && left.getHost().equals(right.getHost())
                && left.getPort() == right.getPort()
                && left.getHealthPort() == right.getHealthPort()
                && left.getRole().equals(right.getRole());
    }

    private record MemberKey(NodeRole role, String nodeId) {}

    public static final class StaleTopologyException extends IllegalStateException {
        private final long requestedVersion;
        private final long availableVersion;

        public StaleTopologyException(long requestedVersion, long availableVersion) {
            super("Requested topology version " + requestedVersion + " but coordinator has " + availableVersion);
            this.requestedVersion = requestedVersion;
            this.availableVersion = availableVersion;
        }

        public long getRequestedVersion() {
            return requestedVersion;
        }

        public long getAvailableVersion() {
            return availableVersion;
        }
    }

    public static final class StaleTopologyEpochException extends IllegalStateException {
        private final String observedEpoch;
        private final String availableEpoch;

        public StaleTopologyEpochException(String observedEpoch, String availableEpoch) {
            super("Observed topology epoch " + observedEpoch + " but coordinator has " + availableEpoch);
            this.observedEpoch = observedEpoch;
            this.availableEpoch = availableEpoch;
        }

        public String getObservedEpoch() {
            return observedEpoch;
        }

        public String getAvailableEpoch() {
            return availableEpoch;
        }
    }
}
