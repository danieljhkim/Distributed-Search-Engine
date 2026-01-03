package com.danieljhkim.dsearch.gateway.api.dto;

import com.danieljhkim.dsearch.proto.common.FilterOperator;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for filter conditions in search requests.
 */
@Setter
@Getter
public class FilterDto {

    private String field;
    private FilterOperator operator = FilterOperator.EQ;
    private List<String> values;

    public FilterDto() {}

    public FilterDto(String field, FilterOperator operator, List<String> values) {
        this.field = field;
        this.operator = operator;
        this.values = values;
    }
}
