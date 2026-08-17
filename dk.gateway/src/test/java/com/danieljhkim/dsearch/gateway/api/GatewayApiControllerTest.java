package com.danieljhkim.dsearch.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.gateway.api.dto.IndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.gateway.service.GatewayIndexService;
import com.danieljhkim.dsearch.gateway.service.GatewaySearchService;
import com.danieljhkim.dsearch.gateway.tracing.CorrelationIdFilter;
import com.danieljhkim.dsearch.proto.common.FilterOperator;
import com.danieljhkim.dsearch.proto.common.FusionStrategy;
import com.danieljhkim.dsearch.proto.common.SearchType;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

@WebMvcTest(controllers = {IndexController.class, SearchController.class, HealthController.class})
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, GatewayApiControllerTest.TestMetricsConfig.class})
class GatewayApiControllerTest {

    private static final String REQUEST_ID_HEADER = CorrelationIdFilter.HEADER_NAME;

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockBean
    private GatewayIndexService indexService;

    @MockBean
    private GatewaySearchService searchService;

    @MockBean
    private RestTemplate restTemplate;

    @MockBean
    private AppConfig appConfig;

    @Test
    void indexDocumentReturnsSuccessAndMapsRequestBody() throws Exception {
        when(indexService.index(any(IndexRequestDto.class))).thenReturn(new IndexResponseDto("doc-1", true));

        mockMvc.perform(
                        post("/api/v1/index")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "id": "doc-1",
                                  "partitionId": "tenant-a",
                                  "fields": {
                                    "title": "Distributed Search",
                                    "category": "docs"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists(REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.id").value("doc-1"))
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<IndexRequestDto> requestCaptor = ArgumentCaptor.forClass(IndexRequestDto.class);
        verify(indexService).index(requestCaptor.capture());
        IndexRequestDto mappedRequest = requestCaptor.getValue();
        assertThat(mappedRequest.getId()).isEqualTo("doc-1");
        assertThat(mappedRequest.getPartitionId()).isEqualTo("tenant-a");
        assertThat(mappedRequest.getFields())
                .containsEntry("title", "Distributed Search")
                .containsEntry("category", "docs");
    }

    @Test
    void deleteDocumentReturnsSuccessAndMapsPathAndPartition() throws Exception {
        when(indexService.delete("doc-7", "tenant-a")).thenReturn(new IndexResponseDto("doc-7", true));

        mockMvc.perform(delete("/api/v1/index/{id}", "doc-7")
                        .param("partitionId", "tenant-a")
                        .header(REQUEST_ID_HEADER, "client-request-7"))
                .andExpect(status().isOk())
                .andExpect(header().string(REQUEST_ID_HEADER, "client-request-7"))
                .andExpect(jsonPath("$.id").value("doc-7"))
                .andExpect(jsonPath("$.success").value(true));

        verify(indexService).delete("doc-7", "tenant-a");
    }

    @Test
    void searchReturnsHitsFacetsAndMapsSearchOptions() throws Exception {
        SearchResponseDto response = new SearchResponseDto(
                List.of(new SearchResponseDto.SearchHitDto(
                        "doc-2",
                        "Lucene Guide",
                        "Lucene search guide",
                        12.5,
                        Map.of("content", "<em>Lucene</em> search guide"),
                        Map.of("category", "docs"))),
                1,
                8,
                2);
        response.setPageSize(3);
        response.setFacets(List.of(new com.danieljhkim.dsearch.gateway.api.dto.FacetResponseDto(
                "category", List.of(new com.danieljhkim.dsearch.gateway.api.dto.FacetBucketDto("docs", 4L)))));
        when(searchService.search(any(SearchRequestDto.class))).thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "query": "lucene vector",
                                  "partitionId": "tenant-a",
                                  "page": 2,
                                  "pageSize": 3,
                                  "searchType": "HYBRID",
                                  "fusionStrategy": "WEIGHTED",
                                  "highlight": false,
                                  "filters": [
                                    {
                                      "field": "category",
                                      "operator": "IN",
                                      "values": ["docs", "guides"]
                                    }
                                  ],
                                  "facets": [
                                    {
                                      "field": "author",
                                      "size": 5,
                                      "filters": [
                                        {
                                          "field": "year",
                                          "operator": "GTE",
                                          "values": ["2024"]
                                        }
                                      ],
                                      "nested": [
                                        {
                                          "field": "tag",
                                          "size": 3
                                        }
                                      ]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists(REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.totalHits").value(1))
                .andExpect(jsonPath("$.tookMillis").value(8))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(3))
                .andExpect(jsonPath("$.hits[0].docId").value("doc-2"))
                .andExpect(jsonPath("$.hits[0].highlightedFields.content").value("<em>Lucene</em> search guide"))
                .andExpect(jsonPath("$.hits[0].fields.category").value("docs"))
                .andExpect(jsonPath("$.facets[0].field").value("category"))
                .andExpect(jsonPath("$.facets[0].buckets[0].value").value("docs"))
                .andExpect(jsonPath("$.facets[0].buckets[0].count").value(4));

        ArgumentCaptor<SearchRequestDto> requestCaptor = ArgumentCaptor.forClass(SearchRequestDto.class);
        verify(searchService).search(requestCaptor.capture());
        SearchRequestDto mappedRequest = requestCaptor.getValue();
        assertThat(mappedRequest.getQuery()).isEqualTo("lucene vector");
        assertThat(mappedRequest.getPartitionId()).isEqualTo("tenant-a");
        assertThat(mappedRequest.getPage()).isEqualTo(2);
        assertThat(mappedRequest.getPageSize()).isEqualTo(3);
        assertThat(mappedRequest.getSearchType()).isEqualTo(SearchType.HYBRID);
        assertThat(mappedRequest.getFusionStrategy()).isEqualTo(FusionStrategy.WEIGHTED);
        assertThat(mappedRequest.getHighlight()).isFalse();
        assertThat(mappedRequest.getFilters()).hasSize(1);
        assertThat(mappedRequest.getFilters().getFirst().getField()).isEqualTo("category");
        assertThat(mappedRequest.getFilters().getFirst().getOperator()).isEqualTo(FilterOperator.IN);
        assertThat(mappedRequest.getFilters().getFirst().getValues()).containsExactly("docs", "guides");
        assertThat(mappedRequest.getFacets()).hasSize(1);
        assertThat(mappedRequest.getFacets().getFirst().getField()).isEqualTo("author");
        assertThat(mappedRequest.getFacets().getFirst().getSize()).isEqualTo(5);
        assertThat(mappedRequest.getFacets().getFirst().getFilters().getFirst().getField())
                .isEqualTo("year");
        assertThat(mappedRequest.getFacets().getFirst().getNested().getFirst().getField())
                .isEqualTo("tag");
    }

    @Test
    void healthReturnsGatewayStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("gateway"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void clusterHealthReturnsAggregatedSuccessStatus() throws Exception {
        when(appConfig.getIndexNodes()).thenReturn(nodeGroup("index-0", "127.0.0.1", 5000, 5100));
        when(appConfig.getQueryNodes()).thenReturn(nodeGroup("query-0", "127.0.0.1", 6000, 6100));
        when(appConfig.getCoordinatorNodes()).thenReturn(nodeGroup("coordinator-0", "127.0.0.1", 7000, 7100));
        when(restTemplate.getForEntity("http://127.0.0.1:5100/health", String.class))
                .thenReturn(ResponseEntity.ok("{\"status\":\"UP\"}"));
        when(restTemplate.getForEntity("http://127.0.0.1:6100/health", String.class))
                .thenReturn(ResponseEntity.ok("{\"status\":\"UP\"}"));
        when(restTemplate.getForEntity("http://127.0.0.1:7100/health", String.class))
                .thenReturn(ResponseEntity.ok("{\"status\":\"UP\"}"));

        mockMvc.perform(get("/cluster/health").header(REQUEST_ID_HEADER, "health-request-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(REQUEST_ID_HEADER, "health-request-1"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.gateway.status").value("UP"))
                .andExpect(jsonPath("$.indexNodes[0].id").value("index-0"))
                .andExpect(jsonPath("$.indexNodes[0].grpcPort").value(5000))
                .andExpect(jsonPath("$.indexNodes[0].healthPort").value(5100))
                .andExpect(jsonPath("$.indexNodes[0].status").value("UP"))
                .andExpect(jsonPath("$.queryNodes[0].id").value("query-0"))
                .andExpect(jsonPath("$.queryNodes[0].status").value("UP"))
                .andExpect(jsonPath("$.coordinatorNodes[0].id").value("coordinator-0"))
                .andExpect(jsonPath("$.coordinatorNodes[0].status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void clusterHealthReturnsDegradedWhenCoordinatorIsDown() throws Exception {
        when(appConfig.getIndexNodes()).thenReturn(nodeGroup("index-0", "127.0.0.1", 5000, 5100));
        when(appConfig.getQueryNodes()).thenReturn(nodeGroup("query-0", "127.0.0.1", 6000, 6100));
        when(appConfig.getCoordinatorNodes()).thenReturn(nodeGroup("coordinator-0", "127.0.0.1", 7000, 7100));
        when(restTemplate.getForEntity("http://127.0.0.1:5100/health", String.class))
                .thenReturn(ResponseEntity.ok("{\"status\":\"UP\"}"));
        when(restTemplate.getForEntity("http://127.0.0.1:6100/health", String.class))
                .thenReturn(ResponseEntity.ok("{\"status\":\"UP\"}"));
        when(restTemplate.getForEntity("http://127.0.0.1:7100/health", String.class))
                .thenThrow(new RuntimeException("coordinator unavailable"));

        mockMvc.perform(get("/cluster/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.coordinatorNodes[0].status").value("DOWN"));
    }

    @Test
    void clusterHealthOnlyProbesTheEffectiveConfiguredNodeSet() throws Exception {
        when(appConfig.getIndexNodes()).thenReturn(nodeGroup("index-0", "127.0.0.1", 5000, 5100));
        when(appConfig.getQueryNodes()).thenReturn(nodeGroup("query-0", "127.0.0.1", 6000, 6100));
        when(appConfig.getCoordinatorNodes()).thenReturn(nodeGroup("coordinator-0", "127.0.0.1", 7000, 7100));
        when(restTemplate.getForEntity(any(String.class), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"UP\"}"));

        mockMvc.perform(get("/cluster/health")).andExpect(status().isOk());

        verify(restTemplate).getForEntity("http://127.0.0.1:5100/health", String.class);
        verify(restTemplate).getForEntity("http://127.0.0.1:6100/health", String.class);
        verify(restTemplate).getForEntity("http://127.0.0.1:7100/health", String.class);
        verify(restTemplate, never()).getForEntity("http://127.0.0.1:5101/health", String.class);
        verify(restTemplate, never()).getForEntity("http://127.0.0.1:6101/health", String.class);
    }

    @Test
    void searchValidationFailureReturnsExactErrorShape() throws Exception {
        mockMvc.perform(
                        post("/api/v1/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "query": "",
                                  "page": 0,
                                  "pageSize": 10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("query query must not be blank"))
                .andExpect(jsonPath("$.path").value("/api/v1/search"));
    }

    @Test
    void searchPageSizeValidationFailureReturnsExactErrorShape() throws Exception {
        mockMvc.perform(
                        post("/api/v1/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "query": "lucene",
                                  "page": 0,
                                  "pageSize": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("pageSize pageSize must be > 0"))
                .andExpect(jsonPath("$.path").value("/api/v1/search"));
    }

    @Test
    void downstreamGrpcExceptionReturnsExactErrorShape() throws Exception {
        when(indexService.index(any(IndexRequestDto.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("index node unavailable")));

        mockMvc.perform(
                        post("/api/v1/index")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "id": "doc-1",
                                  "partitionId": "tenant-a",
                                  "fields": {
                                    "title": "Distributed Search"
                                  }
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("index node unavailable"))
                .andExpect(jsonPath("$.path").value("/api/v1/index"));
    }

    @Test
    void generatedCorrelationIdWhenAbsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(REQUEST_ID_HEADER))
                .andReturn();

        String requestId = result.getResponse().getHeader(REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank();
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
    }

    @Test
    void preservesSuppliedCorrelationId() throws Exception {
        mockMvc.perform(get("/health").header(REQUEST_ID_HEADER, "client-request-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(REQUEST_ID_HEADER, "client-request-123"));
    }

    private static AppConfig.NodeGroupConfig nodeGroup(String id, String host, int grpcPort, int healthPort) {
        AppConfig.NodeConfig node = new AppConfig.NodeConfig();
        node.setId(id);
        node.setHost(host);
        node.setPort(grpcPort);
        node.setHealthPort(healthPort);

        AppConfig.NodeGroupConfig group = new AppConfig.NodeGroupConfig();
        group.setNodes(List.of(node));
        return group;
    }

    @TestConfiguration
    static class TestMetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
