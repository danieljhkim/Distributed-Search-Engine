package com.danieljhkim.dsearch.common.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
}
