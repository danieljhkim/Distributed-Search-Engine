package com.danieljhkim.dsearch.coordinator.grpc;

import com.danieljhkim.dsearch.coordinator.cluster.ClusterMembershipService;
import com.danieljhkim.dsearch.coordinator.cluster.ShardMap;
import com.dk.dsearch.proto.cluster.*;
import io.grpc.stub.StreamObserver;

import java.util.Comparator;
import java.util.List;

public class ClusterServiceImpl extends ClusterServiceGrpc.ClusterServiceImplBase {

    private final ClusterMembershipService membershipService;
    private final ShardMap shardMap;

    public ClusterServiceImpl(ClusterMembershipService membershipService, ShardMap shardMap) {
        this.membershipService = membershipService;
        this.shardMap = shardMap;
    }

    @Override
    public void registerNode(RegisterNodeRequest request,
                             StreamObserver<RegisterNodeResponse> responseObserver) {

        // 1) Register node in membership
        membershipService.registerNode(
                new ClusterMembershipService.ClusterNodeInfo(
                        request.getNodeId(),
                        request.getHost(),
                        request.getPort(),
                        request.getRole().name()  // e.g. "NODE_ROLE_INDEX"
                )
        );

        // 2) If this is an INDEX node, recompute shard assignments
        if (request.getRole() == NodeRole.NODE_ROLE_INDEX) {
            recomputeShardAssignmentsForIndexNodes();
        }

        RegisterNodeResponse resp = RegisterNodeResponse.newBuilder()
                .setSuccess(true)
                .build();

        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void getShardMap(GetShardMapRequest request,
                            StreamObserver<GetShardMapResponse> responseObserver) {

        GetShardMapResponse.Builder builder = GetShardMapResponse.newBuilder();

        shardMap.getShardToNodeId().forEach((shardId, nodeId) -> {
            ClusterMembershipService.ClusterNodeInfo info =
                    membershipService.getNodes().get(nodeId);
            if (info != null) {
                ShardLocation location = ShardLocation.newBuilder()
                        .setShardId(shardId)
                        .setNodeId(info.nodeId())
                        .setHost(info.host())
                        .setPort(info.port())
                        .setRole(NodeRole.valueOf(info.role()))
                        .build();
                builder.addShardLocations(location);
            }
        });

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    /**
     * Hash-based shard assignment:
     * <p>
     * For each shardId in [0, numShards):
     * - Compute index = hash(shardId) % numIndexNodes
     * - Assign shard to indexNodes[index]
     * <p>
     * This is simple, deterministic, and evenly spreads shards
     * (though it does not do consistent hashing).
     */
    private void recomputeShardAssignmentsForIndexNodes() {
        // 1) Collect all INDEX nodes, sorted by nodeId for determinism
        List<ClusterMembershipService.ClusterNodeInfo> indexNodes =
                membershipService.getNodes().values().stream()
                        .filter(info -> "NODE_ROLE_INDEX".equals(info.role()))
                        .sorted(Comparator.comparing(ClusterMembershipService.ClusterNodeInfo::nodeId))
                        .toList();

        if (indexNodes.isEmpty()) {
            // No index nodes; nothing to assign
            shardMap.clearAssignments();
            return;
        }

        // 2) Clear existing assignments
        shardMap.clearAssignments();

        int numShards = shardMap.getNumShards();
        int numIndexNodes = indexNodes.size();

        // 3) Assign each shard to one index node using hash(shardId)
        for (int shardId = 0; shardId < numShards; shardId++) {
            // hash-based index; using shardId itself here
            int idx = Math.floorMod(Integer.hashCode(shardId), numIndexNodes);
            ClusterMembershipService.ClusterNodeInfo owner = indexNodes.get(idx);
            shardMap.assignShard(String.valueOf(shardId), owner.nodeId());
        }
    }
}