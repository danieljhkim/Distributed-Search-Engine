package com.danieljhkim.dsearch.common.grpc;

import io.grpc.ManagedChannel;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class NodeClient<T> {
    private final String nodeId;
    private final ManagedChannel channel;
    private final String host;
    private final int healthPort;
    private T stub;
    private boolean isActive = true;

    public NodeClient(String nodeId, T stub, ManagedChannel channel, String host, int healthPort) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.stub = Objects.requireNonNull(stub, "stub must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.host = host;
        this.healthPort = healthPort;
    }
}
