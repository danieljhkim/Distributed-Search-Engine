package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnalyzeResponseDto {
    private String indexName;
    private String alias;
    private String analyzer;
    private List<AnalyzedTokenDto> tokens;
    private boolean truncated;

    @Getter
    @Setter
    public static class AnalyzedTokenDto {
        private String token;
        private int position;
        private int startOffset;
        private int endOffset;
    }
}
