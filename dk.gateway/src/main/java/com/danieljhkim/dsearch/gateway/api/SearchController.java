package com.danieljhkim.dsearch.gateway.api;

import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.gateway.service.GatewaySearchService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final GatewaySearchService searchService;
    private final MeterRegistry meterRegistry;

    public SearchController(GatewaySearchService searchService, MeterRegistry meterRegistry) {
        this.searchService = searchService;
        this.meterRegistry = meterRegistry;
    }

    @Timed(value = "dsearch.search.http", extraTags = {"endpoint", "/api/v1/search"})
    @PostMapping(consumes = "application/json", produces = "application/json")
    public Page<SearchResponseDto.SearchHitDto> search(@Valid @RequestBody SearchRequestDto req) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return searchService.search(req);
        } finally {
            sample.stop(
                    Timer.builder("dsearch.gateway.search.latency")
                            .tag("searchType", req.getSearchType() != null ? req.getSearchType().name() : "UNKNOWN")
                            .tag("shardId", req.getShardId() != null ? req.getShardId() : "UNKNOWN")
                            .register(meterRegistry)
            );
        }
    }
}