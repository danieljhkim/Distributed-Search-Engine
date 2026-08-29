package com.danieljhkim.dsearch.common.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.tracing.CorrelationIdClientInterceptor;
import com.danieljhkim.dsearch.common.tracing.CorrelationIdServerInterceptor;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class GrpcInterceptorsTest {

    private static final MethodDescriptor<String, String> METHOD = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("demo.Service/Lookup")
            .setRequestMarshaller(new StringMarshaller())
            .setResponseMarshaller(new StringMarshaller())
            .build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void clientCorrelationInterceptorAddsCurrentRequestIdOnlyWhenPresent() {
        @SuppressWarnings("unchecked")
        ClientCall<String, String> delegate = mock(ClientCall.class);
        Channel next = mock(Channel.class);
        when(next.newCall(METHOD, CallOptions.DEFAULT)).thenReturn(delegate);
        CorrelationIdClientInterceptor interceptor = new CorrelationIdClientInterceptor();
        ClientCall<String, String> call = interceptor.interceptCall(METHOD, CallOptions.DEFAULT, next);
        Metadata withId = new Metadata();
        MDC.put(CorrelationIdClientInterceptor.MDC_KEY, "request-123");

        call.start(mock(ClientCall.Listener.class), withId);

        assertEquals("request-123", withId.get(CorrelationIdClientInterceptor.REQUEST_ID_HEADER));
        verify(delegate).start(any(), org.mockito.ArgumentMatchers.same(withId));

        MDC.remove(CorrelationIdClientInterceptor.MDC_KEY);
        Metadata withoutId = new Metadata();
        interceptor.interceptCall(METHOD, CallOptions.DEFAULT, next).start(mock(ClientCall.Listener.class), withoutId);
        assertNull(withoutId.get(CorrelationIdClientInterceptor.REQUEST_ID_HEADER));
    }

    @Test
    void serverCorrelationInterceptorPropagatesAndCleansUpOnCompletionCancellationAndFailure() {
        @SuppressWarnings("unchecked")
        ServerCall<String, String> call = mock(ServerCall.class);
        @SuppressWarnings("unchecked")
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        ServerCall.Listener<String> delegate = new ServerCall.Listener<>() {};
        when(next.startCall(any(), any())).thenReturn(delegate);
        Metadata headers = new Metadata();
        headers.put(CorrelationIdServerInterceptor.REQUEST_ID_HEADER, "request-456");
        CorrelationIdServerInterceptor interceptor = new CorrelationIdServerInterceptor();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, headers, next);
        assertEquals("request-456", MDC.get(CorrelationIdServerInterceptor.MDC_KEY));
        listener.onComplete();
        assertNull(MDC.get(CorrelationIdServerInterceptor.MDC_KEY));

        listener = interceptor.interceptCall(call, headers, next);
        listener.onCancel();
        assertNull(MDC.get(CorrelationIdServerInterceptor.MDC_KEY));

        when(next.startCall(any(), any())).thenThrow(new IllegalStateException("start failed"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> interceptor.interceptCall(call, headers, next));
        assertNull(MDC.get(CorrelationIdServerInterceptor.MDC_KEY));
    }

    @Test
    void serverCorrelationInterceptorGeneratesBoundedOpaqueIdForBlankHeader() {
        @SuppressWarnings("unchecked")
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        when(next.startCall(any(), any())).thenAnswer(invocation -> {
            String requestId = MDC.get(CorrelationIdServerInterceptor.MDC_KEY);
            assertNotNull(requestId);
            assertTrue(requestId.length() <= 36);
            return new ServerCall.Listener<>() {};
        });

        ServerCall.Listener<String> listener =
                new CorrelationIdServerInterceptor().interceptCall(mock(ServerCall.class), new Metadata(), next);
        listener.onComplete();
        assertNull(MDC.get(CorrelationIdServerInterceptor.MDC_KEY));
    }

    @Test
    void micrometerServerInterceptorRecordsCompletionStatusAndStableLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ServerCall<String, String> call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn(METHOD);
        @SuppressWarnings("unchecked")
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        when(next.startCall(any(), any())).thenAnswer(invocation -> new ServerCall.Listener<>() {});

        new GrpcServerMetricsInterceptor(registry).interceptCall(call, new Metadata(), next);
        ServerCall<String, String> captured = captureServerCall(call, registry);
        captured.close(Status.OK, new Metadata());
        captured.close(Status.CANCELLED, new Metadata());

        assertEquals(
                1.0,
                registry.get("dsearch.grpc.server.latency")
                        .tag("method", "demo.Service/Lookup")
                        .tag("status", "OK")
                        .timer()
                        .count());
        assertEquals(
                1.0,
                registry.get("dsearch.grpc.server.latency")
                        .tag("status", "CANCELLED")
                        .timer()
                        .count());
    }

    @Test
    void prometheusInterceptorsCompleteForSuccessCancellationAndExceptionStatuses() {
        @SuppressWarnings("unchecked")
        ServerCall<String, String> serverCall = mock(ServerCall.class);
        when(serverCall.getMethodDescriptor()).thenReturn(METHOD);
        @SuppressWarnings("unchecked")
        ServerCallHandler<String, String> serverNext = mock(ServerCallHandler.class);
        when(serverNext.startCall(any(), any())).thenAnswer(invocation -> new ServerCall.Listener<>() {});
        ServerCall<String, String> wrappedServer = capturePrometheusServerCall(serverCall, serverNext);
        wrappedServer.close(Status.OK, new Metadata());
        wrappedServer.close(Status.CANCELLED, new Metadata());

        @SuppressWarnings("unchecked")
        ClientCall<String, String> clientCall = mock(ClientCall.class);
        Channel next = mock(Channel.class);
        when(next.newCall(METHOD, CallOptions.DEFAULT)).thenReturn(clientCall);
        new PrometheusGrpcClientInterceptor(null)
                .interceptCall(METHOD, CallOptions.DEFAULT, next)
                .start(mock(ClientCall.Listener.class), new Metadata());
        @SuppressWarnings("unchecked")
        ClientCall.Listener<String> listener = captureClientListener(clientCall);
        listener.onClose(Status.OK, new Metadata());
        listener.onClose(Status.CANCELLED, new Metadata());
    }

    private static ServerCall<String, String> captureServerCall(
            ServerCall<String, String> call, SimpleMeterRegistry registry) {
        final ServerCall<String, String>[] captured = new ServerCall[1];
        ServerCallHandler<String, String> capturing = (value, headers) -> {
            captured[0] = value;
            return new ServerCall.Listener<>() {};
        };
        new GrpcServerMetricsInterceptor(registry).interceptCall(call, new Metadata(), capturing);
        return captured[0];
    }

    private static ServerCall<String, String> capturePrometheusServerCall(
            ServerCall<String, String> call, ServerCallHandler<String, String> next) {
        final ServerCall<String, String>[] captured = new ServerCall[1];
        new PrometheusGrpcServerInterceptor().interceptCall(call, new Metadata(), (value, headers) -> {
            captured[0] = value;
            return new ServerCall.Listener<>() {};
        });
        return captured[0];
    }

    @SuppressWarnings("unchecked")
    private static ClientCall.Listener<String> captureClientListener(ClientCall<String, String> delegate) {
        org.mockito.ArgumentCaptor<ClientCall.Listener<String>> captor =
                org.mockito.ArgumentCaptor.forClass(ClientCall.Listener.class);
        verify(delegate).start(captor.capture(), any());
        return captor.getValue();
    }

    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            return "";
        }
    }
}
