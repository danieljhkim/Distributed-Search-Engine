package com.danieljhkim.dsearch.indexnode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.health.HealthHttpServer;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import com.danieljhkim.dsearch.indexnode.server.IndexNodeServer;
import com.danieljhkim.dsearch.ml.embedding.TextEmbedder;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexNodeApplicationTest {

    private static final TextEmbedder FAKE_EMBEDDER = ignored -> new float[] {1.0f, 0.0f, 0.0f};

    @TempDir
    Path tempDir;

    @Test
    void testApplicationClassExists() {
        assertNotNull(IndexNodeApplication.class);
    }

    @Test
    void testMainMethodExists() {
        // Verify main method exists and is accessible
        assertDoesNotThrow(() -> {
            java.lang.reflect.Method mainMethod = IndexNodeApplication.class.getMethod("main", String[].class);
            assertNotNull(mainMethod);
        });
    }

    @Test
    void readinessRequiresGrpcServerAndStaysDownDuringShutdown() throws Exception {
        AtomicBoolean acceptingRequests = new AtomicBoolean();
        AtomicReference<IndexManager> indexManagerReference = new AtomicReference<>();
        AtomicReference<HealthHttpServer.Readiness> startupReadiness =
                new AtomicReference<>(HealthHttpServer.Readiness.notReady("index_initializing"));
        try (IndexManager manager =
                new IndexManager(tempDir.resolve("readiness"), 1, Duration.ofHours(1), null, FAKE_EMBEDDER, 0)) {
            indexManagerReference.set(manager);
            IndexNodeServer indexNodeServer = new IndexNodeServer(0, manager, localConfig());
            HttpServer healthServer = HealthHttpServer.start(
                    0,
                    "index-node",
                    () -> IndexNodeApplication.readiness(acceptingRequests, indexManagerReference, startupReadiness));
            try {
                int healthPort = healthServer.getAddress().getPort();
                assertEquals(503, readinessStatus(healthPort));

                indexNodeServer.startAsync();
                acceptingRequests.set(true);
                assertEquals(200, readinessStatus(healthPort));

                acceptingRequests.set(false);
                indexNodeServer.shutdown();
                assertEquals(503, readinessStatus(healthPort));
                assertEquals(200, livenessStatus(healthPort));
            } finally {
                healthServer.stop(0);
            }
        }
    }

    @Test
    void resolvesIndexingConfigFromApplicationConfig() {
        AppConfig appConfig = new AppConfig();
        AppConfig.IndexingConfig indexingConfig = new AppConfig.IndexingConfig();
        indexingConfig.setMaxBufferedOpsPerShard(25);
        indexingConfig.setMaxFlushIntervalSeconds(9);
        appConfig.setIndexing(indexingConfig);

        IndexNodeApplication.IndexingRuntimeConfig runtimeConfig =
                IndexNodeApplication.resolveIndexingConfig(appConfig, Map.of());

        assertEquals(25, runtimeConfig.maxBufferedOpsPerShard());
        assertEquals(Duration.ofSeconds(9), runtimeConfig.maxFlushInterval());
    }

    @Test
    void environmentOverridesIndexingConfig() {
        AppConfig appConfig = new AppConfig();
        AppConfig.IndexingConfig indexingConfig = new AppConfig.IndexingConfig();
        indexingConfig.setMaxBufferedOpsPerShard(25);
        indexingConfig.setMaxFlushIntervalSeconds(9);
        appConfig.setIndexing(indexingConfig);

        IndexNodeApplication.IndexingRuntimeConfig runtimeConfig = IndexNodeApplication.resolveIndexingConfig(
                appConfig,
                Map.of("INDEX_NODE_MAX_BUFFERED_OPS_PER_SHARD", "7", "INDEX_NODE_MAX_FLUSH_INTERVAL_SECONDS", "3"));

        assertEquals(7, runtimeConfig.maxBufferedOpsPerShard());
        assertEquals(Duration.ofSeconds(3), runtimeConfig.maxFlushInterval());
    }

    @Test
    void missingOrBlankEnvironmentUsesConfigAndMissingIndexingUsesDefaults() {
        AppConfig configured = new AppConfig();
        AppConfig.IndexingConfig indexing = new AppConfig.IndexingConfig();
        indexing.setMaxBufferedOpsPerShard(11);
        indexing.setMaxFlushIntervalSeconds(13);
        configured.setIndexing(indexing);

        IndexNodeApplication.IndexingRuntimeConfig configuredResult = IndexNodeApplication.resolveIndexingConfig(
                configured,
                Map.of(
                        "INDEX_NODE_MAX_BUFFERED_OPS_PER_SHARD", " ",
                        "INDEX_NODE_MAX_FLUSH_INTERVAL_SECONDS", ""));
        assertEquals(11, configuredResult.maxBufferedOpsPerShard());
        assertEquals(Duration.ofSeconds(13), configuredResult.maxFlushInterval());

        IndexNodeApplication.IndexingRuntimeConfig defaultResult =
                IndexNodeApplication.resolveIndexingConfig(new AppConfig(), Map.of());
        assertEquals(IndexManager.DEFAULT_MAX_BUFFERED_OPS_PER_SHARD, defaultResult.maxBufferedOpsPerShard());
        assertEquals(IndexManager.DEFAULT_MAX_FLUSH_INTERVAL, defaultResult.maxFlushInterval());
    }

    @Test
    void nonPositiveEnvironmentValuesAreRejected() {
        AppConfig appConfig = new AppConfig();
        assertThrows(
                IllegalArgumentException.class,
                () -> IndexNodeApplication.resolveIndexingConfig(
                        appConfig, Map.of("INDEX_NODE_MAX_BUFFERED_OPS_PER_SHARD", "0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> IndexNodeApplication.resolveIndexingConfig(
                        appConfig, Map.of("INDEX_NODE_MAX_FLUSH_INTERVAL_SECONDS", "-1")));
        assertThrows(
                NumberFormatException.class,
                () -> IndexNodeApplication.resolveIndexingConfig(
                        appConfig, Map.of("INDEX_NODE_MAX_BUFFERED_OPS_PER_SHARD", "not-a-number")));
    }

    private static int readinessStatus(int healthPort) throws Exception {
        return status(healthPort, "/readyz");
    }

    private static int livenessStatus(int healthPort) throws Exception {
        return status(healthPort, "/livez");
    }

    private static int status(int healthPort, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + healthPort + path))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static AppConfig localConfig() {
        AppConfig config = new AppConfig();
        config.getGrpcSecurity().setProfile("local");
        return config;
    }
}
