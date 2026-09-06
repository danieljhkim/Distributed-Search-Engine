package com.danieljhkim.dsearch.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class IndexResponseDto {

    private String id;
    private boolean success;
    private String operationId;
    private long generation;
    private int acknowledgements;
    private int requiredAcknowledgements;

    public IndexResponseDto() {}

    public IndexResponseDto(String id, boolean success) {
        this.id = id;
        this.success = success;
    }

    public IndexResponseDto(
            String id,
            boolean success,
            String operationId,
            long generation,
            int acknowledgements,
            int requiredAcknowledgements) {
        this.id = id;
        this.success = success;
        this.operationId = operationId;
        this.generation = generation;
        this.acknowledgements = acknowledgements;
        this.requiredAcknowledgements = requiredAcknowledgements;
    }
}
