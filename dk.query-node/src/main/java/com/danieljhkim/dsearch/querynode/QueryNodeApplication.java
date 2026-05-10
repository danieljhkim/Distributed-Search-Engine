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
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryNodeApplication {

    private static final Logger LOGGER = Logger.getLogger(QueryNodeApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        int grpcPort = Integer.parseInt(System.getenv("QUERY_NODE_PORT"));
        int healthPort = Integer.parseInt(System.getenv("QUERY_NODE_HEALTH_PORT"));

        AppConfig appConfig = ConfigLoader.load();
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
        HttpServer healthServer = HealthHttpServer.start(healthPort, "query-node");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down QueryNode gRPC server...");
            try {
                queryNodeServer.shutdown();
                nodeClientManager.shutdown();
                if (coordinatorClientManager != null) {
                    coordinatorClientManager.shutdown();
                }
                healthServer.stop(0);
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Error during shutdown", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error closing", e);
            }
        }));

        LOGGER.info(() -> "QueryNode gRPC server started on port " + grpcPort);
        LOGGER.info(() -> "QueryNode health endpoint on port " + healthPort + " at /health");
        queryNodeServer.start();
    }
}
