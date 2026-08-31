package com.danieljhkim.dsearch.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReindexRequestDto {
    @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") private String targetIndex;

    private List<RepresentativeQueryDto> verificationQueries = new ArrayList<>();

    @Getter
    @Setter
    public static class RepresentativeQueryDto {
        @NotBlank private String query;

        private String searchType = "BM25";
        private int size = 10;
    }
}
