package com.danieljhkim.dsearch.coordinator;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.coordinator.scheduler.HealthCheckScheduler;
import com.danieljhkim.dsearch.coordinator.server.CoordinatorServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CoordinatorApplication {

    private static final Logger LOGGER = Logger.getLogger(CoordinatorApplication.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = requiredPort("COORDINATOR_PORT", System.getenv("COORDINATOR_PORT"));
        int healthPort = requiredPort("COORDINATOR_HEALTH_PORT", System.getenv("COORDINATOR_HEALTH_PORT"));
        AppConfig appConfig = ConfigLoader.load();
        CoordinatorRuntime runtime = start(appConfig, port, healthPort);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down Coordinator gRPC server...");
            try {
                runtime.shutdown();
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Interrupted during coordinator shutdown", e);
                Thread.currentThread().interrupt();
            }
        }));

        runtime.awaitTermination();
    }

    static CoordinatorRuntime start(AppConfig appConfig, int port, int healthPort) throws IOException {
        Objects.requireNonNull(appConfig, "appConfig must not be null");
        ClusterMembershipService membershipService = new ClusterMembershipService(appConfig);
        CoordinatorServer server = new CoordinatorServer(port, membershipService);
        HealthCheckScheduler healthCheckScheduler = new HealthCheckScheduler(membershipService, appConfig);
        AtomicBoolean acceptingRequests = new AtomicBoolean();
        HttpServer healthServer = HealthHttpServer.start(
                healthPort,
                "coordinator-node",
                () -> acceptingRequests.get()
                        ? HealthHttpServer.Readiness.up()
                        : HealthHttpServer.Readiness.notReady("coordinator_starting"));
        try {
            server.startAsync();
            healthCheckScheduler.start();
            acceptingRequests.set(true);
            LOGGER.info(() -> "Coordinator gRPC server started on port " + server.getPort());
            return new CoordinatorRuntime(server, healthCheckScheduler, healthServer, acceptingRequests);
        } catch (IOException | RuntimeException e) {
            acceptingRequests.set(false);
            healthServer.stop(0);
            try {
                healthCheckScheduler.shutdown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
    }

    static int requiredPort(String variable, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(variable + " must be set to a port number");
        }
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException(variable + " must be between 0 and 65535");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(variable + " must be a port number: " + value, e);
        }
    }

    static final class CoordinatorRuntime {
        private final CoordinatorServer server;
        private final HealthCheckScheduler healthCheckScheduler;
        private final HttpServer healthServer;
        private final AtomicBoolean acceptingRequests;

        private CoordinatorRuntime(
                CoordinatorServer server,
                HealthCheckScheduler healthCheckScheduler,
                HttpServer healthServer,
                AtomicBoolean acceptingRequests) {
            this.server = server;
            this.healthCheckScheduler = healthCheckScheduler;
            this.healthServer = healthServer;
            this.acceptingRequests = acceptingRequests;
        }

        void awaitTermination() throws InterruptedException {
            server.awaitTermination();
        }

        int grpcPort() {
            return server.getPort();
        }

        int healthPort() {
            return healthServer.getAddress().getPort();
        }

        void shutdown() throws InterruptedException {
            acceptingRequests.set(false);
            server.shutdown();
            healthCheckScheduler.shutdown();
            healthServer.stop(0);
        }
    }
}
