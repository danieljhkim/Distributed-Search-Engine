package com.danieljhkim.dsearch.querynode.grpc;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.IndexSearchRequest;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import io.grpc.Context;
import io.grpc.Deadline;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class IndexService implements BaseIndexService {

    private static final Duration DEFAULT_CALL_DEADLINE = Duration.ofSeconds(2);

    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;
    private final Duration defaultCallDeadline;

    public IndexService(NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
        this.nodeClientManager = nodeClientManager;
        this.defaultCallDeadline = DEFAULT_CALL_DEADLINE;
    }

    public IndexService(
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager,
            AppConfig.RequestLimitsConfig requestLimits) {
        this.nodeClientManager = nodeClientManager;
        this.defaultCallDeadline = Duration.ofMillis(Math.max(1, requestLimits.getRequestTimeoutMillis()));
    }

    @Override
    public SearchResult search(
            String queryString, String nodeId, String partitionId, int page, int size, SearchType searchType) {
        return search(queryString, nodeId, partitionId, page, size, searchType, null, false);
    }

    @Override
    public SearchResult search(
            String queryString,
            String nodeId,
            String partitionId,
            int page,
            int size,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight) {
        return search(queryString, nodeId, partitionId, page, size, searchType, filters, highlight, null);
    }

    @Override
    public SearchResult search(
            String queryString,
            String nodeId,
            String partitionId,
            int page,
            int size,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests) {
        return search(
                queryString,
                nodeId,
                partitionId,
                page,
                size,
                searchType,
                filters,
                highlight,
                facetRequests,
                defaultCallDeadline);
    }

    @Override
    public SearchResult searchShardTopK(
            String queryString,
            String nodeId,
            String shardId,
            int topK,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            Duration deadline) {
        return search(queryString, nodeId, shardId, 0, topK, searchType, filters, highlight, facetRequests, deadline);
    }

    private SearchResult search(
            String queryString,
            String nodeId,
            String partitionId,
            int page,
            int size,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            Duration deadline) {
        if (!nodeClientManager.getClientMap().containsKey(nodeId)) {
            throw new IllegalArgumentException("Unknown nodeId: " + nodeId);
        }
        int from = Math.toIntExact(Math.multiplyExact((long) page, size));
        IndexSearchRequest.Builder grpcReqBuilder = IndexSearchRequest.newBuilder()
                .setQuery(queryString)
                .setFrom(from)
                .setSize(size)
                .setPartitionId(partitionId)
                .setSearchType(searchType)
                .setHighlight(highlight);

        if (filters != null) {
            grpcReqBuilder.addAllFilters(filters);
        }
        if (facetRequests != null) {
            grpcReqBuilder.addAllFacets(facetRequests);
        }

        IndexServiceGrpc.IndexServiceBlockingStub stub =
                nodeClientManager.getClientMap().get(nodeId).getStub();
        IndexSearchResponse grpcResp = withDeadline(stub, deadline).searchIndex(grpcReqBuilder.build());
        return mapToSearchResult(grpcResp, page);
    }

    private IndexServiceGrpc.IndexServiceBlockingStub withDeadline(
            IndexServiceGrpc.IndexServiceBlockingStub stub, Duration deadline) {
        Duration effectiveDeadline = deadline != null ? deadline : defaultCallDeadline;
        Deadline contextDeadline = Context.current().getDeadline();
        if (contextDeadline != null) {
            long contextNanos = Math.max(1L, contextDeadline.timeRemaining(TimeUnit.NANOSECONDS));
            effectiveDeadline = Duration.ofNanos(Math.min(effectiveDeadline.toNanos(), contextNanos));
        }
        long timeoutNanos = Math.max(1L, effectiveDeadline.toNanos());
        return stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS);
    }
}
