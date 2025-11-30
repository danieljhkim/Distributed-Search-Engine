package com.dk.dsearch.gateway.service;

import com.dk.dsearch.common.grpc.NodeClientManager;
import com.dk.dsearch.gateway.api.dto.SearchRequestDto;
import com.dk.dsearch.gateway.api.dto.SearchResponseDto;
import com.dk.dsearch.gateway.mapper.QueryResponseMapper;
import com.dk.dsearch.proto.query.QueryRequest;
import com.dk.dsearch.proto.query.QueryResponse;
import com.dk.dsearch.proto.query.QueryServiceGrpc;
import org.springframework.stereotype.Service;


@Service
public class GatewaySearchService {

    private final NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager;
    private final QueryResponseMapper mapper;

    public GatewaySearchService(
            NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager,
            QueryResponseMapper mapper
    ) {
        this.qnClientManager = qnClientManager;
        this.mapper = mapper;
    }

    public SearchResponseDto search(SearchRequestDto request) {
        QueryServiceGrpc.QueryServiceBlockingStub queryStub = qnClientManager.nextClient();
        QueryRequest.Builder grpcReqBuilder = buildBaseRequest(request);
        QueryResponse grpcResp = queryStub.search(grpcReqBuilder.build());
        return mapper.toDto(grpcResp);
    }

    private QueryRequest.Builder buildBaseRequest(SearchRequestDto request) {
        return QueryRequest.newBuilder()
                .setQueryString(request.getQuery())
                .setTopK(request.getTopK())
                .setPage(request.getPage())
                .setSize(request.getPageSize())
                .setShardId(request.getShardId())
                .setSearchType(request.getSearchType());
    }

}