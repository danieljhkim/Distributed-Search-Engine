package com.danieljhkim.dsearch.querynode;

import com.danieljhkim.dsearch.common.cluster.NodeGroupManager;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;
import com.danieljhkim.dsearch.querynode.grpc.IndexService;
import com.danieljhkim.dsearch.querynode.search.SearchExecutor;
import com.danieljhkim.dsearch.querynode.server.QueryNodeServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.logging.Logger;

public class QueryNodeApplication {

    private static final Logger LOGGER = Logger.getLogger(QueryNodeApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        int grpcPort = requiredPort("QUERY_NODE_PORT");
        int healthPort = requiredPort("QUERY_NODE_HEALTH_PORT");
        QueryNodeRuntime runtime = createRuntime(grpcPort, healthPort, ConfigLoader.load());

        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        runtime.start();
    }

    static QueryNodeRuntime createRuntime(int grpcPort, int healthPort, AppConfig appConfig) {
        NodeGroupManager nodeGroupManager = new NodeGroupManager(appConfig);
        NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> coordinatorClientManager =
                nodeGroupManager.isServiceDiscoveryEnabled()
                        ? NodeClientManager.loadClientManager(
                                NodeRole.NODE_ROLE_COORDINATOR, ClusterServiceGrpc::newBlockingStub, nodeGroupManager)
                        : null;
        if (coordinatorClientManager != null) {
            nodeGroupManager.setCoordinatorManager(coordinatorClientManager);
        }
        NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager =
                NodeClientManager.loadClientManager(
                        NodeRole.NODE_ROLE_INDEX, IndexServiceGrpc::newBlockingStub, nodeGroupManager);
        SearchExecutor searchExecutor = new SearchExecutor(nodeClientManager);
        BaseIndexService indexService = new IndexService(nodeClientManager);
        QueryNodeServer queryNodeServer = new QueryNodeServer(grpcPort, searchExecutor, indexService, appConfig);
        return new QueryNodeRuntime(
                healthPort, queryNodeServer, searchExecutor, nodeClientManager, coordinatorClientManager);
    }

    private static int requiredPort(String environmentVariable) {
        return parsePort(environmentVariable, System.getenv(environmentVariable));
    }

    static int parsePort(String environmentVariable, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(environmentVariable + " must be set");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(environmentVariable + " must be an integer: " + value, e);
        }
    }

    static final class QueryNodeRuntime implements AutoCloseable {
        private final int healthPort;
        private final QueryNodeServer queryNodeServer;
        private final SearchExecutor searchExecutor;
        private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;
        private final NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> coordinatorClientManager;
        private HttpServer healthServer;

        private QueryNodeRuntime(
                int healthPort,
                QueryNodeServer queryNodeServer,
                SearchExecutor searchExecutor,
                NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager,
                NodeClientManager<ClusterServiceGrpc.ClusterServiceBlockingStub> coordinatorClientManager) {
            this.healthPort = healthPort;
            this.queryNodeServer = queryNodeServer;
            this.searchExecutor = searchExecutor;
            this.nodeClientManager = nodeClientManager;
            this.coordinatorClientManager = coordinatorClientManager;
        }

        void start() throws IOException, InterruptedException {
            try {
                healthServer = HealthHttpServer.start(healthPort, "query-node");
                queryNodeServer.start();
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            } catch (InterruptedException e) {
                close();
                throw e;
            }
        }

        @Override
        public void close() {
            LOGGER.info("Shutting down QueryNode gRPC server...");
            try {
                queryNodeServer.shutdown();
            } catch (InterruptedException e) {
                LOGGER.warning("Interrupted while shutting down QueryNode gRPC server: " + e);
                Thread.currentThread().interrupt();
            } finally {
                try {
                    searchExecutor.close();
                } catch (IOException e) {
                    LOGGER.warning("Error closing SearchExecutor: " + e);
                }
                nodeClientManager.shutdown();
                if (coordinatorClientManager != null) {
                    coordinatorClientManager.shutdown();
                }
                if (healthServer != null) {
                    healthServer.stop(0);
                    healthServer = null;
                }
            }
        }
    }
}
