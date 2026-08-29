package com.danieljhkim.dsearch.common.grpc;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.cluster.NodeGroupManager;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.exception.NodeUnavailableException;
import com.danieljhkim.dsearch.common.loadbalancer.RoundRobin;
import com.danieljhkim.dsearch.common.routing.DocumentOwnership;
import com.danieljhkim.dsearch.common.shard.ShardState;
import com.danieljhkim.dsearch.common.shard.ShardStateStore;
import com.danieljhkim.dsearch.common.tracing.CorrelationIdClientInterceptor;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import lombok.Getter;

public class NodeClientManager<T> {

    private static final Logger LOGGER = Logger.getLogger(NodeClientManager.class.getName());
    private static final CorrelationIdClientInterceptor TRACING_INTERCEPTOR = new CorrelationIdClientInterceptor();

    private final RoundRobin<T> rr;

    @Getter
    private final Map<String, NodeClient<T>> clientMap;

    private volatile List<NodeClient<T>> activeClients;
    private final RoutingStrategy routingStrategy;
    private final NodeClientHealthRefresher healthRefresher;
    private final NodeRole nodeRole;
    private final Function<NodeRole, NodeGroup> nodeGroupResolver;
    private final Function<NodeGroup.NodeInfo, NodeClient<T>> nodeClientFactory;

    /**
     * Ownership ring for document mutations. Fixed for the lifetime of this
     * manager and deliberately independent of liveness and service discovery:
     * see {@link #ownerClient(String, String)}.
     */
    private final List<String> ownershipNodeIds;

    public NodeClientManager(
            Map<String, NodeClient<T>> clientMap,
            RoutingStrategy routingStrategy,
            NodeRole nodeRole,
            Function<Channel, T> clientFactory) {
        this(clientMap, routingStrategy, nodeRole, role -> null, null, node -> {
            throw new IllegalStateException("No node client factory configured for service discovery refresh");
        });
    }

    NodeClientManager(
            Map<String, NodeClient<T>> clientMap,
            RoutingStrategy routingStrategy,
            NodeRole nodeRole,
            Function<NodeRole, NodeGroup> nodeGroupResolver,
            AppConfig.ServiceDiscoveryConfig serviceDiscoveryConfig,
            Function<NodeGroup.NodeInfo, NodeClient<T>> nodeClientFactory) {
        this(
                clientMap,
                routingStrategy,
                nodeRole,
                nodeGroupResolver,
                serviceDiscoveryConfig,
                nodeClientFactory,
                clientMap != null ? clientMap.keySet() : null);
    }

    NodeClientManager(
            Map<String, NodeClient<T>> clientMap,
            RoutingStrategy routingStrategy,
            NodeRole nodeRole,
            Function<NodeRole, NodeGroup> nodeGroupResolver,
            AppConfig.ServiceDiscoveryConfig serviceDiscoveryConfig,
            Function<NodeGroup.NodeInfo, NodeClient<T>> nodeClientFactory,
            Collection<String> ownershipNodeIds) {
        Objects.requireNonNull(clientMap, "clientMap must not be null");
        if (clientMap.isEmpty()) {
            throw new IllegalArgumentException("clientMap must not be empty");
        }
        Objects.requireNonNull(ownershipNodeIds, "ownershipNodeIds must not be null");
        if (ownershipNodeIds.isEmpty()) {
            throw new IllegalArgumentException("ownershipNodeIds must not be empty");
        }
        this.ownershipNodeIds = ownershipNodeIds.stream().sorted().toList();
        this.nodeRole = Objects.requireNonNull(nodeRole, "nodeRole must not be null");
        this.clientMap = new ConcurrentHashMap<>(clientMap);
        this.routingStrategy = Objects.requireNonNull(routingStrategy, "routingStrategy must not be null");
        this.nodeGroupResolver = Objects.requireNonNull(nodeGroupResolver, "nodeGroupResolver must not be null");
        this.nodeClientFactory = Objects.requireNonNull(nodeClientFactory, "nodeClientFactory must not be null");
        rebuildActiveClientSnapshot();
        this.rr = RoundRobin.dynamic(this::activeClientsSnapshot);

        if (serviceDiscoveryConfig != null && serviceDiscoveryConfig.isEnabled()) {
            int interval = Math.max(1, serviceDiscoveryConfig.getRefreshIntervalSeconds());
            this.healthRefresher = new NodeClientHealthRefresher(interval);
            LOGGER.info(() -> "Service discovery enabled for role " + nodeRole + " with refresh interval: " + interval
                    + " seconds");
        } else {
            this.healthRefresher = null;
            LOGGER.info(() -> "Service discovery disabled for role " + nodeRole + "; using static node configuration");
        }
        LOGGER.info(() -> "Role " + nodeRole + " reads use routing strategy " + this.routingStrategy
                + "; document mutations use ownership hashing over nodes " + this.ownershipNodeIds);
    }

