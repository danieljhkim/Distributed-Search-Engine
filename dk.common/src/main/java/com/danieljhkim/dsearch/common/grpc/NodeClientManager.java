package com.danieljhkim.dsearch.common.grpc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.enums.RoutingStrategy;
import com.danieljhkim.dsearch.common.loadbalancer.RoundRobin;
import com.danieljhkim.dsearch.common.shard.ShardState;
import com.danieljhkim.dsearch.common.shard.ShardStateStore;
import com.danieljhkim.dsearch.common.tracing.CorrelationIdClientInterceptor;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoRequest;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeInfo;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.Getter;

public class NodeClientManager<T> {

	private static final Logger LOGGER = Logger.getLogger(NodeClientManager.class.getName());
	private static final CorrelationIdClientInterceptor tracingInterceptor = new CorrelationIdClientInterceptor();
	private static NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> coordinatorManager;
	private static AppConfig defaultConfig;
	private static PrometheusGrpcClientInterceptor metricsInterceptor;

	static {
		try {
			defaultConfig = ConfigLoader.load();
			coordinatorManager = loadClientManager(NodeRole.NODE_ROLE_COORDINATOR, ClusterServiceGrpc::newBlockingStub);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(Level.SEVERE, "Failed to load application configuration", e);
		}
	}

	private final RoundRobin<T> rr;
	@Getter
	private final Map<String, NodeClient<T>> clientMap;
	private final RoutingStrategy routingStrategy;
	private final NodeClientHealthRefresher healthRefresher;
	private final NodeRole nodeRole;
	private final Function<Channel, T> clientFactory;

	public NodeClientManager(Map<String, NodeClient<T>> clientMap, RoutingStrategy routingStrategy, NodeRole nodeRole,
			Function<Channel, T> clientFactory) {
		this.nodeRole = nodeRole;
		this.clientFactory = clientFactory;
		this.clientMap = Objects.requireNonNull(clientMap, "clientMap must not be null");
		this.rr = new RoundRobin<>(this.clientMap.values().stream().toList());
		this.routingStrategy = routingStrategy;

		AppConfig.ServiceDiscoveryConfig sd = defaultConfig != null ? defaultConfig.getServiceDiscovery() : null;
		if (sd != null && sd.isEnabled()) {
			int interval = sd.getRefreshIntervalSeconds();
			this.healthRefresher = new NodeClientHealthRefresher(interval);
			LOGGER.info(() -> "Service discovery enabled for role " + nodeRole + " with refresh interval: " + interval
					+ " seconds");
		} else {
			this.healthRefresher = null;
			LOGGER.info(() -> "Service discovery disabled for role " + nodeRole + "; using static node configuration");
		}
	}

	public static <T> NodeClientManager<T> loadClientManager(
			NodeRole role,
			Function<Channel, T> clientFactory) {
		AppConfig.NodeGroupConfig appConfig = getNodeGroupConfig(role);
		if (metricsInterceptor == null) {
			String component = appConfig.getComponentLabel() != null ? appConfig.getComponentLabel() : "gateway";
			metricsInterceptor = new PrometheusGrpcClientInterceptor(component);
		}
		Map<String, NodeClient<T>> clientMap = appConfig.getNodes().stream()
				.collect(Collectors.toMap(
						node -> String.valueOf(node.getId()),
						node -> {
							ManagedChannel channel = ManagedChannelBuilder
									.forAddress(node.getHost(), node.getPort())
									.usePlaintext()
									.build();
							Channel interceptedChannel = ClientInterceptors.intercept(
									channel,
									tracingInterceptor,
									metricsInterceptor);
							T stub = clientFactory.apply(interceptedChannel);
							return new NodeClient<>(
									String.valueOf(node.getId()),
									stub,
									channel,
									node.getHost(),
									node.getHealthPort());
						}));
		return new NodeClientManager<>(clientMap, appConfig.getRoutingStrategy(), role, clientFactory);
	}

