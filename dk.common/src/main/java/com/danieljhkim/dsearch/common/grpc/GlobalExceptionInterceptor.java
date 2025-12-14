package com.danieljhkim.dsearch.common.grpc;

import java.io.UncheckedIOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.danieljhkim.dsearch.common.exception.IndexInitializationException;
import com.danieljhkim.dsearch.common.exception.IndexOperationException;
import com.danieljhkim.dsearch.common.exception.InvalidIndexStateException;
import com.danieljhkim.dsearch.common.exception.ParseGoneWrongException;
import com.danieljhkim.dsearch.common.exception.ServiceException;
import com.danieljhkim.dsearch.common.exception.ShardNotFoundException;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

public class GlobalExceptionInterceptor implements ServerInterceptor {

	private static final Logger LOGGER = Logger.getLogger(GlobalExceptionInterceptor.class.getName());

	@Override
	public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
			ServerCall<ReqT, RespT> call,
			Metadata headers,
			ServerCallHandler<ReqT, RespT> next) {

		ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);

		return new SimpleForwardingServerCallListener<ReqT>(delegate) {

			@Override
			public void onHalfClose() {
				try {
					super.onHalfClose();
				} catch (Throwable t) {
					// Map to Status + log centrally
					Status status = mapExceptionToStatus(t);

					if (status.getCode() == Status.Code.INTERNAL) {
						LOGGER.log(Level.SEVERE, "Unhandled exception in gRPC call", t);
					} else {
						LOGGER.log(Level.WARNING, "Handled gRPC exception: " + status, t);
					}

					call.close(status, new Metadata());
				}
			}
		};
	}

	private Status mapExceptionToStatus(Throwable t) {

		if (t instanceof UncheckedIOException uioe && uioe.getCause() != null) {
			t = uioe.getCause();
		}

		// ----- Custom domain exceptions -----

		if (t instanceof ShardNotFoundException e) {
			return Status.NOT_FOUND
					.withDescription("Shard not found: " + e.getShardId());

		} else if (t instanceof ParseGoneWrongException e) {
			return Status.INVALID_ARGUMENT
					.withDescription(e.getMessage());

		} else if (t instanceof InvalidIndexStateException e) {
			// e.g., shard not ready, index not fully initialized
			return Status.FAILED_PRECONDITION
					.withDescription(e.getMessage());

		} else if (t instanceof IndexInitializationException e) {
			return Status.FAILED_PRECONDITION
					.withDescription("Index initialization failed: " + e.getMessage());

		} else if (t instanceof IndexOperationException e) {
			// I/O or write failures – could also choose UNAVAILABLE
			return Status.INTERNAL
					.withDescription("Index operation failed: " + e.getMessage());
		}

		// Any other IndexServiceException
		if (t instanceof ServiceException e) {
			return Status.INTERNAL
					.withDescription(e.getMessage());
		}

		// I/O wrapped in UncheckedIOException (if not handled above)
		if (t instanceof UncheckedIOException e) {
			return Status.INTERNAL
					.withDescription("I/O error: " + e.getMessage());
		}

		// Generic fallback → INTERNAL
		return Status.INTERNAL
				.withDescription("Internal server error");
	}
}