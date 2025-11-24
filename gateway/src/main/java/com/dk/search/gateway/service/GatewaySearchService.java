package com.dk.search.gateway.service;

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

    public GatewaySearchService(
            NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager) {
        this.qnClientManager = qnClientManager;
    }

    public SearchResponseDto search(SearchRequestDto request) {
        QueryServiceGrpc.QueryServiceBlockingStub queryStub = qnClientManager.nextClient();
        QueryRequest.Builder grpcReqBuilder = QueryRequest.newBuilder()
                .setQueryString(request.getQuery())
                .setTopK(request.getTopK())
                .setPage(request.getPage())
                .setSize(request.getPageSize())
                .setShardId(request.getShardId());

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

}