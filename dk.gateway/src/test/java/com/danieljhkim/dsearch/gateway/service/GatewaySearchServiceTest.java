package com.danieljhkim.dsearch.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.gateway.api.dto.FacetRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.FilterDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.gateway.mapper.QueryRequestMapper;
import com.danieljhkim.dsearch.gateway.mapper.QueryResponseMapper;
import com.danieljhkim.dsearch.proto.common.FacetBucket;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.common.FilterOperator;
import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.query.FanoutMetadata;
import com.danieljhkim.dsearch.proto.query.FanoutStatus;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;
import com.danieljhkim.dsearch.proto.query.SearchHit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GatewaySearchServiceTest {

    @Mock
    private NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> qnClientManager;

    @Mock
    private QueryServiceGrpc.QueryServiceBlockingStub queryStub;

    private GatewaySearchService service;

    @BeforeEach
    void setUp() {
        service = new GatewaySearchService(qnClientManager, new QueryResponseMapper(), new QueryRequestMapper());
    }

    @Test
    void searchMapsDtoOptionsToGrpcRequestWithoutNetworkCalls() {
        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("lucene vector");
        request.setPartitionId("tenant-a");
        request.setPage(2);
        request.setPageSize(3);
        request.setSearchType(SearchType.HYBRID);
        request.setFusionStrategy(FusionStrategy.WEIGHTED);
        request.setHighlight(false);
        request.setFilters(List.of(new FilterDto("category", FilterOperator.IN, List.of("docs", "guides"))));
        request.setFacets(List.of(new FacetRequestDto(
                "author",
                5,
                List.of(new FilterDto("year", FilterOperator.GTE, List.of("2024"))),
                List.of(new FacetRequestDto("tag", 3)))));

        when(qnClientManager.nextClient()).thenReturn(queryStub);
        when(queryStub.search(any(QueryRequest.class))).thenReturn(queryResponse());

        SearchResponseDto response = service.search(request);

        assertThat(response.getTotalHits()).isEqualTo(1);
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getPageSize()).isEqualTo(3);
        assertThat(response.getHits()).hasSize(1);
        assertThat(response.getHits().getFirst().getDocId()).isEqualTo("doc-2");
        assertThat(response.getFacets()).hasSize(1);
        assertThat(response.getFacets().getFirst().getField()).isEqualTo("category");
        assertThat(response.getFacets().getFirst().getBuckets().getFirst().getValue())
                .isEqualTo("docs");
        assertThat(response.getFanout().getStatus()).isEqualTo("PARTIAL_FAILURE");
        assertThat(response.getFanout().getAttemptedNodes()).isEqualTo(2);
        assertThat(response.getFanout().getSucceededNodes()).isEqualTo(1);
        assertThat(response.getFanout().getFailedNodes()).isEqualTo(1);
        assertThat(response.getFanout().getTimedOutNodes()).isEqualTo(0);

        ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryStub).search(requestCaptor.capture());
        QueryRequest grpcRequest = requestCaptor.getValue();
        assertThat(grpcRequest.getQueryString()).isEqualTo("lucene vector");
        assertThat(grpcRequest.getPartitionId()).isEqualTo("tenant-a");
        assertThat(grpcRequest.getPage()).isEqualTo(2);
        assertThat(grpcRequest.getSize()).isEqualTo(3);
        assertThat(grpcRequest.getSearchType()).isEqualTo(SearchType.HYBRID);
        assertThat(grpcRequest.getFusionStrategy()).isEqualTo(FusionStrategy.WEIGHTED);
        assertThat(grpcRequest.getHighlight()).isFalse();
        assertThat(grpcRequest.getFiltersCount()).isEqualTo(1);
        assertThat(grpcRequest.getFilters(0).getField()).isEqualTo("category");
        assertThat(grpcRequest.getFilters(0).getOperator()).isEqualTo(FilterOperator.IN);
        assertThat(grpcRequest.getFilters(0).getValuesList()).containsExactly("docs", "guides");
        assertThat(grpcRequest.getFacetsCount()).isEqualTo(1);
        assertThat(grpcRequest.getFacets(0).getField()).isEqualTo("author");
        assertThat(grpcRequest.getFacets(0).getSize()).isEqualTo(5);
        assertThat(grpcRequest.getFacets(0).getFilters(0).getField()).isEqualTo("year");
        assertThat(grpcRequest.getFacets(0).getFilters(0).getOperator()).isEqualTo(FilterOperator.GTE);
        assertThat(grpcRequest.getFacets(0).getNested(0).getField()).isEqualTo("tag");
        assertThat(grpcRequest.getFacets(0).getNested(0).getSize()).isEqualTo(3);
    }

    private static QueryResponse queryResponse() {
        return QueryResponse.newBuilder()
                .addHits(SearchHit.newBuilder()
                        .setDocId("doc-2")
                        .setTitle("Lucene Guide")
                        .setContent("Lucene search guide")
                        .setScore(12.5)
                        .putHighlightedFields("content", "<em>Lucene</em> search guide")
                        .putFields("category", "docs")
                        .build())
                .setTotalHits(1)
                .setPage(2)
                .setSize(3)
                .addFacets(FacetResponse.newBuilder()
                        .setField("category")
                        .addBuckets(FacetBucket.newBuilder()
                                .setValue("docs")
                                .setCount(4)
                                .build())
                        .build())
                .setFanout(FanoutMetadata.newBuilder()
                        .setAttemptedNodes(2)
                        .setSucceededNodes(1)
                        .setFailedNodes(1)
                        .setTimedOutNodes(0)
                        .setStatus(FanoutStatus.FANOUT_STATUS_PARTIAL_FAILURE)
                        .build())
                .build();
    }
}
