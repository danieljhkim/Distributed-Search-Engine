package com.dk.dsearch.common.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SearchHit {
    private final String docId;
    private final float score;
    private String content;
    private String title;
    private int rank;
    public double normScore;

    public SearchHit(String docId, float score) {
        this.docId = docId;
        this.score = score;
    }

    public SearchHit(String docId, String title, String content, float score) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
    }

    public SearchHit(String docId, String title, String content, float score, int rank) {
        this.docId = docId;
        this.score = score;
        this.content = content;
        this.title = title;
        this.rank = rank;
    }

}