    public static <T> NodeClientManager<T> loadClientManager(NodeRole role, Function<Channel, T> clientFactory) {
        try {
            AppConfig appConfig = ConfigLoader.load();
            NodeGroupManager nodeGroupManager = new NodeGroupManager(appConfig);
            if (role != NodeRole.NODE_ROLE_COORDINATOR) {
                configureCoordinatorManager(nodeGroupManager);
            }
            return loadClientManager(role, clientFactory, nodeGroupManager);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application configuration", e);
        }
    }

    public static <T> NodeClientManager<T> loadClientManager(
            NodeRole role, Function<Channel, T> clientFactory, NodeGroupManager nodeGroupManager) {
        boolean useServiceDiscovery = role != NodeRole.NODE_ROLE_COORDINATOR;
        return loadClientManager(role, clientFactory, nodeGroupManager, useServiceDiscovery);
    }

    private static void configureCoordinatorManager(NodeGroupManager nodeGroupManager) {
        if (!nodeGroupManager.isServiceDiscoveryEnabled() || nodeGroupManager.hasCoordinatorManager()) {
            return;
        }
        NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> coordinatorManager = loadClientManager(
                NodeRole.NODE_ROLE_COORDINATOR, ClusterServiceGrpc::newBlockingStub, nodeGroupManager, false);
        nodeGroupManager.setCoordinatorManager(coordinatorManager);
    }

    private static <T> NodeClientManager<T> loadClientManager(
            NodeRole role,
            Function<Channel, T> clientFactory,
            NodeGroupManager nodeGroupManager,
            boolean useServiceDiscovery) {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(clientFactory, "clientFactory must not be null");
        Objects.requireNonNull(nodeGroupManager, "nodeGroupManager must not be null");

        // The ownership ring comes from the operator-managed configuration file rather than
        // from discovery, so it is identical in every process and across restarts. Discovery
        // only decides which of those nodes is currently reachable.
        NodeGroup configuredNodeGroup = nodeGroupManager.getConfiguredNodeGroup(role);
        NodeGroup nodeGroup = useServiceDiscovery ? nodeGroupManager.getNodeGroup(role) : configuredNodeGroup;
        if (nodeGroup == null) {
            throw new IllegalStateException("No node group configured for role: " + role);
        }

        PrometheusGrpcClientInterceptor metricsInterceptor =
                new PrometheusGrpcClientInterceptor(componentLabel(nodeGroup));
        Map<String, NodeClient<T>> clientMap = new HashMap<>();
        for (NodeGroup.NodeInfo node : nodeGroup.getAllNodes()) {
            clientMap.put(node.getNodeId(), createNodeClient(node, clientFactory, metricsInterceptor));
        }
        // A configured node that is down when this process starts still owns its documents,
        // so keep a client for it; it stays inactive until discovery reports it healthy.
        for (NodeGroup.NodeInfo node : configuredNodeGroup.getAllNodes()) {
            clientMap.computeIfAbsent(node.getNodeId(), id -> {
                NodeClient<T> client = createNodeClient(node, clientFactory, metricsInterceptor);
                client.setActive(false);
                return client;
            });
        }

        List<String> ownershipNodeIds = configuredNodeGroup.getAllNodes().stream()
                .map(NodeGroup.NodeInfo::getNodeId)
                .toList();
        if (ownershipNodeIds.isEmpty()) {
            throw new IllegalStateException("No nodes configured for role: " + role);
        }

        AppConfig.ServiceDiscoveryConfig serviceDiscoveryConfig =
                useServiceDiscovery ? nodeGroupManager.getServiceDiscoveryConfig() : null;
        return new NodeClientManager<>(
                clientMap,
                nodeGroup.getRoutingStrategy(),
                role,
                nodeGroupManager::getNodeGroup,
                serviceDiscoveryConfig,
                node -> createNodeClient(node, clientFactory, metricsInterceptor),
                ownershipNodeIds);
    }

    private static String componentLabel(NodeGroup nodeGroup) {
        return nodeGroup.getComponentLabel() != null ? nodeGroup.getComponentLabel() : "gateway";
    }

