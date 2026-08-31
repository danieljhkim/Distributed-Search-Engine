package com.danieljhkim.dsearch.indexnode.index.facet;

import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.proto.common.FacetBucket;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import io.grpc.Context;
import io.grpc.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexReader.CacheHelper;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

/**
 * Computes facets using Lucene's Facets API.
 * Uses SortedSetDocValuesFacetCounts for string facets.
 */
public class FacetCalculator {

    private static final Logger LOGGER = Logger.getLogger(FacetCalculator.class.getName());

    @Getter
    private final FacetsConfig facetsConfig;

    private final long maxExpandedBuckets;
    private final BooleanSupplier cancellationRequested;

    // Cache SortedSetDocValuesReaderState per IndexReader to avoid rebuilding it
    private volatile Object cachedReaderKey;
    private volatile SortedSetDocValuesReaderState cachedState;

    public FacetCalculator() {
        this(new FacetsConfig());
    }

    public FacetCalculator(FacetsConfig facetsConfig) {
        this(
                facetsConfig,
                RequestLimitsValidator.limitsOrDefaults(null).getMaxFacetExpandedBuckets(),
                () -> Context.current().isCancelled());
    }

    FacetCalculator(FacetsConfig facetsConfig, long maxExpandedBuckets, BooleanSupplier cancellationRequested) {
        this.facetsConfig = facetsConfig;
        if (maxExpandedBuckets < 1) {
            throw new IllegalArgumentException("maxExpandedBuckets must be greater than 0");
        }
        this.maxExpandedBuckets = maxExpandedBuckets;
        this.cancellationRequested = Objects.requireNonNull(cancellationRequested, "cancellationRequested");
    }

    /**
     * Computes facets for the given search query and facet requests.
     *
     * @param searcher
     *            the index searcher
     * @param query
     *            the search query
     * @param facetRequests
     *            the list of facet requests
     * @return list of facet responses
     */
    public List<FacetResponse> computeFacets(IndexSearcher searcher, Query query, List<FacetRequest> facetRequests) {
        if (facetRequests == null || facetRequests.isEmpty()) {
            return List.of();
        }
        validateSupportedRequests(facetRequests);
        FacetWorkBudget workBudget = new FacetWorkBudget(maxExpandedBuckets, cancellationRequested);

        try {
            return computeFacetResponses(searcher, query, facetRequests, workBudget);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to compute facets", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // This can happen if there are no facet fields indexed
            LOGGER.log(Level.FINE, "No facet fields found in index", e);
        }

        return List.of();
    }

