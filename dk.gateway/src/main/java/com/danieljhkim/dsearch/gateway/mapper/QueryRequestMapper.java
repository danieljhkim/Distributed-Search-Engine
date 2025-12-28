package com.danieljhkim.dsearch.gateway.mapper;

import com.danieljhkim.dsearch.gateway.api.dto.FacetRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.FilterDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import org.springframework.stereotype.Component;

@Component
public class QueryRequestMapper {

	public QueryRequest toGrpc(SearchRequestDto request) {
		var protoType = request.getSearchType();
		var fusionStrategy = request.getFusionStrategy();

		QueryRequest.Builder b = QueryRequest.newBuilder()
				.setQueryString(request.getQuery())
				.setPage(request.getPage())
				.setSize(request.getPageSize())
				.setPartitionId(request.getPartitionId())
				.setSearchType(protoType)
				.setFusionStrategy(fusionStrategy)
				.setHighlight(Boolean.TRUE.equals(request.getHighlight()));

		// Collections: treat null as empty
		if (request.getFilters() != null) {
			request.getFilters().forEach(f -> b.addFilters(mapFilter(f)));
		}
		if (request.getFacets() != null) {
			request.getFacets().forEach(f -> b.addFacets(mapFacetRequest(f)));
		}

		return b.build();
	}

	private Filter mapFilter(FilterDto dto) {
		Filter.Builder b = Filter.newBuilder()
				.setField(dto.getField())
				.setOperator(dto.getOperator());

		if (dto.getValues() != null) {
			b.addAllValues(dto.getValues());
		}
		return b.build();
	}

	private FacetRequest mapFacetRequest(FacetRequestDto dto) {
		FacetRequest.Builder b = FacetRequest.newBuilder()
				.setField(dto.getField());

		if (dto.getSize() != null) {
			b.setSize(dto.getSize());
		}
		if (dto.getFilters() != null) {
			dto.getFilters().forEach(f -> b.addFilters(mapFilter(f)));
		}
		if (dto.getNested() != null) {
			dto.getNested().forEach(n -> b.addNested(mapFacetRequest(n)));
		}
		return b.build();
	}
}