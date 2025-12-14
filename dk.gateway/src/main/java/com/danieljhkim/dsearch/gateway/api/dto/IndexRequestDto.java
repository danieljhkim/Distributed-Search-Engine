package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class IndexRequestDto {
	private String id; // optional; if absent, server generates
	private Map<String, String> fields;
	private String partitionId;

	public IndexRequestDto() {
	}

}