package com.danieljhkim.dsearch.coordinator.cluster;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
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

/**
 * Authoritative, versioned coordinator membership.
 *
 * <p>Every topology-changing mutation is serialized, increments a monotonic version, and is
 * atomically persisted before it is returned to an RPC caller. Heartbeats renew leases without
 * changing the topology version. The persisted epoch and version let clients reject a coordinator
 * that has lost durable state.
 */
public class ClusterMembershipService {

    public static final int CONTRACT_VERSION = 1;

    private static final String STATE_FORMAT_VERSION_KEY = "state.format.version";
    private static final String BACKUP_SUFFIX = ".bak";

    private final NodeGroup indexGroup;
    private final NodeGroup queryGroup;
    private final NodeGroup coordinatorGroup;
    private final Duration leaseDuration;
    private final Path stateFile;
    private final Clock clock;
    private final Map<MemberKey, Long> lastSeenMillis = new HashMap<>();

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
        } else {
            this.topologyEpoch = UUID.randomUUID().toString();
            this.topologyVersion = 1;
            long now = clock.millis();
            seedLeases(indexGroup, NodeRole.NODE_ROLE_INDEX, now);
            seedLeases(queryGroup, NodeRole.NODE_ROLE_QUERY, now);
            seedLeases(coordinatorGroup, NodeRole.NODE_ROLE_COORDINATOR, now);
            persistState();
        }
    }

    /** Register or replace a node and renew its lease. */
    public synchronized void registerNode(NodeGroup.NodeInfo nodeInfo, NodeRole role) {
        Objects.requireNonNull(nodeInfo, "nodeInfo must not be null");
        NodeGroup group = requireGroup(role);
        NodeGroup.NodeInfo existing = group.getNode(nodeInfo.getNodeId());
        NodeGroup.NodeInfo healthyNode = copyWithHealth(nodeInfo, true);
        boolean topologyChanged = existing == null || !sameMember(existing, healthyNode) || !existing.isHealthy();
        group.addOrUpdateNode(healthyNode);
        lastSeenMillis.put(new MemberKey(role, nodeInfo.getNodeId()), clock.millis());
        persistMutation(topologyChanged);
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
        String serializedVersion = required(properties, STATE_FORMAT_VERSION_KEY);
        int stateFormatVersion;
        try {
            stateFormatVersion = Integer.parseInt(serializedVersion);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Coordinator state has an invalid format version: " + serializedVersion, e);
        }
        if (stateFormatVersion != CONTRACT_VERSION) {
            throw new IllegalStateException("Coordinator state format version " + stateFormatVersion
                    + " is incompatible with supported version " + CONTRACT_VERSION);
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
