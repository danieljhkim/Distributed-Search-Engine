package com.danieljhkim.dsearch.common.model;

import com.danieljhkim.dsearch.proto.common.FacetResponse;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SearchResult {

    private final List<SearchHit> hits;
    private final long totalHits;
    private final int page_size;
    private int page;
    private List<FacetResponse> facets;
    private FanoutMetadata fanoutMetadata;

    public SearchResult(List<SearchHit> hits, long totalHits, int page) {
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.page = page;
        this.page_size = hits.size();
        this.facets = null;
    }

    public SearchResult(List<SearchHit> hits, long totalHits) {
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.page_size = hits.size();
        this.facets = null;
    }

    public SearchResult(List<SearchHit> hits, long totalHits, int page, List<FacetResponse> facets) {
        this(hits, totalHits, page, facets, null);
    }

    public SearchResult(
            List<SearchHit> hits, long totalHits, int page, List<FacetResponse> facets, FanoutMetadata fanoutMetadata) {
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.page = page;
        this.page_size = hits.size();
        this.facets = facets != null ? List.copyOf(facets) : null;
        this.fanoutMetadata = fanoutMetadata;
    }

    public enum FanoutStatus {
        SUCCESS,
        PARTIAL_FAILURE,
        FAILED
    }

    public record FanoutMetadata(
            int attemptedNodes, int succeededNodes, int failedNodes, int timedOutNodes, int unavailableLogicalRanges) {
        public FanoutMetadata(int attemptedNodes, int succeededNodes, int failedNodes, int timedOutNodes) {
            this(attemptedNodes, succeededNodes, failedNodes, timedOutNodes, 0);
        }

        public FanoutStatus status() {
            if (attemptedNodes == 0 || succeededNodes == 0) {
                return FanoutStatus.FAILED;
            }
            if (failedNodes > 0
                    || timedOutNodes > 0
                    || unavailableLogicalRanges > 0
                    || succeededNodes < attemptedNodes) {
                return FanoutStatus.PARTIAL_FAILURE;
            }
            return FanoutStatus.SUCCESS;
        }

        public static FanoutMetadata combine(FanoutMetadata left, FanoutMetadata right) {
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            return new FanoutMetadata(
                    left.attemptedNodes + right.attemptedNodes,
                    left.succeededNodes + right.succeededNodes,
                    left.failedNodes + right.failedNodes,
                    left.timedOutNodes + right.timedOutNodes,
                    left.unavailableLogicalRanges + right.unavailableLogicalRanges);
        }
    }
}
