package com.dk.dsearch.gateway.api;

import com.dk.dsearch.gateway.api.dto.SearchRequestDto;
import com.dk.dsearch.gateway.api.dto.SearchResponseDto;
import com.dk.dsearch.gateway.service.GatewaySearchService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final GatewaySearchService searchService;

    public SearchController(GatewaySearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public Page<SearchResponseDto.SearchHitDto> search(@Valid @RequestBody SearchRequestDto request) {
        return searchService.search(request);
    }
}