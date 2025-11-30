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

}