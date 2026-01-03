package com.danieljhkim.dsearch.common.tracing;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.slf4j.MDC;

public class CorrelationIdServerInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> REQUEST_ID_HEADER =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    public static final String MDC_KEY = "requestId";

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String requestId = headers.get(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = java.util.UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        try {
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                    next.startCall(call, headers)) {

                @Override
                public void onComplete() {
                    try {
                        super.onComplete();
                    } finally {
                        MDC.remove(MDC_KEY);
                    }
                }

                @Override
                public void onCancel() {
                    try {
                        super.onCancel();
                    } finally {
                        MDC.remove(MDC_KEY);
                    }
                }
            };
        } catch (RuntimeException e) {
            MDC.remove(MDC_KEY);
            throw e;
        }
    }
}
