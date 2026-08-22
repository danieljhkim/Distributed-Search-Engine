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

    @Size(max = 64, message = "partitionId must be at most 64 characters") @Pattern(
            regexp = "[A-Za-z0-9_-]+",
            message = "partitionId may contain only letters, numbers, underscores, and hyphens")
    private String partitionId;

    public IndexRequestDto() {}
}
