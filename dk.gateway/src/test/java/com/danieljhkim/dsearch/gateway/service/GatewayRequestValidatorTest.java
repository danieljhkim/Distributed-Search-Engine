package com.danieljhkim.dsearch.gateway.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.gateway.api.dto.FacetRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayRequestValidatorTest {

    @Test
    void defaultLimitsAcceptOrdinaryNestedFacetWork() {
        SearchRequestDto request = searchRequestWithNestedFacetSize(10);

        assertDoesNotThrow(() -> GatewayRequestValidator.validateSearch(request, new AppConfig.RequestLimitsConfig()));
    }

    @Test
    void defaultLimitsRejectAmplifiedNestedFacetWorkBeforeMappingOrFanout() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        SearchRequestDto request = searchRequestWithNestedFacetSize(1000);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> GatewayRequestValidator.validateSearch(request, limits));

        assertTrue(exception.getMessage().contains("Expanded facet bucket upper bound"));
        assertTrue(exception.getMessage().contains(Long.toString(limits.getMaxFacetExpandedBuckets())));
    }

    private static SearchRequestDto searchRequestWithNestedFacetSize(int size) {
        FacetRequestDto levelThree = new FacetRequestDto("level-3", size);
        FacetRequestDto levelTwo = new FacetRequestDto("level-2", size, null, List.of(levelThree));
        FacetRequestDto levelOne = new FacetRequestDto("level-1", size, null, List.of(levelTwo));
        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("query");
        request.setPageSize(10);
        request.setFacets(List.of(levelOne));
        return request;
    }
}
