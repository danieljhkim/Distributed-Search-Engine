package com.danieljhkim.dsearch.querynode.grpc;

import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface BaseIndexService {

    SearchResult search(String queryString, String nodeId, String shardId, int page, int size, SearchType searchType);

    default IndexSchema inspectSchema(String indexOrAlias) {
        return null;
    }

    default SearchResult search(
            String queryString,
            String nodeId,
            String shardId,
            int page,
            int size,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight) {
        // Default implementation delegates to basic search (for backward compatibility)
        return search(queryString, nodeId, shardId, page, size, searchType, filters, highlight, null);
    }

    default SearchResult search(
            String queryString,
            String nodeId,
            String shardId,
            int page,
            int size,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests) {
        // Default implementation delegates to basic search (for backward compatibility)
        return search(queryString, nodeId, shardId, page, size, searchType);
    }

    default SearchResult searchShardTopK(
            String queryString, String nodeId, String shardId, int topK, SearchType searchType) {
        // page = 0, size = topK → from = 0, size = topK
        return search(queryString, nodeId, shardId, 0, topK, searchType);
    }

    default SearchResult searchShardTopK(
            String queryString,
            String nodeId,
            String shardId,
            int topK,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight) {
        return searchShardTopK(queryString, nodeId, shardId, topK, searchType, filters, highlight, null);
    }

    default SearchResult searchShardTopK(
            String queryString,
            String nodeId,
            String shardId,
            int topK,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests) {
        return search(queryString, nodeId, shardId, 0, topK, searchType, filters, highlight, facetRequests);
    }

    default SearchResult searchShardTopK(
            String queryString,
            String nodeId,
            String shardId,
            int topK,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            Duration deadline) {
        return searchShardTopK(queryString, nodeId, shardId, topK, searchType, filters, highlight, facetRequests);
    }

    default SearchResult mapToSearchResult(IndexSearchResponse grpcResp, int page) {
        List<SearchHit> hits = new ArrayList<>();
        for (com.danieljhkim.dsearch.proto.index.IndexHit hit : grpcResp.getHitsList()) {
            Map<String, String> highlightedFields = hit.getHighlightedFieldsMap();
            Map<String, String> fields = hit.getFieldsMap();
            Map<String, String> highlightedFieldsMap = highlightedFields.isEmpty() ? null : highlightedFields;
            Map<String, String> fieldsMap = fields.isEmpty() ? null : fields;
            hits.add(new SearchHit(
                    hit.getDocId(), hit.getTitle(), hit.getContent(), hit.getScore(), highlightedFieldsMap, fieldsMap));
        }
        List<FacetResponse> facets = grpcResp.getFacetsList();
        return new SearchResult(hits, grpcResp.getTotalHits(), page, facets.isEmpty() ? null : facets);
    }
}
