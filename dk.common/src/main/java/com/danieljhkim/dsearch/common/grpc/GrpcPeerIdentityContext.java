package com.danieljhkim.dsearch.common.grpc;

import io.grpc.Context;
import java.util.Objects;

/** Request context populated only by the transport identity interceptor. */
public final class GrpcPeerIdentityContext {

    private static final Context.Key<GrpcPeerIdentity> PEER_IDENTITY = Context.key("dsearch-peer-identity");

    private GrpcPeerIdentityContext() {}

    public static GrpcPeerIdentity current() {
        return PEER_IDENTITY.get();
    }

    public static void runAs(GrpcPeerIdentity identity, Runnable action) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Context.current().withValue(PEER_IDENTITY, identity).run(action);
    }

    static Context withIdentity(GrpcPeerIdentity identity) {
        return Context.current().withValue(PEER_IDENTITY, identity);
    }
}
