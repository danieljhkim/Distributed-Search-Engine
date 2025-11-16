package com.dk.search.common.model;

import lombok.Getter;

import java.util.List;

@Getter
public class SearchResult {

    private final List<SearchHit> hits;
    private final long totalHits;
    private final long tookMillis;
    private final int page;
    private final int page_size;

    public SearchResult(List<SearchHit> hits, long totalHits, long tookMillis) {
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.tookMillis = tookMillis;
        this.page = 0;
        this.page_size = hits.size();
    }

    public SearchResult(List<SearchHit> hits, long totalHits, long tookMicros, int page) {
        this.hits = List.copyOf(hits);
        this.totalHits = totalHits;
        this.page = page;
        this.page_size = hits.size();
        this.tookMillis = tookMicros / 1000;
    }

}