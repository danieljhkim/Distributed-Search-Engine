package com.danieljhkim.dsearch.common.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/** Converts the transport-authenticated client certificate into an authorization context. */
public final class GrpcPeerIdentityInterceptor implements ServerInterceptor {

    private final boolean localPlaintext;

    public GrpcPeerIdentityInterceptor(GrpcTransportSecurity transportSecurity) {
        this.localPlaintext = transportSecurity.isLocalPlaintext();
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        GrpcPeerIdentity identity;
        if (localPlaintext) {
            identity = GrpcPeerIdentity.admin("explicit-local-profile");
        } else {
            SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
            try {
                identity = GrpcPeerIdentity.fromSession(session);
            } catch (SSLPeerUnverifiedException | IllegalArgumentException e) {
                call.close(Status.UNAUTHENTICATED.withDescription(e.getMessage()), new Metadata());
                return new ServerCall.Listener<>() {};
            }
        }
        Context context = GrpcPeerIdentityContext.withIdentity(identity);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
