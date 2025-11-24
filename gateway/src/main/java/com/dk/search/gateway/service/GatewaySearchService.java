package com.dk.search.gateway.service;

import com.dk.search.common.config.AppConfig;
import com.dk.search.common.grpc.NodeClientManager;
import com.dk.search.gateway.api.dto.SearchRequestDto;
import com.dk.search.gateway.api.dto.SearchResponseDto;
import com.dk.search.proto.query.QueryRequest;
import com.dk.search.proto.query.QueryResponse;
import com.dk.search.proto.query.QueryServiceGrpc;
import com.dk.search.proto.query.SearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GatewaySearchService {

    private final NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager;
    private final AppConfig.ClusterConfig clusterConfig;
    private final List<String> shardIds;

    public GatewaySearchService(
            NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager, AppConfig appConfig) {
        this.qnClientManager = qnClientManager;
        this.clusterConfig = appConfig.getCluster();
        this.shardIds = new ArrayList<>();
        for (AppConfig.IndexShardConfig shard : clusterConfig.getIndexShards()) {
            this.shardIds.add(shard.getId());
        }
    }

    public SearchResponseDto search(SearchRequestDto request) {
        QueryServiceGrpc.QueryServiceBlockingStub queryStub = qnClientManager.nextClient();
        QueryRequest.Builder grpcReqBuilder = QueryRequest.newBuilder()
                .setQueryString(request.getQuery())
                .setTopK(request.getTopK())
                .setPage(request.getPage())
                .setSize(request.getPageSize());

        grpcReqBuilder.addAllShardIds(getValidShardIds(request.getShardIds()));
        QueryResponse grpcResp = queryStub.search(grpcReqBuilder.build());

        List<SearchResponseDto.SearchHitDto> hits = new ArrayList<>();
        for (SearchHit hit : grpcResp.getHitsList()) {
            hits.add(new SearchResponseDto.SearchHitDto(hit.getDocId(), hit.getScore()));
        }

        return new SearchResponseDto(
                hits,
                grpcResp.getTotalHits(),
                grpcResp.getTookMillis(),
                grpcResp.getPage()
        );
    }

    private List<String> getValidShardIds(List<String> requestedShardIds) {
        if (requestedShardIds == null || requestedShardIds.isEmpty()) {
            return this.shardIds;
        }
        for (String shardId : requestedShardIds) {
            if (!this.shardIds.contains(shardId)) {
                throw new IllegalArgumentException("Invalid shard ID: " + shardId);
            }
        }
        return shardIds;
    }
}