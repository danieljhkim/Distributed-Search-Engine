package com.danieljhkim.dsearch.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class IndexResponseDto {

    private String id;
    private boolean success;

    public IndexResponseDto() {}

    public IndexResponseDto(String id, boolean success) {
        this.id = id;
        this.success = success;
    }
}
