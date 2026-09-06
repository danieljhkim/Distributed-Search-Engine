package com.danieljhkim.dsearch.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

/** One delete operation whose identity may be reused after an uncertain outcome. */
@Getter
@Setter
public class BulkDeleteItemRequestDto {

    private String id;
    /** Stable retry identity. Omitted values are generated for this item. */
    private String operationId;
    /** Positive document generation, or omitted so the authoritative primary allocates it. */
    private Long generation;

    public BulkDeleteItemRequestDto() {}

    public BulkDeleteItemRequestDto(String id, String operationId, Long generation) {
        this.id = id;
        this.operationId = operationId;
        this.generation = generation;
    }
}
