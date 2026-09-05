package com.danieljhkim.dsearch.querynode.grpc;

import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.pagination.SortOptions;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.common.SortValue;
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

    /**
     * Schema and alias generation of a partition, read together.
     *
     * <p>Cursor validation needs both, and reading them in one round trip is what keeps them
     * consistent: fetched separately, an alias swap in between could pair a new schema with an old
     * generation and let an unusable cursor through.
     *
     * @param generation alias generation currently serving the partition
     */
    record IndexSnapshot(IndexSchema schema, long generation) {}

    default IndexSnapshot inspectIndexSnapshot(String indexOrAlias) {
        // Generation 0 for an implementation that only knows how to report a schema. Cursor
        // validation still binds to it, so such an implementation simply never sees a generation
        // change rather than silently skipping the check.
        IndexSchema schema = inspectSchema(indexOrAlias);
        return schema == null ? null : new IndexSnapshot(schema, 0L);
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

    /**
     * Node-local top-K under an explicit ordering.
     *
     * <p>With a resume point the node returns the first {@code topK} hits after it, so a deep page
     * costs one page per node rather than growing with depth.
     */
    default SearchResult searchShardTopK(
            String queryString,
            String nodeId,
            String shardId,
            int topK,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            Duration deadline,
            SortOptions sortOptions) {
        return searchShardTopK(
                queryString, nodeId, shardId, topK, searchType, filters, highlight, facetRequests, deadline);
    }

    /**
     * Node-local top-K with an optional stored-field response projection. A null projection keeps
     * legacy behavior; an empty list requests identity and traversal metadata only.
     */
    default SearchResult searchShardTopK(
            String queryString,
            String nodeId,
            String shardId,
            int topK,
            SearchType searchType,
            List<Filter> filters,
            boolean highlight,
            List<FacetRequest> facetRequests,
            Duration deadline,
            SortOptions sortOptions,
            List<String> storedFields) {
        return searchShardTopK(
                queryString,
                nodeId,
                shardId,
                topK,
                searchType,
                filters,
                highlight,
                facetRequests,
                deadline,
                sortOptions);
    }

    default SearchResult mapToSearchResult(IndexSearchResponse grpcResp, int page) {
        List<SearchHit> hits = new ArrayList<>();
        for (com.danieljhkim.dsearch.proto.index.IndexHit hit : grpcResp.getHitsList()) {
            Map<String, String> highlightedFields = hit.getHighlightedFieldsMap();
            Map<String, String> fields = hit.getFieldsMap();
            Map<String, String> highlightedFieldsMap = highlightedFields.isEmpty() ? null : highlightedFields;
            Map<String, String> fieldsMap = fields.isEmpty() ? null : fields;
            List<SortValue> sortValues = hit.getSortValuesCount() == 0 ? null : hit.getSortValuesList();
            hits.add(new SearchHit(
                    hit.getDocId(),
                    hit.hasTitle() ? hit.getTitle() : null,
                    hit.hasContent() ? hit.getContent() : null,
                    hit.getScore(),
                    highlightedFieldsMap,
                    fieldsMap,
                    sortValues));
        }
        List<FacetResponse> facets = grpcResp.getFacetsList();
        return new SearchResult(hits, grpcResp.getTotalHits(), page, facets.isEmpty() ? null : facets);
    }
}
