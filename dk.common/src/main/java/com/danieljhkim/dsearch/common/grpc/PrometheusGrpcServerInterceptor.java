package com.danieljhkim.dsearch.common.grpc;

import io.grpc.*;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

public class PrometheusGrpcServerInterceptor implements ServerInterceptor {

    private static final Histogram GRPC_SERVER_LATENCY = Histogram.build()
            .name("dsearch_grpc_server_latency_seconds")
            .help("Latency of gRPC calls handled by this server")
            .labelNames("service", "method", "status")
            .register();

    private static final Counter GRPC_SERVER_REQUESTS = Counter.build()
            .name("dsearch_grpc_server_requests_total")
            .help("Total number of gRPC requests handled by this server")
            .labelNames("service", "method", "status")
            .register();

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        String fullMethodName = call.getMethodDescriptor().getFullMethodName();
        String serviceName;
        String methodName;
        int slashIdx = fullMethodName.lastIndexOf('/');
        if (slashIdx > 0) {
            serviceName = fullMethodName.substring(0, slashIdx);
            methodName = fullMethodName.substring(slashIdx + 1);
        } else {
            serviceName = "unknown";
            methodName = fullMethodName;
        }

        final long startNanos = System.nanoTime();

        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long elapsedNanos = System.nanoTime() - startNanos;
                double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
                String statusLabel = status != null ? status.getCode().name() : "UNKNOWN";
                GRPC_SERVER_LATENCY
                        .labels(serviceName, methodName, statusLabel)
                        .observe(elapsedSeconds);

                GRPC_SERVER_REQUESTS
                        .labels(serviceName, methodName, statusLabel)
                        .inc();

                super.close(status, trailers);
            }
        }, headers);
    }
}