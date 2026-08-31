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

    /**
     * Ordering, most significant component first. Empty means relevance order. A deterministic
     * document-id tie-breaker is always appended server-side, so the order is total.
     */
    private List<SortDto> sort;

    /**
     * Opaque cursor from a previous response's {@code nextCursor}. Must not be combined with a
     * non-zero {@code page}: the cursor already encodes the position.
     */
    private String cursor;

    public SearchRequestDto() {}
}