    /**
     * Computes facets using a caller-provided FacetsCollector. This lets you
     * collect
     * hits and facets in the same search pass (avoids executing the query twice).
     */
    public List<FacetResponse> computeFacets(
            IndexSearcher searcher, FacetsCollector fc, List<FacetRequest> facetRequests) {
        List<FacetResponse> responses = new ArrayList<>();
        if (facetRequests == null || facetRequests.isEmpty()) {
            return responses;
        }
        validateSupportedRequests(facetRequests);
        if (containsNestedRequest(facetRequests)) {
            throw new IllegalArgumentException("Nested facets require query-backed computation");
        }

        try {
            FacetWorkBudget workBudget = new FacetWorkBudget(maxExpandedBuckets, cancellationRequested);
            workBudget.checkpoint();
            IndexReader reader = searcher.getIndexReader();
            SortedSetDocValuesReaderState state = getOrCreateState(reader);
            Facets facets = new SortedSetDocValuesFacetCounts(state, fc);

            for (FacetRequest request : facetRequests) {
                workBudget.checkpoint();
                responses.add(computeSingleFacet(searcher, null, facets, request, workBudget));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to compute facets", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // This can happen if there are no facet fields indexed
            LOGGER.log(Level.FINE, "No facet fields found in index", e);
        }

        return responses;
    }

    private List<FacetResponse> computeFacetResponses(
            IndexSearcher searcher, Query query, List<FacetRequest> facetRequests, FacetWorkBudget workBudget)
            throws IOException {
        workBudget.checkpoint();
        FacetsCollector collector = new FacetsCollector();
        searcher.search(query, collector);
        Facets facets = new SortedSetDocValuesFacetCounts(getOrCreateState(searcher.getIndexReader()), collector);
        List<FacetResponse> responses = new ArrayList<>(facetRequests.size());
        for (FacetRequest request : facetRequests) {
            workBudget.checkpoint();
            responses.add(computeSingleFacet(searcher, query, facets, request, workBudget));
        }
        return responses;
    }

    private SortedSetDocValuesReaderState getOrCreateState(IndexReader reader) throws IOException {
        Objects.requireNonNull(reader, "reader");

        Object key = null;
        CacheHelper helper = reader.getReaderCacheHelper();
        if (helper != null) {
            key = helper.getKey();
        }
        if (key == null) {
            // Fallback: identity-based key (should be stable for this reader instance)
            key = System.identityHashCode(reader);
        }

        SortedSetDocValuesReaderState state = cachedState;
        Object cachedKey = cachedReaderKey;
        if (state != null && key.equals(cachedKey)) {
            return state;
        }

        synchronized (this) {
            state = cachedState;
            cachedKey = cachedReaderKey;
            if (state != null && key.equals(cachedKey)) {
                return state;
            }
            SortedSetDocValuesReaderState newState = new DefaultSortedSetDocValuesReaderState(reader, facetsConfig);
            cachedReaderKey = key;
            cachedState = newState;
            return newState;
        }
    }

    /**
     * Computes a single facet.
     */
    private FacetResponse computeSingleFacet(
            IndexSearcher searcher, Query query, Facets facets, FacetRequest request, FacetWorkBudget workBudget) {
        String field = request.getField();

        try {
            // Node responses are intermediate distributed-aggregation inputs. Applying the
            // requested size here can discard a bucket that is globally dominant after other
            // nodes contribute their counts. The query node applies the public size only after
            // merging these complete local candidate sets.
            FacetResult result = facets.getAllChildren(field);
            FacetResponse.Builder responseBuilder = FacetResponse.newBuilder().setField(field);

            if (result != null && result.labelValues != null) {
                for (LabelAndValue lv : Arrays.stream(result.labelValues)
                        .sorted(Comparator.comparingLong((LabelAndValue value) -> value.value.longValue())
                                .reversed()
                                .thenComparing(value -> value.label))
                        .toList()) {
                    workBudget.expandBucket();
                    FacetBucket.Builder bucketBuilder =
                            FacetBucket.newBuilder().setValue(lv.label).setCount(lv.value.longValue());

                    if (request.getNestedCount() > 0) {
                        if (query == null) {
                            throw new IllegalArgumentException("Nested facets require query-backed computation");
                        }
                        DrillDownQuery bucketQuery = new DrillDownQuery(facetsConfig, query);
                        bucketQuery.add(field, lv.label);
                        bucketBuilder.addAllNested(
                                computeFacetResponses(searcher, bucketQuery, request.getNestedList(), workBudget));
                    }

                    responseBuilder.addBuckets(bucketBuilder.build());
                }
            }
            return responseBuilder.build();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to compute facet for field: " + field, e);
            // Return empty facet response instead of null
            return FacetResponse.newBuilder().setField(field).build();
        }
    }

    private static void validateSupportedRequests(List<FacetRequest> facetRequests) {
        for (FacetRequest request : facetRequests) {
            if (request.getFiltersCount() > 0) {
                throw new IllegalArgumentException(
                        "Facet-level filters are not supported; use top-level search filters instead");
            }
            validateSupportedRequests(request.getNestedList());
        }
    }

    private static boolean containsNestedRequest(List<FacetRequest> facetRequests) {
        return facetRequests.stream().anyMatch(request -> request.getNestedCount() > 0);
    }

    private static final class FacetWorkBudget {
        private final long maximumBuckets;
        private final BooleanSupplier cancellationRequested;
        private long expandedBuckets;

        private FacetWorkBudget(long maximumBuckets, BooleanSupplier cancellationRequested) {
            this.maximumBuckets = maximumBuckets;
            this.cancellationRequested = cancellationRequested;
        }

        private void checkpoint() {
            if (cancellationRequested.getAsBoolean()) {
                Context context = Context.current();
                Status status =
                        context.getDeadline() != null && context.getDeadline().isExpired()
                                ? Status.DEADLINE_EXCEEDED
                                : Status.CANCELLED;
                throw status.withDescription("Facet calculation cancelled before recursive expansion completed")
                        .asRuntimeException();
            }
        }

        private void expandBucket() {
            checkpoint();
            if (expandedBuckets >= maximumBuckets) {
                throw Status.RESOURCE_EXHAUSTED
                        .withDescription("Facet calculation exceeded expanded bucket limit (" + maximumBuckets + ")")
                        .asRuntimeException();
            }
            expandedBuckets++;
        }
    }
}
