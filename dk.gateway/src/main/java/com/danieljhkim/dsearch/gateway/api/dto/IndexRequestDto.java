package com.danieljhkim.dsearch.gateway.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class IndexRequestDto {
    private String id; // optional; if absent, server generates
    private Map<String, String> fields;
    /** Stable retry identity. Omitted values are generated for this request. */
    private String operationId;
    /**
     * Caller-monotonic document generation. When omitted, the authoritative shard primary
     * durably allocates the next generation for this document.
     */
    private Long generation;

    @Size(max = 64, message = "partitionId must be at most 64 characters") @Pattern(
            regexp = "[A-Za-z0-9_-]+",
            message = "partitionId may contain only letters, numbers, underscores, and hyphens")
    private String partitionId;

    public IndexRequestDto() {}
}
