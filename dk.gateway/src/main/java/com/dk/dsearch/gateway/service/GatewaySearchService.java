package com.dk.dsearch.gateway.service;

import com.dk.dsearch.common.enums.EnumMapper;
import com.dk.dsearch.common.grpc.NodeClientManager;
import com.dk.dsearch.gateway.api.dto.SearchRequestDto;
import com.dk.dsearch.gateway.api.dto.SearchResponseDto;
import com.dk.dsearch.gateway.mapper.QueryResponseMapper;
import com.dk.dsearch.proto.query.QueryRequest;
import com.dk.dsearch.proto.query.QueryResponse;
import com.dk.dsearch.proto.query.QueryServiceGrpc;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.*;

import java.util.List;


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

    public Page<SearchResponseDto.SearchHitDto> search(SearchRequestDto request) {
        Pageable pageable = PageRequest.of(request.getPage(), request.getPageSize());
        QueryServiceGrpc.QueryServiceBlockingStub queryStub = qnClientManager.nextClient();
        QueryRequest.Builder grpcReqBuilder = buildBaseRequest(request);
        QueryResponse grpcResp = queryStub.search(grpcReqBuilder.build());
        List<SearchResponseDto.SearchHitDto> content = mapper.toDto(grpcResp).getHits();
        long total = grpcResp.getTotalHits();
        return new PageImpl<>(content, pageable, total);
    }

    private QueryRequest.Builder buildBaseRequest(SearchRequestDto request) {
        com.dk.dsearch.proto.common.SearchType protoType = EnumMapper.mapToProtoEnum(request.getSearchType());
        return QueryRequest.newBuilder()
                .setQueryString(request.getQuery())
                .setPage(request.getPage())
                .setSize(request.getPageSize())
                .setShardId(request.getShardId())
                .setSearchType(protoType);
    }
}