package com.danieljhkim.dsearch.gateway.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** A bounded set of document upserts for one partition. */
@Getter
@Setter
public class BulkIndexRequestDto {

    @Size(max = 64, message = "partitionId must be at most 64 characters") @Pattern(
            regexp = "[A-Za-z0-9_-]+",
            message = "partitionId may contain only letters, numbers, underscores, and hyphens")
    private String partitionId;

    private List<IndexRequestDto> items;
}
