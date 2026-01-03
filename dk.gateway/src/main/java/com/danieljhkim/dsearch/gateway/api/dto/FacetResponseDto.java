package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for facet responses in search results.
 */
@Setter
@Getter
public class FacetResponseDto {

    private String field;
    private List<FacetBucketDto> buckets;

    public FacetResponseDto() {}

    public FacetResponseDto(String field, List<FacetBucketDto> buckets) {
        this.field = field;
        this.buckets = buckets;
    }
}
