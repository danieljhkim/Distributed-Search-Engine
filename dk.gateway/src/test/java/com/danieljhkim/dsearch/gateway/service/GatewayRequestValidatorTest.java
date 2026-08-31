package com.danieljhkim.dsearch.gateway.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.gateway.api.dto.FacetRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SortDto;
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

    @Test
    void aCursorCannotBeCombinedWithOffsetPaging() {
        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("query");
        request.setPageSize(10);
        request.setPage(2);
        request.setCursor("v1.payload.signature");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> GatewayRequestValidator.validateSearch(request, new AppConfig.RequestLimitsConfig()));

        assertTrue(exception.getMessage().contains("mutually exclusive"));
    }

    @Test
    void aCursorPageIsNotBoundedByTheOffsetWindow() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxResultWindow(20);

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("query");
        request.setPageSize(10);
        request.setCursor("v1.payload.signature");

        assertDoesNotThrow(() -> GatewayRequestValidator.validateSearch(request, limits));
    }

    @Test
    void deepOffsetPagingIsRejectedAtTheConfiguredWindow() {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxResultWindow(100);

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("query");
        request.setPageSize(10);
        request.setPage(10);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> GatewayRequestValidator.validateSearch(request, limits));
        assertTrue(exception.getMessage().contains("use cursor pagination"));
    }

    @Test
    void sortFieldCountIsBounded() {
        AppConfig.PaginationConfig pagination = new AppConfig.PaginationConfig();
        pagination.setMaxSortFields(1);

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("query");
        request.setPageSize(10);
        request.setSort(List.of(new SortDto("price", "asc"), new SortDto("year", "desc")));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> GatewayRequestValidator.validateSearch(request, new AppConfig.RequestLimitsConfig(), pagination));
        assertTrue(exception.getMessage().contains("Sort field count"));
    }

    @Test
    void anUnknownSortDirectionIsRejected() {
        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("query");
        request.setPageSize(10);
        request.setSort(List.of(new SortDto("price", "sideways")));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> GatewayRequestValidator.validateSearch(request, new AppConfig.RequestLimitsConfig()));
        assertTrue(exception.getMessage().contains("'asc' or 'desc'"));
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
