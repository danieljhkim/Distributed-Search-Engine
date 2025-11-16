
package com.dk.search.gateway.api;

import com.dk.search.gateway.api.dto.SearchRequestDto;
import com.dk.search.gateway.api.dto.SearchResponseDto;
import com.dk.search.gateway.service.GatewaySearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final GatewaySearchService searchService;

    public SearchController(GatewaySearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public SearchResponseDto search(@RequestBody SearchRequestDto request) {
        return searchService.search(request);
    }
}