package com.danieljhkim.dsearch.common.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexAlias {
    private String alias;
    private String indexName;
    private String previousIndexName;
    private int generation = 1;

    public IndexAlias() {}

    public IndexAlias(String alias, String indexName, String previousIndexName, int generation) {
        this.alias = alias;
        this.indexName = indexName;
        this.previousIndexName = previousIndexName;
        this.generation = generation;
    }

    public IndexAlias copy() {
        return new IndexAlias(alias, indexName, previousIndexName, generation);
    }
}
