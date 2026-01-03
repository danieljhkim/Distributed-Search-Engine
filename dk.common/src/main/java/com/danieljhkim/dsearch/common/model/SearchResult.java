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
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.page = page;
        this.page_size = hits.size();
        this.facets = facets != null ? List.copyOf(facets) : null;
    }
}
