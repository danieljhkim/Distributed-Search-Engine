package com.danieljhkim.dsearch.gateway.mapper;

import com.danieljhkim.dsearch.gateway.api.dto.FacetBucketDto;
import com.danieljhkim.dsearch.gateway.api.dto.FacetResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.proto.common.FacetBucket;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.proto.query.SearchHit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QueryResponseMapper {

    public SearchResponseDto toDto(QueryResponse grpcResp) {
        List<SearchResponseDto.SearchHitDto> hits =
                grpcResp.getHitsList().stream().map(this::toHitDto).toList();

        SearchResponseDto response = new SearchResponseDto(hits, grpcResp.getTotalHits(), grpcResp.getPage());

        // Map facets if present
        if (grpcResp.getFacetsCount() > 0) {
            List<FacetResponseDto> facets =
                    grpcResp.getFacetsList().stream().map(this::toFacetDto).toList();
            response.setFacets(facets);
        }

        return response;
    }

    private SearchResponseDto.SearchHitDto toHitDto(SearchHit hit) {
        Map<String, String> highlightedFields = hit.getHighlightedFieldsMap();
        Map<String, String> fields = hit.getFieldsMap();
        Map<String, String> highlightedFieldsMap = highlightedFields.isEmpty() ? null : highlightedFields;
        Map<String, String> fieldsMap = fields.isEmpty() ? null : fields;

        return new SearchResponseDto.SearchHitDto(
                hit.getDocId(), hit.getTitle(), hit.getContent(), hit.getScore(), highlightedFieldsMap, fieldsMap);
    }

    private FacetResponseDto toFacetDto(FacetResponse facetResponse) {
        List<FacetBucketDto> buckets =
                facetResponse.getBucketsList().stream().map(this::toBucketDto).toList();
        return new FacetResponseDto(facetResponse.getField(), buckets);
    }

    private FacetBucketDto toBucketDto(FacetBucket bucket) {
        FacetBucketDto dto = new FacetBucketDto(bucket.getValue(), bucket.getCount());
        if (bucket.getNestedCount() > 0) {
            List<FacetResponseDto> nestedFacets =
                    bucket.getNestedList().stream().map(this::toFacetDto).toList();
            dto.setNested(nestedFacets);
        }
        return dto;
    }
}
