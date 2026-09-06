package com.danieljhkim.dsearch.common.cluster;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.DeregisterNodeRequest;
import com.danieljhkim.dsearch.proto.cluster.DeregisterNodeResponse;
import com.danieljhkim.dsearch.proto.cluster.HeartbeatRequest;
import com.danieljhkim.dsearch.proto.cluster.HeartbeatResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeRequest;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns an index or query node's coordinator membership lease.
 *
 * <p>The local service starts first, then this agent registers in the background. Coordinator
 * outages never prevent the node process from starting: registration and heartbeat failures use
 * bounded exponential backoff with full jitter. A missing membership or changed coordinator epoch
 * moves the agent back to registration, allowing the same node identity to rejoin after expiry.
 */
public final class NodeMembershipAgent implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(NodeMembershipAgent.class.getName());
    private static final int MIN_SUPPORTED_CONTRACT_VERSION = 1;
    private static final int MAX_SUPPORTED_CONTRACT_VERSION = 2;

    private final NodeIdentity identity;
    private final Settings settings;
    private final ClusterServiceGrpc.ClusterServiceBlockingStub client;
    private final ManagedChannel channel;
    private final ScheduledExecutorService executor;
    private final LongUnaryOperator jitter;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();

    private volatile boolean registered;
    private volatile String observedEpoch = "";
    private volatile long observedVersion;
    private int consecutiveFailures;
    private long serverLeaseMillis;

    public NodeMembershipAgent(
            NodeIdentity identity,
            Settings settings,
            ClusterServiceGrpc.ClusterServiceBlockingStub client,
            ManagedChannel channel) {
        this(
                identity,
                settings,
                client,
                channel,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "node-membership-" + identity.nodeId());
                    thread.setDaemon(true);
                    return thread;
                }),
                bound -> ThreadLocalRandom.current().nextLong(bound + 1));
    }

    NodeMembershipAgent(
            NodeIdentity identity,
            Settings settings,
            ClusterServiceGrpc.ClusterServiceBlockingStub client,
            ManagedChannel channel,
            ScheduledExecutorService executor,
            LongUnaryOperator jitter) {
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.jitter = Objects.requireNonNull(jitter, "jitter must not be null");
        validateIdentity(identity);
        validateSettings(settings);
    }

    /** Begin background registration. Safe to call more than once. */
    public void start() {
        if (started.compareAndSet(false, true) && !closing.get()) {
            schedule(0);
        }
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        if (registered) {
            deregisterBestEffort();
        }
        executor.shutdownNow();
        channel.shutdownNow();
    }

    public boolean isRegistered() {
        return registered;
    }

    String observedEpoch() {
        return observedEpoch;
    }

    long observedVersion() {
        return observedVersion;
    }

    private void runCycle() {
        if (closing.get()) {
            return;
        }
        try {
            if (registered) {
                heartbeat();
            } else {
                register();
            }
            consecutiveFailures = 0;
            schedule(successDelayMillis());
        } catch (RuntimeException error) {
            if (closing.get()) {
                return;
            }
            handleFailure(error);
            consecutiveFailures++;
            schedule(retryDelayMillis(consecutiveFailures));
        }
    }

    private void register() {
        RegisterNodeResponse response = deadlineClient()
                .registerNode(RegisterNodeRequest.newBuilder()
                        .setNodeId(identity.nodeId())
                        .setHost(identity.host())
                        .setPort(identity.port())
                        .setHealthPort(identity.healthPort())
                        .setRole(identity.role())
                        .setObservedTopologyEpoch(observedEpoch)
                        .setObservedTopologyVersion(observedVersion)
                        .build());
        requireCompatibleResponse(response.getSuccess(), response.getContractVersion(), "registration");
        acceptRegistrationTopology(response.getTopologyEpoch(), response.getTopologyVersion());
        serverLeaseMillis = response.getLeaseDurationMillis();
        registered = true;
        LOGGER.info(() -> "Registered coordinator membership for " + identity.role() + "/" + identity.nodeId());
    }

    private void heartbeat() {
        HeartbeatResponse response = deadlineClient()
                .heartbeat(HeartbeatRequest.newBuilder()
                        .setNodeId(identity.nodeId())
                        .setRole(identity.role())
                        .setObservedTopologyEpoch(observedEpoch)
                        .setObservedTopologyVersion(observedVersion)
                        .build());
        requireCompatibleResponse(response.getSuccess(), response.getContractVersion(), "heartbeat");
        acceptHeartbeatTopology(response.getTopologyEpoch(), response.getTopologyVersion());
        serverLeaseMillis = response.getLeaseDurationMillis();
    }

    private void deregisterBestEffort() {
        try {
            DeregisterNodeResponse response = client.withDeadlineAfter(
                            settings.shutdownDeregisterTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .deregisterNode(DeregisterNodeRequest.newBuilder()
                            .setNodeId(identity.nodeId())
                            .setRole(identity.role())
                            .setObservedTopologyEpoch(observedEpoch)
                            .setObservedTopologyVersion(observedVersion)
                            .build());
            requireCompatibleResponse(response.getSuccess(), response.getContractVersion(), "deregistration");
            registered = false;
        } catch (RuntimeException error) {
            LOGGER.log(
                    Level.FINE,
                    "Graceful deregistration failed for " + identity.role() + "/" + identity.nodeId()
                            + "; lease expiry will remove it",
                    error);
        }
    }

    private ClusterServiceGrpc.ClusterServiceBlockingStub deadlineClient() {
        return client.withDeadlineAfter(settings.rpcDeadline().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void acceptRegistrationTopology(String epoch, long version) {
        requireTopology(epoch, version);
        if (!observedEpoch.isEmpty() && observedEpoch.equals(epoch) && version < observedVersion) {
            throw Status.FAILED_PRECONDITION
                    .withDescription("registration returned older topology version " + version + " for epoch " + epoch
                            + "; already observed " + observedVersion)
                    .asRuntimeException();
        }
        observedEpoch = epoch;
        observedVersion = version;
    }

    private void acceptHeartbeatTopology(String epoch, long version) {
        requireTopology(epoch, version);
        if (!observedEpoch.equals(epoch)) {
            throw Status.FAILED_PRECONDITION
                    .withDescription("coordinator topology epoch changed from " + observedEpoch + " to " + epoch)
                    .asRuntimeException();
        }
        if (version < observedVersion) {
            throw Status.FAILED_PRECONDITION
                    .withDescription("heartbeat returned older topology version " + version + "; already observed "
                            + observedVersion)
                    .asRuntimeException();
        }
        observedVersion = version;
    }

    private void handleFailure(RuntimeException error) {
        String operation = registered ? "heartbeat" : "registration";
        Status.Code code = error instanceof StatusRuntimeException statusError
                ? statusError.getStatus().getCode()
                : Status.Code.UNKNOWN;
        if (code == Status.Code.NOT_FOUND || code == Status.Code.FAILED_PRECONDITION) {
            registered = false;
        }
        Level level =
                code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED ? Level.FINE : Level.WARNING;
        LOGGER.log(
                level,
                "Coordinator membership " + operation + " failed for " + identity.role() + "/" + identity.nodeId()
                        + " (" + code + ")",
                error);
    }

    private void schedule(long delayMillis) {
        if (!closing.get()) {
            executor.schedule(this::runCycle, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        }
    }

    private long successDelayMillis() {
        long configured = settings.heartbeatInterval().toMillis();
        if (serverLeaseMillis <= 0) {
            return configured;
        }
        return Math.max(1, Math.min(configured, serverLeaseMillis / 3));
    }

    long retryDelayMillis(int failureCount) {
        long initial = settings.initialBackoff().toMillis();
        long maximum = settings.maxBackoff().toMillis();
        int shift = Math.min(Math.max(0, failureCount - 1), 30);
        long exponential = initial > (Long.MAX_VALUE >> shift) ? Long.MAX_VALUE : initial << shift;
        long bound = Math.min(maximum, exponential);
        return Math.max(0, Math.min(bound, jitter.applyAsLong(bound)));
    }

    private static void requireCompatibleResponse(boolean success, int contractVersion, String operation) {
        if (!success) {
            throw new IllegalStateException("Coordinator rejected membership " + operation);
        }
        if (contractVersion < MIN_SUPPORTED_CONTRACT_VERSION || contractVersion > MAX_SUPPORTED_CONTRACT_VERSION) {
            throw new IllegalStateException("Unsupported coordinator membership contract version: " + contractVersion);
        }
    }

    private static void requireTopology(String epoch, long version) {
        if (epoch == null || epoch.isBlank() || version < 1) {
            throw new IllegalStateException("Coordinator returned an invalid topology epoch or version");
        }
    }

    /** Resolve stable identity and coordinator settings from config plus process environment. */
    public static ResolvedMembership resolve(
            AppConfig appConfig, Map<String, String> environment, NodeRole role, int grpcPort, int healthPort) {
        Objects.requireNonNull(appConfig, "appConfig must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        AppConfig.ServiceDiscoveryConfig discovery = appConfig.getServiceDiscovery();
        if (discovery == null || !discovery.isEnabled()) {
            return null;
        }

        String prefix =
                switch (role) {
                    case NODE_ROLE_INDEX -> "INDEX_NODE";
                    case NODE_ROLE_QUERY -> "QUERY_NODE";
                    default -> throw new IllegalArgumentException("Only index and query nodes own membership: " + role);
                };
        AppConfig.NodeGroupConfig localGroup =
                role == NodeRole.NODE_ROLE_INDEX ? appConfig.getIndexNodes() : appConfig.getQueryNodes();
        AppConfig.NodeConfig configuredNode = findConfiguredNode(localGroup, environment.get(prefix + "_ID"), grpcPort);
        String nodeId = firstNonBlank(
                environment.get(prefix + "_ID"),
                environment.get("NODE_ID"),
                configuredNode == null ? null : configuredNode.getId());
        if (nodeId == null) {
            throw new IllegalArgumentException(prefix + "_ID or NODE_ID must identify this node");
        }
        String host = firstNonBlank(
                environment.get(prefix + "_HOST"),
                environment.get("NODE_HOST"),
                configuredNode == null ? null : configuredNode.getHost(),
                "localhost");

        AppConfig.NodeConfig coordinator = firstNode(appConfig.getCoordinatorNodes());
        String coordinatorHost =
                firstNonBlank(environment.get("COORDINATOR_HOST"), coordinator == null ? null : coordinator.getHost());
        int coordinatorPort = parsePort(
                "COORDINATOR_PORT",
                environment.get("COORDINATOR_PORT"),
                coordinator == null ? 0 : coordinator.getPort());
        if (coordinatorHost == null) {
            throw new IllegalArgumentException("COORDINATOR_HOST or coordinatorNodes.nodes[0].host must be set");
        }

        long expiryMillis = Duration.ofSeconds(positive(discovery.getNodeExpirySeconds(), 30))
                .toMillis();
        long requestedHeartbeat = Duration.ofSeconds(positive(discovery.getHeartbeatIntervalSeconds(), 10))
                .toMillis();
        Duration heartbeat = Duration.ofMillis(Math.max(1, Math.min(requestedHeartbeat, expiryMillis / 3)));
        Settings settings = new Settings(
                coordinatorHost,
                coordinatorPort,
                heartbeat,
                Duration.ofMillis(positive(discovery.getRegistrationInitialBackoffMillis(), 250)),
                Duration.ofSeconds(positive(discovery.getRegistrationMaxBackoffSeconds(), 10)),
                Duration.ofMillis(positive(discovery.getMembershipRpcDeadlineMillis(), 2000)),
                Duration.ofMillis(positive(discovery.getShutdownDeregisterTimeoutMillis(), 1000)));
        return new ResolvedMembership(new NodeIdentity(nodeId, host, grpcPort, healthPort, role), settings);
    }

    private static AppConfig.NodeConfig findConfiguredNode(
            AppConfig.NodeGroupConfig group, String requestedId, int grpcPort) {
        List<AppConfig.NodeConfig> nodes = group == null ? null : group.getNodes();
        if (nodes == null) {
            return null;
        }
        if (requestedId != null && !requestedId.isBlank()) {
            for (AppConfig.NodeConfig node : nodes) {
                if (requestedId.equals(node.getId())) {
                    return node;
                }
            }
        }
        for (AppConfig.NodeConfig node : nodes) {
            if (node.getPort() == grpcPort) {
                return node;
            }
        }
        return null;
    }

    private static AppConfig.NodeConfig firstNode(AppConfig.NodeGroupConfig group) {
        return group == null || group.getNodes() == null || group.getNodes().isEmpty()
                ? null
                : group.getNodes().getFirst();
    }

    private static int parsePort(String name, String environmentValue, int configuredValue) {
        int value = configuredValue;
        if (environmentValue != null && !environmentValue.isBlank()) {
            try {
                value = Integer.parseInt(environmentValue);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(name + " must be an integer: " + environmentValue, error);
            }
        }
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static void validateIdentity(NodeIdentity identity) {
        if (identity.nodeId() == null || identity.nodeId().isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (identity.host() == null || identity.host().isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        parsePort("node port", null, identity.port());
        parsePort("node health port", null, identity.healthPort());
        if (identity.role() != NodeRole.NODE_ROLE_INDEX && identity.role() != NodeRole.NODE_ROLE_QUERY) {
            throw new IllegalArgumentException("Only index and query nodes own membership: " + identity.role());
        }
    }

    private static void validateSettings(Settings settings) {
        if (settings.coordinatorHost() == null || settings.coordinatorHost().isBlank()) {
            throw new IllegalArgumentException("coordinatorHost must not be blank");
        }
        parsePort("coordinatorPort", null, settings.coordinatorPort());
        if (settings.heartbeatInterval().isZero()
                || settings.heartbeatInterval().isNegative()
                || settings.initialBackoff().isZero()
                || settings.initialBackoff().isNegative()
                || settings.maxBackoff().compareTo(settings.initialBackoff()) < 0
                || settings.rpcDeadline().isZero()
                || settings.rpcDeadline().isNegative()
                || settings.shutdownDeregisterTimeout().isZero()
                || settings.shutdownDeregisterTimeout().isNegative()) {
            throw new IllegalArgumentException(
                    "membership durations must be positive and maxBackoff >= initialBackoff");
        }
    }

    public record NodeIdentity(String nodeId, String host, int port, int healthPort, NodeRole role) {}

    public record Settings(
            String coordinatorHost,
            int coordinatorPort,
            Duration heartbeatInterval,
            Duration initialBackoff,
            Duration maxBackoff,
            Duration rpcDeadline,
            Duration shutdownDeregisterTimeout) {}

    public record ResolvedMembership(NodeIdentity identity, Settings settings) {}
}
