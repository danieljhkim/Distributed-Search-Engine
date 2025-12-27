package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO for facet bucket in facet responses.
 */
@Setter
@Getter
public class FacetBucketDto {

	private String value;
	private Long count;
	private List<FacetResponseDto> nested;

	public FacetBucketDto() {
	}

	public FacetBucketDto(String value, Long count) {
		this.value = value;
		this.count = count;
	}

	public FacetBucketDto(String value, Long count, List<FacetResponseDto> nested) {
		this.value = value;
		this.count = count;
		this.nested = nested;
	}
}
