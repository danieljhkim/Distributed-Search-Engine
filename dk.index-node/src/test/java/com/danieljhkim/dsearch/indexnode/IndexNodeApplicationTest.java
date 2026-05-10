package com.danieljhkim.dsearch.indexnode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.danieljhkim.dsearch.common.config.AppConfig;
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
}