	@SuppressWarnings("all")
	public static AppConfig.NodeGroupConfig getNodeGroupConfig(NodeRole role) {
		if (role == NodeRole.NODE_ROLE_COORDINATOR) {
			// hack: for coordinator role, use static config.
			return getStaticNodeGroupConfig(role);
		}
		// If service discovery is disabled just use the static config from
		// app-config.yaml
		if (!isServiceDiscoveryEnabled()
				|| coordinatorManager == null
				|| defaultConfig == null
				|| defaultConfig.getCoordinatorNodes() == null) {
			return getStaticNodeGroupConfig(role);
		}
		GetClusterInfoRequest request = GetClusterInfoRequest.newBuilder().setRole(role).build();
		try {
			GetClusterInfoResponse response = coordinatorManager.nextClient().getClusterInfo(request);
			AppConfig.NodeGroupConfig nodeGroupConfig = new AppConfig.NodeGroupConfig();
			nodeGroupConfig.setComponentLabel(response.getComponentLabel());
			nodeGroupConfig.setRoutingStrategy(RoutingStrategy.valueOf(response.getRoutingStrategy()));

			List<AppConfig.NodeConfig> nodeConfigs = new ArrayList<>();
			for (NodeInfo nodeProto : response.getNodesList()) {
				AppConfig.NodeConfig nodeConfig = new AppConfig.NodeConfig();
				nodeConfig.setId(nodeProto.getNodeId());
				nodeConfig.setHost(nodeProto.getHost());
				nodeConfig.setPort(nodeProto.getPort());
				nodeConfig.setHealthPort(nodeProto.getHealthPort());
				nodeConfigs.add(nodeConfig);
			}
			nodeGroupConfig.setNodes(nodeConfigs);
			return nodeGroupConfig;
		} catch (Exception e) {
			LOGGER.warning(() -> "Failed to fetch cluster info from coordinator for role "
					+ role + ". Falling back to static configuration. Cause: " + e.toString());
			return getStaticNodeGroupConfig(role);
		}
	}

	private static AppConfig.NodeGroupConfig getStaticNodeGroupConfig(NodeRole role) {
		if (defaultConfig == null) {
			return null;
		}
		return switch (role) {
			case NODE_ROLE_INDEX -> defaultConfig.getIndexNodes();
			case NODE_ROLE_QUERY -> defaultConfig.getQueryNodes();
			case NODE_ROLE_COORDINATOR -> defaultConfig.getCoordinatorNodes();
			default -> throw new IllegalArgumentException("Unsupported role: " + role);
		};
	}

	private static boolean isServiceDiscoveryEnabled() {
		if (defaultConfig == null || defaultConfig.getServiceDiscovery() == null) {
			return false;
		}
		return defaultConfig.getServiceDiscovery().isEnabled();
	}

	/**
	 * Get a client/stub for the next node in round-robin order or least loaded for
	 * WRITE/DEL ops.
	 */
	public T nextClient(String partitionId, boolean isWriteOperation) {
		LOGGER.info(() -> "Routing strategy: " + this.routingStrategy);
		if (this.routingStrategy == RoutingStrategy.LEAST_LOADED) {
			return nextLeastLoadedClient(partitionId, isWriteOperation);
		}
		return this.rr.next().getStub();
	}

	/**
	 * Get a client/stub for the next node in round-robin for READ operations.
	 */
	public T nextClient() {
		return this.rr.next().getStub();
	}

	public T nextLeastLoadedClient(String partitionId, boolean isWriteOperation) { // TODO: handle update
		NodeClient<T> leastLoadedClient = null;
		long minDocCount = Long.MAX_VALUE;
		for (NodeClient<T> client : clientMap.values()) {
			long shardDocCount = client.getShardDocCount(partitionId);
			LOGGER.info(() -> "Client " + client.getNodeId() + " has partition " + partitionId + " doc count: "
					+ shardDocCount);
			if (shardDocCount < minDocCount && client.isActive()) {
				minDocCount = shardDocCount;
				leastLoadedClient = client;
			}
		}
		if (leastLoadedClient != null) {
			if (isWriteOperation) {
				leastLoadedClient.incrementDocToShard(partitionId);
			} else {
				leastLoadedClient.decrementDocFromShard(partitionId);
			}
			return leastLoadedClient.getStub();
		} else {
			throw new IllegalStateException("No available clients for shard: " + partitionId);
		}
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
		for (NodeClient<T> client : clientMap.values()) {
			client.setActive(false);
		}
		AppConfig.NodeGroupConfig cfg = getNodeGroupConfig(this.nodeRole);
		if (cfg == null || cfg.getNodes() == null) {
			return;
		}
		for (AppConfig.NodeConfig nodeConfig : cfg.getNodes()) {
			NodeClient<T> existing = clientMap.get(nodeConfig.getId());
			if (existing == null) {
				clientMap.put(nodeConfig.getId(), initClient(nodeConfig));
			} else {
				existing.setActive(true);
			}
		}
	}

	private NodeClient<T> initClient(AppConfig.NodeConfig node) {
		ManagedChannel channel = ManagedChannelBuilder
				.forAddress(node.getHost(), node.getPort())
				.usePlaintext()
				.build();
		Channel interceptedChannel = ClientInterceptors.intercept(
				channel,
				tracingInterceptor,
				metricsInterceptor);
		T stub = clientFactory.apply(interceptedChannel);
		return new NodeClient<>(
				String.valueOf(node.getId()),
				stub,
				channel,
				node.getHost(),
				node.getHealthPort());
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
		coordinatorManager.shutdown();
	}

	/**
	 * Periodically refreshes the node clients from the coordinator / service
	 * discovery.
	 */
	private class NodeClientHealthRefresher {
		private final ScheduledExecutorService scheduler;

		NodeClientHealthRefresher(int refreshIntervalSeconds) {
			this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "node-health-checker-" + nodeRole.name().toLowerCase());
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