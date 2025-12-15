package com.danieljhkim.dsearch.coordinator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class CoordinatorApplicationTest {

	@Test
	void testApplicationClassExists() {
		assertNotNull(CoordinatorApplication.class);
	}

	@Test
	void testMainMethodExists() {
		// Verify main method exists and is accessible
		assertDoesNotThrow(() -> {
			java.lang.reflect.Method mainMethod = CoordinatorApplication.class.getMethod("main", String[].class);
			assertNotNull(mainMethod);
		});
	}
}
