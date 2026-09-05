package com.danieljhkim.dsearch.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnalyzeRequestDto {
    @NotBlank private String text;
}
