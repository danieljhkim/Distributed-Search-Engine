package com.danieljhkim.dsearch.indexnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class IndexNodeHealthEndpointTest {

    @Test
    void livenessStaysAvailableWhileReadinessReportsDependencyFailure() throws Exception {
        HttpServer server = HealthHttpServer.start(
                0, "index-node", () -> HealthHttpServer.Readiness.notReady("disk_space_below_threshold"));
        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/health"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"UP\""));
            assertTrue(health.body().contains("\"service\":\"index-node\""));

            HttpResponse<String> liveness = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/livez"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, liveness.statusCode());

            HttpResponse<String> readiness = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/readyz"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, readiness.statusCode());
            assertTrue(readiness.body().contains("\"reason\":\"disk_space_below_threshold\""));

            HttpResponse<String> methodNotAllowed = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/health"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, methodNotAllowed.statusCode());
        } finally {
            server.stop(0);
        }
    }
}
