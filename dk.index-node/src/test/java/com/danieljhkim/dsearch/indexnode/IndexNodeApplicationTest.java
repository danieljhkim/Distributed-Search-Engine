package com.danieljhkim.dsearch.indexnode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
