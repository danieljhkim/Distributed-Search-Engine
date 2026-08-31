package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** The ordered, itemized result of a bulk document upsert. */
@Getter
@Setter
public class BulkIndexResponseDto {

    private boolean success;
    private List<BulkIndexItemResponseDto> items;

    public BulkIndexResponseDto() {}

    public BulkIndexResponseDto(boolean success, List<BulkIndexItemResponseDto> items) {
        this.success = success;
        this.items = items;
    }
}
