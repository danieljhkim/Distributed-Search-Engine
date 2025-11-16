package com.dk.search.gateway.service;

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

    private final QueryServiceGrpc.QueryServiceBlockingStub queryStub;

    public GatewaySearchService(QueryServiceGrpc.QueryServiceBlockingStub queryStub) {
        this.queryStub = queryStub;
    }

    public SearchResponseDto search(SearchRequestDto request) {
        int topK = (request.getTopK() != null && request.getTopK() > 0)
                ? request.getTopK()
                : 10;

        QueryRequest.Builder grpcReqBuilder = QueryRequest.newBuilder()
                .setQueryString(request.getQuery())
                .setTopK(topK);

        if (request.getShardIds() != null && !request.getShardIds().isEmpty()) {
            grpcReqBuilder.addAllShardIds(request.getShardIds());
        } else {
            // simple default: shard 0
            grpcReqBuilder.addShardIds(0);
        }

        QueryResponse grpcResp = queryStub.search(grpcReqBuilder.build());

        List<SearchResponseDto.SearchHitDto> hits = new ArrayList<>();
        for (SearchHit hit : grpcResp.getHitsList()) {
            hits.add(new SearchResponseDto.SearchHitDto(hit.getDocId(), hit.getScore()));
        }

        return new SearchResponseDto(
                hits,
                grpcResp.getTotalHits(),
                grpcResp.getTookMillis()
        );
    }
}