package com.danieljhkim.dsearch.gateway.mapper;

import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.dk.dsearch.proto.query.QueryResponse;
import com.dk.dsearch.proto.query.SearchHit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QueryResponseMapper {

    public SearchResponseDto toDto(QueryResponse grpcResp) {
        List<SearchResponseDto.SearchHitDto> hits = grpcResp.getHitsList().stream()
                .map(this::toHitDto)
                .toList();

        return new SearchResponseDto(
                hits,
                grpcResp.getTotalHits(),
                grpcResp.getPage()
        );
    }

    private SearchResponseDto.SearchHitDto toHitDto(SearchHit hit) {
        return new SearchResponseDto.SearchHitDto(
                hit.getDocId(),
                hit.getTitle(),
                hit.getContent(),
                hit.getScore()
        );
    }
}