package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** The ordered, itemized result of a bulk document deletion. */
@Getter
@Setter
public class BulkDeleteResponseDto {

    private boolean success;
    private List<BulkDeleteItemResponseDto> items;

    public BulkDeleteResponseDto() {}

    public BulkDeleteResponseDto(boolean success, List<BulkDeleteItemResponseDto> items) {
        this.success = success;
        this.items = items;
    }
}
