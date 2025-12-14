package com.danieljhkim.dsearch.indexnode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import com.danieljhkim.dsearch.indexnode.server.IndexNodeServer;
import com.sun.net.httpserver.HttpServer;

public class IndexNodeApplication {

	private static final Logger LOGGER = Logger.getLogger(IndexNodeApplication.class.getName());

	public static void main(String[] args) throws IOException, InterruptedException {

		int grpcPort = Integer.parseInt(System.getenv("INDEX_NODE_PORT"));
		int healthPort = Integer.parseInt(System.getenv("INDEX_NODE_HEALTH_PORT"));
		String baseDirStr = System.getenv("INDEX_NODE_BASE_DIR");
		Path baseDir = Path.of(baseDirStr);

		IndexManager indexManager = new IndexManager(baseDir);
		IndexNodeServer indexNodeServer = new IndexNodeServer(grpcPort, indexManager);
		HttpServer healthServer = HealthHttpServer.start(healthPort, "index-node");

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOGGER.info("Shutting down IndexNode gRPC server...");
			try {
				indexManager.close();
				indexNodeServer.shutdown();
				healthServer.stop(0);

			} catch (InterruptedException e) {
				LOGGER.log(Level.SEVERE, "Error during shutdown", e);
				Thread.currentThread().interrupt();
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, "Error closing IndexManager", e);
			}
		}));

		LOGGER.info(() -> "IndexNode gRPC server started on port " + grpcPort);
		LOGGER.info(() -> "IndexNode health endpoint on port " + healthPort + " at /health");

		indexNodeServer.start();
	}
}
