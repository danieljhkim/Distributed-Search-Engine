package com.danieljhkim.dsearch.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AliasSwapRequestDto {
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]+")
    private String alias;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]+")
    private String targetIndex;
}
