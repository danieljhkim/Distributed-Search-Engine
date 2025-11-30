package com.dk.dsearch.common.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SearchResult {

    private final List<SearchHit> hits;
    private final long totalHits;
    private final int page_size;
    private int page;

    public SearchResult(List<SearchHit> hits, long totalHits, int page) {
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.page = page;
        this.page_size = hits.size();
    }

    public SearchResult(List<SearchHit> hits, long totalHits) {
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.page_size = hits.size();
    }
}