package com.dk.dsearch.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class SearchResponseDto {

    private List<SearchHitDto> hits;
    private long totalHits;
    private long tookMillis;
    private int page;
    private int pageSize;

    public SearchResponseDto() {
    }

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
    public static class SearchHitDto {
        private String docId;
        private double score;

        public SearchHitDto() {
        }

        public SearchHitDto(String docId, double score) {
            this.docId = docId;
            this.score = score;
        }

    }
}