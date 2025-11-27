package com.dk.dsearch.gateway.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Setter
@Getter
public class IndexRequestDto {
    private String id;                 // optional; if absent, server generates
    private Map<String, String> fields;
    private String shardId;           // optional; default 0 for now

    public IndexRequestDto() {
    }

}