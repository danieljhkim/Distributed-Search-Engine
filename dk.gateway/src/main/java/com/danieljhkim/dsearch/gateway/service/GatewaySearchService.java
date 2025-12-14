package com.danieljhkim.dsearch.gateway.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.danieljhkim.dsearch.common.enums.EnumMapper;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.gateway.mapper.QueryResponseMapper;
import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;

@Service
public class GatewaySearchService {

	private final NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager;
	private final QueryResponseMapper mapper;

	public GatewaySearchService(
			NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager,
			QueryResponseMapper mapper) {
		this.qnClientManager = qnClientManager;
		this.mapper = mapper;
	}

	public Page<SearchResponseDto.SearchHitDto> search(SearchRequestDto request) {
		long startNanos = System.nanoTime();
		Pageable pageable = PageRequest.of(request.getPage(), request.getPageSize());
		QueryServiceGrpc.QueryServiceBlockingStub queryStub = qnClientManager.nextClient();
		QueryRequest.Builder grpcReqBuilder = buildBaseRequest(request);
		QueryResponse grpcResp = queryStub.search(grpcReqBuilder.build());
		List<SearchResponseDto.SearchHitDto> content = mapper.toDto(grpcResp).getHits();
		long total = grpcResp.getTotalHits();
		long tookMillis = (System.nanoTime() - startNanos) / 1_000_000;
		System.out.println("Gateway search took " + tookMillis + " ms");
		return new PageImpl<>(content, pageable, total);
	}

	private QueryRequest.Builder buildBaseRequest(SearchRequestDto request) {
		com.danieljhkim.dsearch.proto.common.SearchType protoType = EnumMapper.mapToProtoEnum(request.getSearchType());
		FusionStrategy fusionStrategy = EnumMapper.mapToProtoEnum(request.getFusionStrategy());
		return QueryRequest.newBuilder()
				.setQueryString(request.getQuery())
				.setPage(request.getPage())
				.setSize(request.getPageSize())
				.setPartitionId(request.getPartitionId())
				.setSearchType(protoType)
				.setFusionStrategy(fusionStrategy);
	}
}