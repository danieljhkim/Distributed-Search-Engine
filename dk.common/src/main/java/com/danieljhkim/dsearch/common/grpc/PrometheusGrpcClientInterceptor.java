package com.danieljhkim.dsearch.common.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

public class PrometheusGrpcClientInterceptor implements ClientInterceptor {

	private static final Histogram GRPC_CLIENT_LATENCY = Histogram.build()
			.name("dsearch_grpc_client_latency_seconds")
			.help("Latency of outbound gRPC calls from this client")
			.labelNames("component", "service", "method", "status")
			.register();

	private static final Counter GRPC_CLIENT_REQUESTS = Counter.build()
			.name("dsearch_grpc_client_requests_total")
			.help("Total number of outbound gRPC calls from this client")
			.labelNames("component", "service", "method", "status")
			.register();

	/**
	 * component = logical caller, e.g. "gateway->query-node" or
	 * "gateway->index-node"
	 */
	private final String componentLabel;

	public PrometheusGrpcClientInterceptor(String componentLabel) {
		this.componentLabel = componentLabel != null ? componentLabel : "unknown";
	}

	@Override
	public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
			MethodDescriptor<ReqT, RespT> method,
			CallOptions callOptions,
			Channel next) {
		String fullMethodName = method.getFullMethodName(); // e.g. dsearch.query.QueryService/Search
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

		long startNanos = System.nanoTime();
		ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
		return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {

			@Override
			public void start(Listener<RespT> responseListener, Metadata headers) {
				Listener<RespT> timingListener = new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(
						responseListener) {

					@Override
					public void onClose(Status status, Metadata trailers) {
						long elapsedNanos = System.nanoTime() - startNanos;
						double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

						String statusLabel = status != null ? status.getCode().name() : "UNKNOWN";

						GRPC_CLIENT_LATENCY
								.labels(componentLabel, serviceName, methodName, statusLabel)
								.observe(elapsedSeconds);

						GRPC_CLIENT_REQUESTS
								.labels(componentLabel, serviceName, methodName, statusLabel)
								.inc();

						super.onClose(status, trailers);
					}
				};

				super.start(timingListener, headers);
			}
		};
	}
}