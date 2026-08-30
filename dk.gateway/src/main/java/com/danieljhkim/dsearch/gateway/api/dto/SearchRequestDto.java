package com.danieljhkim.dsearch.gateway.api.dto;

import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import com.danieljhkim.dsearch.proto.common.SearchType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SearchRequestDto {

    @NotBlank(message = "query must not be blank") private String query;

    @jakarta.validation.constraints.Size(max = 64, message = "partitionId must be at most 64 characters") @Pattern(
            regexp = "[A-Za-z0-9_-]+",
            message = "partitionId may contain only letters, numbers, underscores, and hyphens")
    private String partitionId = "default";

    @Min(value = 0, message = "page must be >= 0") private int page = 0;

    @Min(value = 1, message = "pageSize must be > 0") private int pageSize = 10;

    private SearchType searchType = SearchType.BM25;

    private FusionStrategy fusionStrategy = FusionStrategy.RRF;

    private List<FilterDto> filters;

    private List<FacetRequestDto> facets;

    private Boolean highlight = true;

    public SearchRequestDto() {}
}
