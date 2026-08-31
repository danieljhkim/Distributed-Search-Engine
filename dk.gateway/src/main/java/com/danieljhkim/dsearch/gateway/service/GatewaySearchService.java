package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.gateway.mapper.QueryRequestMapper;
import com.danieljhkim.dsearch.gateway.mapper.QueryResponseMapper;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GatewaySearchService {

    private final NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager;
    private final QueryResponseMapper resMapper;
    private final QueryRequestMapper reqMapper;
    private final AppConfig.RequestLimitsConfig requestLimits;
    private final AppConfig.PaginationConfig paginationLimits;

    @Autowired
    public GatewaySearchService(
            NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager,
            QueryResponseMapper resMapper,
            QueryRequestMapper reqMapper,
            AppConfig appConfig) {
        this.qnClientManager = qnClientManager;
        this.resMapper = resMapper;
        this.reqMapper = reqMapper;
        this.requestLimits = RequestLimitsValidator.limitsOrDefaults(appConfig.getRequestLimits());
        this.paginationLimits = RequestLimitsValidator.paginationOrDefaults(appConfig.getPagination());
    }

    GatewaySearchService(
            NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager,
            QueryResponseMapper resMapper,
            QueryRequestMapper reqMapper) {
        this.qnClientManager = qnClientManager;
        this.resMapper = resMapper;
        this.reqMapper = reqMapper;
        this.requestLimits = new AppConfig.RequestLimitsConfig();
        this.paginationLimits = new AppConfig.PaginationConfig();
    }

    public SearchResponseDto search(SearchRequestDto request) {
        long startNanos = System.nanoTime();
        QueryServiceGrpc.QueryServiceBlockingStub queryStub = qnClientManager.nextClient();
        GatewayRequestValidator.validateSearch(request, requestLimits, paginationLimits);
        QueryRequest grpcReq = reqMapper.toGrpc(request);
        RequestLimitsValidator.validateQueryRequest(grpcReq, requestLimits, paginationLimits);
        QueryResponse grpcResp = queryStub
                .withDeadlineAfter(Math.max(1, requestLimits.getRequestTimeoutMillis()), TimeUnit.MILLISECONDS)
                .search(grpcReq);
        SearchResponseDto response = resMapper.toDto(grpcResp);
        long tookMillis = (System.nanoTime() - startNanos) / 1_000_000;
        response.setTookMillis(tookMillis);
        response.setPage(request.getPage());
        response.setPageSize(request.getPageSize());
        return response;
    }
}
