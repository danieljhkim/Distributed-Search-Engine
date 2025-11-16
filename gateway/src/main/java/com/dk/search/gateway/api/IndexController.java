package com.dk.search.gateway.api;

import com.dk.search.gateway.api.dto.IndexRequestDto;
import com.dk.search.gateway.api.dto.IndexResponseDto;
import com.dk.search.gateway.service.GatewayIndexService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final GatewayIndexService indexService;

    public IndexController(GatewayIndexService indexService) {
        this.indexService = indexService;
    }

    @PostMapping
    public IndexResponseDto indexDocument(@RequestBody IndexRequestDto request) {
        return indexService.index(request);
    }
}