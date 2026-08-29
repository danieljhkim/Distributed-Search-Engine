package com.danieljhkim.dsearch.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.exception.IndexInitializationException;
import com.danieljhkim.dsearch.common.exception.IndexOperationException;
import com.danieljhkim.dsearch.common.exception.InvalidIndexStateException;
import com.danieljhkim.dsearch.common.exception.ParseGoneWrongException;
import com.danieljhkim.dsearch.common.exception.ServiceException;
import com.danieljhkim.dsearch.common.exception.ShardNotFoundException;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

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

    @Test
    void testMapExceptionToStatus_CoversDomainAndWrappedFailures() {
        assertEquals(
                Status.Code.FAILED_PRECONDITION,
                invokeMapExceptionToStatus(new InvalidIndexStateException("not ready"))
                        .getCode());
        assertEquals(
                Status.Code.FAILED_PRECONDITION,
                invokeMapExceptionToStatus(new IndexInitializationException("open failed"))
                        .getCode());
        assertEquals(
                Status.Code.INTERNAL,
                invokeMapExceptionToStatus(new IndexOperationException("write failed"))
                        .getCode());
        assertEquals(
                Status.Code.INTERNAL,
                invokeMapExceptionToStatus(new ServiceException("service failed") {})
                        .getCode());
        assertEquals(
                Status.Code.INTERNAL,
                invokeMapExceptionToStatus(new UncheckedIOException(new IOException("disk")))
                        .getCode());
    }

    @Test
    void interceptCallClosesTheCallWithMappedStatusWhenHandlerThrows() {
        @SuppressWarnings("unchecked")
        ServerCall<String, String> call = mock(ServerCall.class);
        @SuppressWarnings("unchecked")
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        when(next.startCall(any(), any())).thenReturn(new ServerCall.Listener<>() {
            @Override
            public void onHalfClose() {
                throw new ParseGoneWrongException("bad query");
            }
        });

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, new Metadata(), next);
        listener.onHalfClose();

        verify(call)
                .close(
                        org.mockito.ArgumentMatchers.argThat(status -> status.getCode() == Status.Code.INVALID_ARGUMENT
                                && "bad query".equals(status.getDescription())),
                        any());
    }

    // Helper method to access private method via reflection
    private Status invokeMapExceptionToStatus(Throwable t) {
        try {
            java.lang.reflect.Method method =
                    GlobalExceptionInterceptor.class.getDeclaredMethod("mapExceptionToStatus", Throwable.class);
            method.setAccessible(true);
            return (Status) method.invoke(interceptor, t);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke mapExceptionToStatus", e);
        }
    }
}
