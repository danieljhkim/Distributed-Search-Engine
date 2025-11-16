package com.dk.search.indexnode.grpc;

import com.dk.search.common.exception.ShardNotFoundException;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.*;

import java.io.UncheckedIOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GlobalExceptionInterceptor implements ServerInterceptor {

    private static final Logger LOGGER = Logger.getLogger(GlobalExceptionInterceptor.class.getName());

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        ServerCall.Listener<ReqT> delegateListener = next.startCall(call, headers);

        return new SimpleForwardingServerCallListener<ReqT>(delegateListener) {

            @Override
            public void onHalfClose() {
                try {
                    // Let the actual service implementation run
                    super.onHalfClose();
                } catch (UncheckedIOException e) {
                    LOGGER.log(Level.SEVERE, "I/O error in gRPC call", e);
                    call.close(
                            Status.INTERNAL
                                    .withDescription("I/O error while processing request")
                                    .augmentDescription(e.getCause() != null ? e.getCause().getMessage() : null),
                            new Metadata()
                    );
                } catch (IllegalArgumentException e) {
                    // Validation errors -> INVALID_ARGUMENT
                    LOGGER.log(Level.WARNING, "Invalid argument in gRPC call", e);
                    call.close(
                            Status.INVALID_ARGUMENT.withDescription(e.getMessage()),
                            new Metadata()
                    );

                } catch (ShardNotFoundException e) {
                    // Example domain exception -> NOT_FOUND
                    LOGGER.log(Level.WARNING, "Shard not found", e);
                    call.close(
                            Status.NOT_FOUND.withDescription("Shard not found: " + e.getMessage()),
                            new Metadata()
                    );
                } catch (Exception e) {
                    // Catch-all -> INTERNAL
                    LOGGER.log(Level.SEVERE, "Unhandled exception in gRPC call", e);
                    call.close(
                            Status.INTERNAL.withDescription("Internal server error"),
                            new Metadata()
                    );
                }
            }
        };
    }
}