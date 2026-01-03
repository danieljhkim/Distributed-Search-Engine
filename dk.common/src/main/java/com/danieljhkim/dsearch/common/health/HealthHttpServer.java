package com.danieljhkim.dsearch.common.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public final class HealthHttpServer {

    private static final Logger LOGGER = Logger.getLogger(HealthHttpServer.class.getName());

    private HealthHttpServer() {}

    public static HttpServer start(int port, String serviceName) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new HealthHandler(serviceName));
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, serviceName + "-health-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
        LOGGER.info(() -> "Health HTTP server for " + serviceName + " started on port " + port);
        return server;
    }

    private static class HealthHandler implements HttpHandler {

        private final String serviceName;

        private HealthHandler(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                exchange.close();
                return;
            }
            String body = String.format(
                    "{\"status\":\"UP\",\"service\":\"%s\",\"timestamp\":\"%s\"}",
                    serviceName, Instant.now().toString());
            byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
