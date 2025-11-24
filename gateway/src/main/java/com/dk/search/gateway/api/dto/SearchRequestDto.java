package com.dk.search.gateway.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SearchRequestDto {

    @NotBlank(message = "query must not be blank")
    @Size(max = 512, message = "query must be at most 512 characters")
    private String query;

    private String shardId;

    @Min(value = 1, message = "topK must be > 0")
    private int topK = 30;

    @Min(value = 0, message = "page must be >= 0")
    private int page = 0;

    @Min(value = 1, message = "pageSize must be > 0")
    private int pageSize = 10;

    public SearchRequestDto() {
    }

}