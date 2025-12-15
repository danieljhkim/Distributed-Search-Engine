package com.danieljhkim.dsearch.coordinator;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.coordinator.scheduler.HealthCheckScheduler;
import com.danieljhkim.dsearch.coordinator.server.CoordinatorServer;
import com.sun.net.httpserver.HttpServer;

public class CoordinatorApplication {

	private static final Logger LOGGER = Logger.getLogger(CoordinatorApplication.class.getName());

	public static void main(String[] args) throws IOException, InterruptedException {

		int port = Integer.parseInt(System.getenv("COORDINATOR_PORT"));
		int healthPort = Integer.parseInt(System.getenv("COORDINATOR_HEALTH_PORT"));
		AppConfig appConfig = ConfigLoader.load();
		ClusterMembershipService membershipService = new ClusterMembershipService(appConfig);
		CoordinatorServer server = new CoordinatorServer(port, membershipService);
		HealthCheckScheduler healthCheckScheduler = new HealthCheckScheduler(membershipService, appConfig);
		HttpServer healthServer = HealthHttpServer.start(healthPort, "coordinator-node");

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOGGER.info("Shutting down Coordinator gRPC server...");
			try {
				server.shutdown();
				healthCheckScheduler.shutdown();
				healthServer.stop(0);
			} catch (InterruptedException e) {
				LOGGER.log(Level.SEVERE, "Interrupted during coordinator shutdown", e);
				Thread.currentThread().interrupt();
			}
		}));

		LOGGER.info(() -> "Coordinator gRPC server started on port " + port);
		healthCheckScheduler.start();
		server.start();
	}
}
