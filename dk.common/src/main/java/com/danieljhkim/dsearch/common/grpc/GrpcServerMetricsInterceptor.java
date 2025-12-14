package com.danieljhkim.dsearch.common.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class GrpcServerMetricsInterceptor implements ServerInterceptor {

	private final MeterRegistry registry;

	public GrpcServerMetricsInterceptor(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
			ServerCall<ReqT, RespT> call,
			Metadata headers,
			ServerCallHandler<ReqT, RespT> next) {
		String methodName = call.getMethodDescriptor().getFullMethodName();
		long start = System.nanoTime();

		ServerCall<ReqT, RespT> wrappedCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
			@Override
			public void close(Status status, Metadata trailers) {
				long end = System.nanoTime();
				Timer.builder("dsearch.grpc.server.latency")
						.tag("method", methodName)
						.tag("status", status.getCode().name())
						.register(registry)
						.record(end - start, java.util.concurrent.TimeUnit.NANOSECONDS);

				super.close(status, trailers);
			}
		};

		return next.startCall(wrappedCall, headers);
	}
}