    private static <T> NodeClient<T> createNodeClient(
            NodeGroup.NodeInfo node,
            Function<Channel, T> clientFactory,
            PrometheusGrpcClientInterceptor metricsInterceptor) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(node.getHost(), node.getPort())
                .usePlaintext()
                .build();
        Channel interceptedChannel = ClientInterceptors.intercept(channel, TRACING_INTERCEPTOR, metricsInterceptor);
        T stub = clientFactory.apply(interceptedChannel);
        return new NodeClient<>(node.getNodeId(), stub, channel, node.getHost(), node.getHealthPort());
    }

    /**
     * Get a client/stub for the next node in round-robin for READ operations.
     */
    public T nextClient() {
        return this.rr.next().getStub();
    }

    /**
     * Ownership ring used for document mutations, in a stable order.
     */
    public List<String> getOwnershipNodeIds() {
        return ownershipNodeIds;
    }

    /**
     * Node that authoritatively owns {@code (partitionId, documentId)}.
     *
     * <p>The ring is the configured node set, not the currently healthy subset,
     * so an unhealthy node keeps ownership of its documents rather than handing
     * them to a peer that would then hold a second copy of the same document.
     */
    public String ownerNodeId(String partitionId, String documentId) {
        return DocumentOwnership.ownerNodeId(partitionId, documentId, ownershipNodeIds);
    }

    /**
     * Client for the node that owns {@code (partitionId, documentId)}.
     *
     * <p>Callers mutate the returned client's shard doc counts themselves, and
     * only once the node has confirmed the mutation.
     *
     * @throws NodeUnavailableException if the owner has no client or is not currently active
     */
    public NodeClient<T> ownerClient(String partitionId, String documentId) {
        String ownerNodeId = ownerNodeId(partitionId, documentId);
        NodeClient<T> owner = clientMap.get(ownerNodeId);
        if (owner == null) {
            throw new NodeUnavailableException(
                    ownerNodeId,
                    "No client for owner node " + ownerNodeId + " of document " + documentId + " in partition "
                            + partitionId);
        }
        if (!owner.isActive()) {
            throw new NodeUnavailableException(
                    ownerNodeId,
                    "Owner node " + ownerNodeId + " of document " + documentId + " in partition " + partitionId
                            + " is not available; the mutation is not rerouted because another node would hold a"
                            + " competing copy of the document");
        }
        return owner;
    }

    public ShardStateStore.ShardDocSnapshot snapshotShardDocCounts() {
        ShardStateStore.ShardDocSnapshot snapshot = new ShardStateStore.ShardDocSnapshot();
        for (NodeClient<T> client : clientMap.values()) {
            ShardStateStore.NodeEntry entry = new ShardStateStore.NodeEntry();
            entry.setNodeId(client.getNodeId());

            Map<String, Long> shardCounts = new HashMap<>();
            for (ShardState state : client.getShardStates().values()) {
                shardCounts.put(state.getPartitionId(), state.getDocumentCount());
            }
            entry.setShards(shardCounts);
            snapshot.getNodes().add(entry);
        }
        return snapshot;
    }

    public void applySnapshot(ShardStateStore.ShardDocSnapshot snapshot) {
        Map<String, ShardStateStore.NodeEntry> byNode = new HashMap<>();
        for (ShardStateStore.NodeEntry e : snapshot.getNodes()) {
            byNode.put(e.getNodeId(), e);
        }

        for (NodeClient<T> client : clientMap.values()) {
            ShardStateStore.NodeEntry entry = byNode.get(client.getNodeId());
            if (entry == null) {
                continue;
            }
            for (Map.Entry<String, Long> shardCount : entry.getShards().entrySet()) {
                String shardId = shardCount.getKey();
                long count = shardCount.getValue();
                ShardState state = client.getOrCreateShardState(shardId);
                state.getDocCount().set(count);
            }
        }
    }

    void refreshClientsFromCluster() {
        NodeGroup cfg = nodeGroupResolver.apply(this.nodeRole);
        if (cfg == null || cfg.getNodes() == null) {
            LOGGER.warning(() -> "No node group available while refreshing role " + nodeRole);
            return;
        }

        List<NodeGroup.NodeInfo> discoveredNodes = cfg.getAllNodes();
        Set<String> discoveredNodeIds = discoveredNodes.stream()
                .map(NodeGroup.NodeInfo::getNodeId)
                .collect(java.util.stream.Collectors.toSet());

        for (NodeGroup.NodeInfo nodeConfig : discoveredNodes) {
            NodeClient<T> client =
                    clientMap.computeIfAbsent(nodeConfig.getNodeId(), id -> nodeClientFactory.apply(nodeConfig));
            client.setActive(true);
        }
        for (NodeClient<T> client : clientMap.values()) {
            if (!discoveredNodeIds.contains(client.getNodeId())) {
                client.setActive(false);
            }
        }
        rebuildActiveClientSnapshot();
    }

    public List<String> getActiveNodeIds() {
        return activeClientsSnapshot().stream().map(NodeClient::getNodeId).toList();
    }

    List<NodeClient<T>> activeClientsSnapshot() {
        return activeClients;
    }

    private void rebuildActiveClientSnapshot() {
        this.activeClients = this.clientMap.values().stream()
                .filter(NodeClient::isActive)
                .sorted(Comparator.comparing(NodeClient::getNodeId))
                .toList();
    }

    public void shutdown() {
        if (healthRefresher != null) {
            healthRefresher.shutdown();
        }
        for (NodeClient<T> client : this.clientMap.values()) {
            ManagedChannel channel = client.getChannel();
            if (!channel.isShutdown() && !channel.isTerminated()) {
                channel.shutdown();
            }
        }
    }

    /**
     * Periodically refreshes the node clients from the coordinator / service
     * discovery.
     */
    private class NodeClientHealthRefresher {
        private final ScheduledExecutorService scheduler;

        NodeClientHealthRefresher(int refreshIntervalSeconds) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t =
                        new Thread(r, "node-health-checker-" + nodeRole.name().toLowerCase());
                t.setDaemon(true);
                return t;
            });
            this.scheduler.scheduleAtFixedRate(
                    NodeClientManager.this::refreshClientsFromCluster,
                    refreshIntervalSeconds,
                    refreshIntervalSeconds,
                    TimeUnit.SECONDS);
        }

        void shutdown() {
            scheduler.shutdownNow();
        }
    }
}
