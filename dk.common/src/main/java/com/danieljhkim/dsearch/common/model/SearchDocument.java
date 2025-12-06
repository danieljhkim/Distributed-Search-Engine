package com.danieljhkim.dsearch.common.model;

import lombok.Getter;

import java.util.Map;

@Getter
public class SearchDocument {
    private final String id;
    private final Map<String, String> fields;

    public SearchDocument(String id, Map<String, String> fields) {
        this.id = id;
        this.fields = Map.copyOf(fields);
    }

}