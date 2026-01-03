package com.danieljhkim.dsearch.common.model;

import java.util.Map;
import lombok.Getter;

@Getter
public class SearchDocument {
    private final String id;
    private final Map<String, String> fields;

    public SearchDocument(String id, Map<String, String> fields) {
        this.id = id;
        this.fields = Map.copyOf(fields);
    }
}
