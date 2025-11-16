package com.dk.search.gateway.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class SearchRequestDto {

    @NotBlank(message = "query must not be blank")
    @Size(max = 512, message = "query must be at most 512 characters")
    private String query;

    private List<Integer> shardIds;

    @Min(value = 0, message = "topk must be >= 0")
    private int topK = 0;

    public SearchRequestDto() {
    }

}