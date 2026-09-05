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

    public BulkDeleteItemResponseDto() {}

    public BulkDeleteItemResponseDto(int requestIndex, String id, String status, String error) {
        this.requestIndex = requestIndex;
        this.id = id;
        this.status = status;
        this.error = error;
    }
}
