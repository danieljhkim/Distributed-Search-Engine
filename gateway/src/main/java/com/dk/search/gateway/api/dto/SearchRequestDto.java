package com.dk.search.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class SearchRequestDto {

    private String query;
    private Integer topK;
    private List<Integer> shardIds;   // optional; default [0] or all

    public SearchRequestDto() {
    }

}