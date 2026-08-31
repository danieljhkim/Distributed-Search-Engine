package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.gateway.api.dto.FacetRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SortDto;
import java.util.ArrayDeque;

/** Validates recursive HTTP DTO structure before the recursive protobuf mapper is invoked. */
final class GatewayRequestValidator {

    private GatewayRequestValidator() {}

    static void validateSearch(SearchRequestDto request, AppConfig.RequestLimitsConfig limits) {
        validateSearch(request, limits, new AppConfig.PaginationConfig());
    }

    static void validateSearch(
            SearchRequestDto request,
            AppConfig.RequestLimitsConfig limits,
            AppConfig.PaginationConfig paginationLimits) {
        boolean resuming = request.getCursor() != null && !request.getCursor().isBlank();
        if (resuming) {
            // Rejected here rather than silently preferring one: honouring the cursor would ignore
            // a page the caller asked for, and honouring the page would re-read from the top.
            if (request.getPage() != 0) {
                throw new IllegalArgumentException(
                        "cursor and page are mutually exclusive; omit page when resuming from a cursor");
            }
            RequestLimitsValidator.validateCursorWindow(request.getQuery(), request.getPageSize(), limits);
        } else {
            RequestLimitsValidator.validateSearchWindow(
                    request.getQuery(), request.getPage(), request.getPageSize(), limits);
        }
        validateSort(request, paginationLimits);
        int filterClauses =
                request.getFilters() == null ? 0 : request.getFilters().size();
        if (filterClauses > limits.getMaxFilterClauses()) {
            throw new IllegalArgumentException(
                    "Filter clause count exceeds maximum allowed (" + limits.getMaxFilterClauses() + ")");
        }

        int facetCount = 0;
        ArrayDeque<FacetAtDepth> pending = new ArrayDeque<>();
        if (request.getFacets() != null) {
            request.getFacets().forEach(facet -> pending.addLast(new FacetAtDepth(facet, 1, 1L)));
        }
        long expandedBucketUpperBound = 0L;
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
            int effectiveSize = facetSize != null ? facetSize : RequestLimitsValidator.DEFAULT_FACET_SIZE;
            if (effectiveSize > limits.getMaxSize()) {
                throw new IllegalArgumentException("Facet size must be between 1 and " + limits.getMaxSize());
            }
            RequestLimitsValidator.FacetBucketUpperBound upperBound =
                    RequestLimitsValidator.accumulateFacetBucketUpperBound(
                            expandedBucketUpperBound,
                            current.parentBucketUpperBound(),
                            effectiveSize,
                            limits.getMaxFacetExpandedBuckets());
            long nodeBucketUpperBound = upperBound.nodeBuckets();
            expandedBucketUpperBound = upperBound.expandedBuckets();
            if (current.facet().getFilters() != null
                    && !current.facet().getFilters().isEmpty()) {
                throw new IllegalArgumentException(
                        "Facet-level filters are not supported; use top-level search filters instead");
            }
            if (current.facet().getNested() != null) {
                current.facet()
                        .getNested()
                        .forEach(nested ->
                                pending.addLast(new FacetAtDepth(nested, current.depth() + 1, nodeBucketUpperBound)));
            }
        }
    }

    private static void validateSort(SearchRequestDto request, AppConfig.PaginationConfig paginationLimits) {
        if (request.getSort() == null || request.getSort().isEmpty()) {
            return;
        }
        AppConfig.PaginationConfig effective = RequestLimitsValidator.paginationOrDefaults(paginationLimits);
        int maxSortFields = Math.max(1, effective.getMaxSortFields());
        if (request.getSort().size() > maxSortFields) {
            throw new IllegalArgumentException("Sort field count ("
                    + request.getSort().size() + ") exceeds maximum allowed (" + maxSortFields + ")");
        }
        for (SortDto sort : request.getSort()) {
            if (sort == null || sort.getField() == null || sort.getField().isBlank()) {
                throw new IllegalArgumentException("sort field must not be blank");
            }
            if (sort.getOrder() != null
                    && !sort.getOrder().equalsIgnoreCase("asc")
                    && !sort.getOrder().equalsIgnoreCase("desc")) {
                throw new IllegalArgumentException("sort order must be 'asc' or 'desc'");
            }
        }
    }

    private record FacetAtDepth(FacetRequestDto facet, int depth, long parentBucketUpperBound) {}
}
