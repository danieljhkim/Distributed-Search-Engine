package com.danieljhkim.dsearch.common.tracing;

import io.grpc.*;
import org.slf4j.MDC;

public class CorrelationIdClientInterceptor implements ClientInterceptor {

    public static final Metadata.Key<String> REQUEST_ID_HEADER =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    public static final String MDC_KEY = "requestId";

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String requestId = MDC.get(MDC_KEY);
                if (requestId != null) {
                    headers.put(REQUEST_ID_HEADER, requestId);
                }
                super.start(responseListener, headers);
            }
        };
    }
}