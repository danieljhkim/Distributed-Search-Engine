package com.danieljhkim.dsearch.coordinator.scheduler;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HealthCheckScheduler {

    private static final Logger LOGGER = Logger.getLogger(HealthCheckScheduler.class.getName());

    private final ScheduledExecutorService clusterHealthScheduler;
    private final HttpClient httpClient;
    private final ClusterMembershipService membershipService;
    private final int refreshIntervalSeconds;
    private final boolean enabled;

    public HealthCheckScheduler(ClusterMembershipService membershipService, AppConfig appConfig) {
        this(
                membershipService,
                appConfig,
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "node-health-checker");
                    t.setDaemon(true);
                    return t;
                }));
    }

    HealthCheckScheduler(
            ClusterMembershipService membershipService,
            AppConfig appConfig,
            HttpClient httpClient,
            ScheduledExecutorService clusterHealthScheduler) {
        this.refreshIntervalSeconds = appConfig.getServiceDiscovery().getRefreshIntervalSeconds();
        this.enabled = appConfig.getServiceDiscovery().isEnabled();
        this.membershipService = membershipService;
        this.httpClient = httpClient;
        this.clusterHealthScheduler = clusterHealthScheduler;
    }

    public void start() {
        if (!enabled) {
            LOGGER.info("Health check scheduler is disabled.");
            return;
        }
        this.clusterHealthScheduler.scheduleAtFixedRate(
                this::checkClusterHealth, refreshIntervalSeconds, refreshIntervalSeconds, TimeUnit.SECONDS);
    }

    public void shutdown() throws InterruptedException {
        this.clusterHealthScheduler.shutdown();
        if (!this.clusterHealthScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            this.clusterHealthScheduler.shutdownNow();
        }
    }

    void checkClusterHealth() {
        for (NodeGroup.NodeInfo nodeInfo : membershipService.getIndexGroup().getAllNodes()) {
            boolean isHealthy = checkNodeHealth(nodeInfo);
            membershipService.recordHealthCheck(nodeInfo.getNodeId(), NodeRole.NODE_ROLE_INDEX, isHealthy);
        }
        for (NodeGroup.NodeInfo nodeInfo : membershipService.getQueryGroup().getAllNodes()) {
            boolean isHealthy = checkNodeHealth(nodeInfo);
            membershipService.recordHealthCheck(nodeInfo.getNodeId(), NodeRole.NODE_ROLE_QUERY, isHealthy);
        }
        for (String expiredNode : membershipService.expireNodes()) {
            LOGGER.warning(() -> "Expired coordinator membership lease: " + expiredNode);
        }
    }

    private boolean checkNodeHealth(NodeGroup.NodeInfo nodeInfo) {
        String healthCheckUrl = String.format("http://%s:%d/health", nodeInfo.getHost(), nodeInfo.getHealthPort());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(healthCheckUrl))
                .timeout(Duration.ofMillis(500))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    "Health check failed for node: " + nodeInfo.getNodeId() + " (" + nodeInfo.getHost() + ":"
                            + nodeInfo.getHealthPort() + ")",
                    e);
            return false;
        }
    }
}
