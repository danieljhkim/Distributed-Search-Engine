package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.gateway.api.dto.FacetRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import java.util.ArrayDeque;

/** Validates recursive HTTP DTO structure before the recursive protobuf mapper is invoked. */
final class GatewayRequestValidator {

    private GatewayRequestValidator() {}

    static void validateSearch(SearchRequestDto request, AppConfig.RequestLimitsConfig limits) {
        RequestLimitsValidator.validateSearchWindow(
                request.getQuery(), request.getPage(), request.getPageSize(), limits);
        int filterClauses =
                request.getFilters() == null ? 0 : request.getFilters().size();
        if (filterClauses > limits.getMaxFilterClauses()) {
            throw new IllegalArgumentException(
                    "Filter clause count exceeds maximum allowed (" + limits.getMaxFilterClauses() + ")");
        }

        int facetCount = 0;
        ArrayDeque<FacetAtDepth> pending = new ArrayDeque<>();
        if (request.getFacets() != null) {
            request.getFacets().forEach(facet -> pending.addLast(new FacetAtDepth(facet, 1)));
        }
        while (!pending.isEmpty()) {
            FacetAtDepth current = pending.removeFirst();
            facetCount++;
            if (facetCount > limits.getMaxFacetCount()) {
                throw new IllegalArgumentException(
                        "Facet count exceeds maximum allowed (" + limits.getMaxFacetCount() + ")");
            }
            if (current.depth() > limits.getMaxFacetDepth()) {
                throw new IllegalArgumentException("Facet depth (" + current.depth() + ") exceeds maximum allowed ("
                        + limits.getMaxFacetDepth() + ")");
            }
            Integer facetSize = current.facet().getSize();
            if (facetSize != null && (facetSize < 1 || facetSize > limits.getMaxSize())) {
                throw new IllegalArgumentException("Facet size must be between 1 and " + limits.getMaxSize());
            }
            if (current.facet().getFilters() != null
                    && !current.facet().getFilters().isEmpty()) {
                throw new IllegalArgumentException(
                        "Facet-level filters are not supported; use top-level search filters instead");
            }
            if (current.facet().getNested() != null) {
                current.facet()
                        .getNested()
                        .forEach(nested -> pending.addLast(new FacetAtDepth(nested, current.depth() + 1)));
            }
        }
    }

    private record FacetAtDepth(FacetRequestDto facet, int depth) {}
}
