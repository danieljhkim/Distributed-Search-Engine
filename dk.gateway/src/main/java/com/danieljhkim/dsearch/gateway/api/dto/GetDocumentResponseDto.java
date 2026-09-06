package com.danieljhkim.dsearch.gateway.api.dto;

import java.util.Map;

/** Stored fields returned after an authoritative exact-id lookup. */
public record GetDocumentResponseDto(String partitionId, String id, Map<String, String> fields) {}
