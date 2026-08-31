package com.danieljhkim.dsearch.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

/** Ordered outcome for one bulk item. */
@Getter
@Setter
public class BulkIndexItemResponseDto {

    private int requestIndex;
    private String id;
    private String status;
    private String error;

    public BulkIndexItemResponseDto() {}

    public BulkIndexItemResponseDto(int requestIndex, String id, String status, String error) {
        this.requestIndex = requestIndex;
        this.id = id;
        this.status = status;
        this.error = error;
    }
}
