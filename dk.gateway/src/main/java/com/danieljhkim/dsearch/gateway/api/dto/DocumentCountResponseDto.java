package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;

/** Current committed count, plus logical shards not represented in this observation. */
public record DocumentCountResponseDto(
        String partitionId,
        long documentCount,
        List<String> unavailableLogicalShards,
        List<String> failedLogicalShards) {
    public boolean isComplete() {
        return unavailableLogicalShards.isEmpty() && failedLogicalShards.isEmpty();
    }
}
