package com.danieljhkim.dsearch.common.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HealthHttpServerTest {

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void healthEndpointReportsServiceAndTimestampOnEphemeralPort() throws Exception {
        HttpServer server = HealthHttpServer.start(0, "index-node");
        try {
            HttpResponse<String> response = client.send(
                    request(server, "/health", "GET"), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, response.statusCode());
            assertEquals(
                    "application/json",
                    response.headers().firstValue("Content-type").orElseThrow());
            assertTrue(
                    response.body()
                            .matches(
                                    "\\{\\\"status\\\":\\\"UP\\\",\\\"service\\\":\\\"index-node\\\",\\\"timestamp\\\":\\\".+\\\"\\}"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void healthEndpointRejectsNonGetRequests() throws Exception {
        HttpServer server = HealthHttpServer.start(0, "query-node");
        try {
            HttpResponse<String> response = client.send(
                    request(server, "/health", "POST"), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(HttpURLConnection.HTTP_BAD_METHOD, response.statusCode());
            assertTrue(response.body().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private static HttpRequest request(HttpServer server, String path, String method) {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        return HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }
}
