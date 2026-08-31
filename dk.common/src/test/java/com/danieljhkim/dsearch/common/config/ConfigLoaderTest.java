package com.danieljhkim.dsearch.common.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    @Test
    void testGetConfigFilePath_DefaultValue() {
        String originalValue = System.getenv("APP_CONFIG_PATH");
        try {
            // Clear the environment variable
            if (originalValue != null) {
                // Note: We can't actually unset env vars in Java, so we test the default
                // behavior
            }
            String path = ConfigLoader.getConfigFilePath();
            assertNotNull(path);
            assertTrue(path.equals("app-config.yaml") || path.equals(originalValue));
        } finally {
            // Restore original value if needed
        }
    }

    @Test
    void testGetConfigFilePath_WithEnvVariable() {
        String originalValue = System.getenv("APP_CONFIG_PATH");
        try {
            // Test that it returns the env var if set
            // Note: We can't set env vars in Java, so we just verify the method works
            String path = ConfigLoader.getConfigFilePath();
            assertNotNull(path);
            assertTrue(path.length() > 0);
        } finally {
            // Restore original value if needed
        }
    }

    @Test
    void testLoad_DefaultConfig() throws IOException {
        AppConfig config = assertDoesNotThrow(() -> ConfigLoader.load());
        assertNotNull(config);
        assertNotNull(config.getIndexing());
        assertEquals(100, config.getIndexing().getMaxBufferedOpsPerShard());
        assertEquals(5, config.getIndexing().getMaxFlushIntervalSeconds());
        assertEquals(1, config.getMl().getModels().getTextEmbedding().getPredictorPoolSize());
        assertEquals(384, config.getMl().getModels().getTextEmbedding().getDimension());
        assertEquals("standard", config.getIndexing().getAnalyzer());
    }

    @Test
    void testLoad_SpecificConfig() throws IOException {
        AppConfig config = assertDoesNotThrow(() -> ConfigLoader.load("app-config.yaml"));
        assertNotNull(config);
    }

    @Test
    void testLoad_NonExistentConfig() {
        assertThrows(RuntimeException.class, () -> ConfigLoader.load("non-existent-config.yaml"));
    }

    @Test
    void runtimeNodeCountsLimitConfiguredIndexAndQueryNodesTogether() throws IOException {
        AppConfig config = ConfigLoader.load("app-config.yaml");

        ConfigLoader.applyRuntimeNodeCounts(config, "1", "1");

        assertEquals(
                List.of("0"),
                config.getIndexNodes().getNodes().stream()
                        .map(AppConfig.NodeConfig::getId)
                        .toList());
        assertEquals(
                List.of("0"),
                config.getQueryNodes().getNodes().stream()
                        .map(AppConfig.NodeConfig::getId)
                        .toList());
        assertEquals(1, config.getCoordinatorNodes().getNodes().size());
    }

    @Test
    void runtimeNodeCountsRejectNodesThatCannotBeDeployed() throws IOException {
        AppConfig config = ConfigLoader.load("app-config.yaml");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> ConfigLoader.applyRuntimeNodeCounts(config, "3", "1"));

        assertTrue(exception.getMessage().contains("N_INDEX_NODES requests 3 nodes"));
    }
}
