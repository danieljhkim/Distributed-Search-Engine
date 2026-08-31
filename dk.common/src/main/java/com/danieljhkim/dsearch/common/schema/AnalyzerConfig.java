package com.danieljhkim.dsearch.common.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyzerConfig(String name) {

    public static final String STANDARD = "standard";
    public static final String KEYWORD = "keyword";

    public AnalyzerConfig {
        name = normalize(name);
    }

    public static AnalyzerConfig standard() {
        return new AnalyzerConfig(STANDARD);
    }

    public static AnalyzerConfig of(String name) {
        return new AnalyzerConfig(name);
    }

    public static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return STANDARD;
        }
        return name.trim();
    }
}
