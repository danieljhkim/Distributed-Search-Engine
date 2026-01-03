package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.gateway.mapper.QueryRequestMapper;
import com.danieljhkim.dsearch.gateway.mapper.QueryResponseMapper;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;
import org.springframework.stereotype.Service;

@Service
public class GatewaySearchService {

    private final NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager;
    private final QueryResponseMapper resMapper;
    private final QueryRequestMapper reqMapper;

    public GatewaySearchService(
            NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager,
            QueryResponseMapper resMapper,
            QueryRequestMapper reqMapper) {
        this.qnClientManager = qnClientManager;
        this.resMapper = resMapper;
        this.reqMapper = reqMapper;
    }

    public SearchResponseDto search(SearchRequestDto request) {
        // Validate request limits
        RequestLimitsValidator.validateRequestLimits(request.getQuery(), request.getPageSize());
        long startNanos = System.nanoTime();
        QueryServiceGrpc.QueryServiceBlockingStub queryStub = qnClientManager.nextClient();
        QueryRequest grpcReq = reqMapper.toGrpc(request);
        QueryResponse grpcResp = queryStub.search(grpcReq);
        SearchResponseDto response = resMapper.toDto(grpcResp);
        long tookMillis = (System.nanoTime() - startNanos) / 1_000_000;
        response.setTookMillis(tookMillis);
        response.setPage(request.getPage());
        response.setPageSize(request.getPageSize());
        return response;
    }
}
