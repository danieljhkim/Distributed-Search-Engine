package com.danieljhkim.dsearch.indexnode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexNodeApplicationTest {

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
}
