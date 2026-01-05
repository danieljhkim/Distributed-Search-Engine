package com.danieljhkim.dsearch.coordinator.grpc;

import com.danieljhkim.dsearch.common.cluster.NodeGroup;
import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.proto.cluster.ClusterServiceGrpc;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoRequest;
import com.danieljhkim.dsearch.proto.cluster.GetClusterInfoResponse;
import com.danieljhkim.dsearch.proto.cluster.NodeInfo;
import com.danieljhkim.dsearch.proto.cluster.NodeRole;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeRequest;
import com.danieljhkim.dsearch.proto.cluster.RegisterNodeResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class ClusterServiceImpl extends ClusterServiceGrpc.ClusterServiceImplBase {

    private final ClusterMembershipService membershipService;

    public ClusterServiceImpl(ClusterMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @Override
    public void registerNode(RegisterNodeRequest request, StreamObserver<RegisterNodeResponse> responseObserver) {
        // TODO: Validate request fields
        membershipService.registerNode(
                new NodeGroup.NodeInfo(
                        request.getNodeId(),
                        request.getHost(),
                        request.getPort(),
                        request.getHealthPort(),
                        request.getRole().name(),
                        true),
                request.getRole());
        RegisterNodeResponse resp =
                RegisterNodeResponse.newBuilder().setSuccess(true).build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void getClusterInfo(GetClusterInfoRequest request, StreamObserver<GetClusterInfoResponse> responseObserver) {
        try {
            NodeRole role = request.getRole();
            NodeGroup group = membershipService.resolveGroup(role);
            if (group == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("No node group registered for role: " + role)
                        .asRuntimeException());
                return;
            }
            GetClusterInfoResponse.Builder resp = GetClusterInfoResponse.newBuilder()
                    .setComponentLabel(group.getComponentLabel())
                    .setRoutingStrategy(group.getRoutingStrategy().name());

            for (NodeGroup.NodeInfo ni : group.getAllNodes()) {
                if (!ni.isHealthy()) {
                    continue;
                }
                resp.addNodes(NodeInfo.newBuilder()
                        .setNodeId(ni.getNodeId())
                        .setHost(ni.getHost())
                        .setPort(ni.getPort())
                        .setHealthPort(ni.getHealthPort())
                        .setRole(role)
                        .build());
            }
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            e.printStackTrace();
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to get cluster info for role: " + request.getRole())
                    .withCause(e)
                    .asRuntimeException());
        }
    }
}
