package com.danieljhkim.dsearch.gateway.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.danieljhkim.dsearch.gateway.api.dto.FacetRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.FilterDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.proto.common.FacetBucket;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.common.FilterOperator;
import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.query.FanoutMetadata;
import com.danieljhkim.dsearch.proto.query.FanoutStatus;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.proto.query.SearchHit;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryMapperTest {

    @Test
    void requestMapperHandlesNestedFacetsAndNullCollections() {
        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("lucene");
        request.setPartitionId("tenant-a");
        request.setPage(3);
        request.setPageSize(7);
        request.setSearchType(SearchType.HYBRID);
        request.setFusionStrategy(FusionStrategy.WEIGHTED);
        request.setHighlight(false);
        request.setFilters(List.of(new FilterDto("category", FilterOperator.IN, List.of("docs", "guides"))));
        request.setFacets(List.of(new FacetRequestDto(
                "author",
                4,
                List.of(new FilterDto("year", FilterOperator.GTE, List.of("2024"))),
                List.of(new FacetRequestDto("tag", 2)))));

        var mapped = new QueryRequestMapper().toGrpc(request);

        assertThat(mapped.getQueryString()).isEqualTo("lucene");
        assertThat(mapped.getFilters(0).getValuesList()).containsExactly("docs", "guides");
        assertThat(mapped.getFacets(0).getNested(0).getField()).isEqualTo("tag");

        request.setFilters(null);
        request.setFacets(null);
        mapped = new QueryRequestMapper().toGrpc(request);
        assertThat(mapped.getFiltersCount()).isZero();
        assertThat(mapped.getFacetsCount()).isZero();
    }

    @Test
    void responseMapperPreservesPublicFanoutFacetAndPaginationFields() {
        FacetResponse nestedFacet = FacetResponse.newBuilder()
                .setField("tag")
                .addBuckets(FacetBucket.newBuilder().setValue("java").setCount(2))
                .build();
        QueryResponse response = QueryResponse.newBuilder()
                .setPage(4)
                .setTotalHits(11)
                .addHits(SearchHit.newBuilder()
                        .setDocId("doc-1")
                        .setTitle("Title")
                        .setContent("Body")
                        .setScore(3.5))
                .addFacets(FacetResponse.newBuilder()
                        .setField("category")
                        .addBuckets(FacetBucket.newBuilder()
                                .setValue("docs")
                                .setCount(7)
                                .addNested(nestedFacet)))
                .setFanout(FanoutMetadata.newBuilder()
                        .setStatus(FanoutStatus.FANOUT_STATUS_PARTIAL_FAILURE)
                        .setAttemptedNodes(3)
                        .setSucceededNodes(2)
                        .setFailedNodes(1)
                        .setTimedOutNodes(0))
                .build();

        SearchResponseDto mapped = new QueryResponseMapper().toDto(response);

        assertThat(mapped.getPage()).isEqualTo(4);
        assertThat(mapped.getTotalHits()).isEqualTo(11);
        assertThat(mapped.getHits().getFirst().getHighlightedFields()).isNull();
        assertThat(mapped.getFacets().getFirst().getBuckets().getFirst().getNested())
                .hasSize(1);
        assertThat(mapped.getFanout().getStatus()).isEqualTo("PARTIAL_FAILURE");
    }

    @Test
    void responseMapperMapsEveryFanoutStatusWithoutAJsonOrderingDependency() {
        QueryResponseMapper mapper = new QueryResponseMapper();
        assertThat(mapper.toDto(responseWith(FanoutStatus.FANOUT_STATUS_SUCCESS))
                        .getFanout()
                        .getStatus())
                .isEqualTo("SUCCESS");
        assertThat(mapper.toDto(responseWith(FanoutStatus.FANOUT_STATUS_FAILED))
                        .getFanout()
                        .getStatus())
                .isEqualTo("FAILED");
        assertThat(mapper.toDto(responseWith(FanoutStatus.FANOUT_STATUS_UNSPECIFIED))
                        .getFanout()
                        .getStatus())
                .isEqualTo("UNKNOWN");
    }

    private static QueryResponse responseWith(FanoutStatus status) {
        return QueryResponse.newBuilder()
                .setFanout(FanoutMetadata.newBuilder().setStatus(status))
                .build();
    }
}
