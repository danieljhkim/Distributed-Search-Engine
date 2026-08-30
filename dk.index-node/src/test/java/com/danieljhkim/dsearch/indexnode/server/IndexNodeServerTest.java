package com.danieljhkim.dsearch.indexnode.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.prometheus.client.exporter.HTTPServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexNodeServerTest {

    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};

    @TempDir
    Path tempDir;

    @Test
    void startsGrpcAndMetricsOnEphemeralPortsAndStopsBoth() throws Exception {
        try (IndexManager manager = manager("start-stop")) {
            IndexNodeServer node = new IndexNodeServer(0, 0, manager, localConfig());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread runner = new Thread(() -> {
                try {
                    node.start();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            runner.start();

            awaitPort(() -> node.grpcPort());
            awaitPort(() -> node.metricsPort());

            ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", node.grpcPort())
                    .usePlaintext()
                    .build();
            try {
                IndexDocumentResponse response = IndexServiceGrpc.newBlockingStub(channel)
                        .indexDocument(IndexDocumentRequest.newBuilder()
                                .setPartitionId("0")
                                .setDocument(Document.newBuilder()
                                        .setId("doc-1")
                                        .addFields(Field.newBuilder()
                                                .setName("content")
                                                .setValue("server content"))
                                        .build())
                                .build());
                assertTrue(response.getSuccess());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }

            HttpResponse<String> metrics = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:" + node.metricsPort() + "/metrics"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("jvm_"));

            node.shutdown();
            runner.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(runner.isAlive());
            assertNullFailure(failure.get());
            assertEquals(-1, node.metricsPort());
        }
    }

    @Test
    void startupFailureRollsBackMetricsAndPortsCanBeReused() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0);
                IndexManager firstManager = manager("failed-start")) {
            int grpcPort = occupied.getLocalPort();
            int metricsPort = freePort();
            IndexNodeServer failed = new IndexNodeServer(grpcPort, metricsPort, firstManager, localConfig());

            assertThrows(IOException.class, failed::start);

            try (HTTPServer replacementMetrics = new HTTPServer(metricsPort)) {
                assertNotNull(replacementMetrics);
            }

            occupied.close();
            try (IndexManager secondManager = manager("reusable")) {
                IndexNodeServer reusable = new IndexNodeServer(grpcPort, metricsPort, secondManager, localConfig());
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread runner = new Thread(() -> {
                    try {
                        reusable.start();
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                });
                runner.start();
                awaitPort(() -> reusable.grpcPort());
                reusable.shutdown();
                runner.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(runner.isAlive());
                assertNullFailure(failure.get());
            }
        }
    }

    private IndexManager manager(String name) {
        return new IndexManager(tempDir.resolve(name), 10, Duration.ofHours(1), null, FAKE_EMBEDDER);
    }

    private static AppConfig localConfig() {
        AppConfig config = new AppConfig();
        config.getGrpcSecurity().setProfile("local");
        return config;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitPort(PortSupplier supplier) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (supplier.port() <= 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(supplier.port() > 0, "server did not bind an ephemeral port");
    }

    private static void assertNullFailure(Throwable failure) {
        assertNull(failure, failure == null ? "" : failure.toString());
    }

    @FunctionalInterface
    private interface PortSupplier {
        int port();
    }
}
