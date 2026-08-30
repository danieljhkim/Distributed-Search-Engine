package com.danieljhkim.dsearch.indexnode;

import com.danieljhkim.dsearch.common.cluster.NodeMembershipAgent;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.AppConfig.FieldConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import com.danieljhkim.dsearch.indexnode.server.IndexNodeServer;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IndexNodeApplication {

    private static final Logger LOGGER = Logger.getLogger(IndexNodeApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {

        int grpcPort = Integer.parseInt(System.getenv("INDEX_NODE_PORT"));
        int healthPort = Integer.parseInt(System.getenv("INDEX_NODE_HEALTH_PORT"));
        String baseDirStr = System.getenv("INDEX_NODE_BASE_DIR");
        Path baseDir = Path.of(baseDirStr);

        // Load configuration including field configs
        AppConfig appConfig = ConfigLoader.load();
        List<FieldConfig> fieldConfigs = appConfig.getFieldConfigs();

        IndexingRuntimeConfig indexingConfig = resolveIndexingConfig(appConfig, System.getenv());
        IndexManager indexManager = new IndexManager(
                baseDir, indexingConfig.maxBufferedOpsPerShard(), indexingConfig.maxFlushInterval(), fieldConfigs);
        IndexNodeServer indexNodeServer = new IndexNodeServer(grpcPort, indexManager);
        HttpServer healthServer = HealthHttpServer.start(healthPort, "index-node");
        NodeMembershipAgent membershipAgent = createMembershipAgent(appConfig, System.getenv(), grpcPort, healthPort);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down IndexNode gRPC server...");
            if (membershipAgent != null) {
                membershipAgent.close();
            }
            try {
                indexNodeServer.shutdown();
                healthServer.stop(0);

            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Error during shutdown", e);
                Thread.currentThread().interrupt();
            }
            try {
                indexManager.close();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error closing IndexManager", e);
            }
        }));

        LOGGER.info(() -> "IndexNode gRPC server started on port " + grpcPort);
        LOGGER.info(() -> "IndexNode health endpoint on port " + healthPort + " at /health");

        indexNodeServer.startAsync();
        if (membershipAgent != null) {
            membershipAgent.start();
        }
        indexNodeServer.awaitTermination();
    }

    static NodeMembershipAgent createMembershipAgent(
            AppConfig appConfig, Map<String, String> environment, int grpcPort, int healthPort) {
        NodeMembershipAgent.ResolvedMembership resolved =
                NodeMembershipAgent.resolve(appConfig, environment, NodeRole.NODE_ROLE_INDEX, grpcPort, healthPort);
        if (resolved == null) {
            return null;
        }
        ManagedChannel channel = ManagedChannelBuilder.forAddress(
                        resolved.settings().coordinatorHost(),
                        resolved.settings().coordinatorPort())
                .usePlaintext()
                .build();
        return new NodeMembershipAgent(
                resolved.identity(), resolved.settings(), ClusterServiceGrpc.newBlockingStub(channel), channel);
    }

    static IndexingRuntimeConfig resolveIndexingConfig(AppConfig appConfig, Map<String, String> environment) {
        AppConfig.IndexingConfig config = appConfig.getIndexing();
        int maxBufferedOpsPerShard =
                config != null ? config.getMaxBufferedOpsPerShard() : IndexManager.DEFAULT_MAX_BUFFERED_OPS_PER_SHARD;
        int maxFlushIntervalSeconds = config != null
                ? config.getMaxFlushIntervalSeconds()
                : (int) IndexManager.DEFAULT_MAX_FLUSH_INTERVAL.toSeconds();

        maxBufferedOpsPerShard =
                readPositiveInt(environment, "INDEX_NODE_MAX_BUFFERED_OPS_PER_SHARD", maxBufferedOpsPerShard);
        maxFlushIntervalSeconds =
                readPositiveInt(environment, "INDEX_NODE_MAX_FLUSH_INTERVAL_SECONDS", maxFlushIntervalSeconds);

        return new IndexingRuntimeConfig(maxBufferedOpsPerShard, Duration.ofSeconds(maxFlushIntervalSeconds));
    }

    private static int readPositiveInt(Map<String, String> environment, String name, int defaultValue) {
        String rawValue = environment.get(name);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }

        int parsedValue = Integer.parseInt(rawValue);
        if (parsedValue < 1) {
            throw new IllegalArgumentException(name + " must be greater than 0");
        }
        return parsedValue;
    }

    static record IndexingRuntimeConfig(int maxBufferedOpsPerShard, Duration maxFlushInterval) {}
}
