package com.danieljhkim.dsearch.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

/** Ordered outcome for one bulk delete item. */
@Getter
@Setter
public class BulkDeleteItemResponseDto {

    private int requestIndex;
    private String id;
    private String status;
    private String error;
    private String operationId;
    private Long generation;

    public BulkDeleteItemResponseDto() {}

    public BulkDeleteItemResponseDto(int requestIndex, String id, String status, String error) {
        this(requestIndex, id, status, error, null, null);
    }

    public BulkDeleteItemResponseDto(
            int requestIndex, String id, String status, String error, String operationId, Long generation) {
        this.requestIndex = requestIndex;
        this.id = id;
        this.status = status;
        this.error = error;
        this.operationId = operationId;
        this.generation = generation;
    }
}
