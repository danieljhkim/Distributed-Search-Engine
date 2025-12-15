package com.danieljhkim.dsearch.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.danieljhkim.dsearch.common.exception.ParseGoneWrongException;
import com.danieljhkim.dsearch.common.exception.ShardNotFoundException;

import io.grpc.Status;

class GlobalExceptionInterceptorTest {

	private final GlobalExceptionInterceptor interceptor = new GlobalExceptionInterceptor();

	@Test
	void testMapExceptionToStatus_ShardNotFoundException() {
		ShardNotFoundException ex = new ShardNotFoundException("shard-123");
		Status status = invokeMapExceptionToStatus(ex);
		assertEquals(Status.Code.NOT_FOUND, status.getCode());
		assert status.getDescription().contains("shard-123");
	}

	@Test
	void testMapExceptionToStatus_ParseGoneWrongException() {
		ParseGoneWrongException ex = new ParseGoneWrongException("Parse failed");
		Status status = invokeMapExceptionToStatus(ex);
		assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
		assert status.getDescription().contains("Parse failed");
	}

	@Test
	void testMapExceptionToStatus_IllegalArgumentException() {
		java.lang.IllegalArgumentException ex = new java.lang.IllegalArgumentException("Invalid argument");
		Status status = invokeMapExceptionToStatus(ex);
		assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
		assert status.getDescription().contains("Invalid argument");
	}

	@Test
	void testMapExceptionToStatus_GenericException() {
		RuntimeException ex = new RuntimeException("Generic error");
		Status status = invokeMapExceptionToStatus(ex);
		assertEquals(Status.Code.INTERNAL, status.getCode());
	}

	// Helper method to access private method via reflection
	private Status invokeMapExceptionToStatus(Throwable t) {
		try {
			java.lang.reflect.Method method = GlobalExceptionInterceptor.class.getDeclaredMethod("mapExceptionToStatus",
					Throwable.class);
			method.setAccessible(true);
			return (Status) method.invoke(interceptor, t);
		} catch (Exception e) {
			throw new RuntimeException("Failed to invoke mapExceptionToStatus", e);
		}
	}
}
