package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for facet requests in search queries.
 */
@Setter
@Getter
public class FacetRequestDto {

    private String field;
    private Integer size = 10;
    private List<FilterDto> filters;
    private List<FacetRequestDto> nested;

    public FacetRequestDto() {}

    public FacetRequestDto(String field, Integer size) {
        this.field = field;
        this.size = size;
    }

    public FacetRequestDto(String field, Integer size, List<FilterDto> filters, List<FacetRequestDto> nested) {
        this.field = field;
        this.size = size;
        this.filters = filters;
        this.nested = nested;
    }
}
