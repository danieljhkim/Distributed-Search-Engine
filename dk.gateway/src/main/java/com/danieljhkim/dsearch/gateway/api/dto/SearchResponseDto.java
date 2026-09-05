package com.danieljhkim.dsearch.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SearchResponseDto {

    private List<SearchHitDto> hits;
    private long totalHits;
    private long tookMillis;
    private int page;
    private int pageSize;
    private List<FacetResponseDto> facets;
    private FanoutMetadataDto fanout;
    /**
     * Opaque cursor for the next page. Null when the result set is exhausted, or when the request
     * shape does not support cursor traversal.
     */
    private String nextCursor;

    public SearchResponseDto() {}

    public SearchResponseDto(List<SearchHitDto> hits, long totalHits, long tookMillis) {
        this.hits = hits;
        this.totalHits = totalHits;
        this.tookMillis = tookMillis;
        this.page = 0;
        this.pageSize = hits.size();
    }

    public SearchResponseDto(List<SearchHitDto> hits, long totalHits, long tookMillis, int page) {
        this.hits = hits;
        this.totalHits = totalHits;
        this.tookMillis = tookMillis;
        this.page = page;
        this.pageSize = hits.size();
    }

    @Setter
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SearchHitDto {
        private String docId;
        private double score;
        private String title;
        private String content;
        private Map<String, String> highlightedFields;
        private Map<String, String> fields;

        public SearchHitDto() {}

        public SearchHitDto(String docId, String title, String content, double score) {
            this.docId = docId;
            this.score = score;
            this.title = title;
            this.content = content;
        }

        public SearchHitDto(
                String docId, String title, String content, double score, Map<String, String> highlightedFields) {
            this.docId = docId;
            this.score = score;
            this.title = title;
            this.content = content;
            this.highlightedFields = highlightedFields;
        }

        public SearchHitDto(
                String docId,
                String title,
                String content,
                double score,
                Map<String, String> highlightedFields,
                Map<String, String> fields) {
            this.docId = docId;
            this.score = score;
            this.title = title;
            this.content = content;
            this.highlightedFields = highlightedFields;
            this.fields = fields;
        }
    }

    @Setter
    @Getter
    public static class FanoutMetadataDto {
        private String status;
        private int attemptedNodes;
        private int succeededNodes;
        private int failedNodes;
        private int timedOutNodes;

        public FanoutMetadataDto() {}

        public FanoutMetadataDto(
                String status, int attemptedNodes, int succeededNodes, int failedNodes, int timedOutNodes) {
            this.status = status;
            this.attemptedNodes = attemptedNodes;
            this.succeededNodes = succeededNodes;
            this.failedNodes = failedNodes;
            this.timedOutNodes = timedOutNodes;
        }
    }
}
