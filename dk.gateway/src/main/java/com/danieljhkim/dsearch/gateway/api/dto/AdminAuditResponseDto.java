package com.danieljhkim.dsearch.gateway.api.dto;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditResponseDto {
    private String auditId;
    private Instant timestamp;
    private String operation;
    private String actor;
    private boolean success;
    private String alias;
    private String indexName;
    private String previousIndexName;
    private String message;
    private Map<String, Object> details;
}
