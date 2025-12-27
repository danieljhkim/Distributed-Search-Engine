package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SearchResponseDto {

	private List<SearchHitDto> hits;
	private long totalHits;
	private long tookMillis;
	private int page;
	private int pageSize;
	private List<FacetResponseDto> facets;

	public SearchResponseDto() {
	}

	public SearchResponseDto(List<SearchHitDto> hits, long totalHits, long tookMillis) {
		this.hits = hits;
		this.totalHits = totalHits;
		this.tookMillis = tookMillis;
		this.page = 0;
		this.pageSize = hits.size();
	}

	public SearchResponseDto(List<SearchHitDto> hits, long totalHits, long tookMillis, int page) {
		this.hits = hits;
		this.totalHits = totalHits;
		this.tookMillis = tookMillis;
		this.page = page;
		this.pageSize = hits.size();
	}

	@Setter
	@Getter
	public static class SearchHitDto {
		private String docId;
		private double score;
		private String title;
		private String content;
		private Map<String, String> highlightedFields;
		private Map<String, String> fields;

		public SearchHitDto() {
		}

		public SearchHitDto(String docId, String title, String content, double score) {
			this.docId = docId;
			this.score = score;
			this.title = title;
			this.content = content;
		}

		public SearchHitDto(String docId, String title, String content, double score,
				Map<String, String> highlightedFields) {
			this.docId = docId;
			this.score = score;
			this.title = title;
			this.content = content;
			this.highlightedFields = highlightedFields;
		}

		public SearchHitDto(String docId, String title, String content, double score,
				Map<String, String> highlightedFields, Map<String, String> fields) {
			this.docId = docId;
			this.score = score;
			this.title = title;
			this.content = content;
			this.highlightedFields = highlightedFields;
			this.fields = fields;
		}
	}
}