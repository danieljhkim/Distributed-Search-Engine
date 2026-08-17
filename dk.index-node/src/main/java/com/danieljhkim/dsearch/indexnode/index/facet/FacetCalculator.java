package com.danieljhkim.dsearch.indexnode.index.facet;

import com.danieljhkim.dsearch.proto.common.FacetBucket;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
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
    private static final int DEFAULT_TOP_N = 10;

    @Getter
    private final FacetsConfig facetsConfig;

    // Cache SortedSetDocValuesReaderState per IndexReader to avoid rebuilding it
    private volatile Object cachedReaderKey;
    private volatile SortedSetDocValuesReaderState cachedState;

    public FacetCalculator() {
        this.facetsConfig = new FacetsConfig();
    }

    public FacetCalculator(FacetsConfig facetsConfig) {
        this.facetsConfig = facetsConfig;
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
        List<FacetResponse> responses = new ArrayList<>();
        if (facetRequests == null || facetRequests.isEmpty()) {
            return responses;
        }

        try {
            FacetsCollector fc = new FacetsCollector();
            searcher.search(query, fc);
            responses.addAll(computeFacets(searcher, fc, facetRequests));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to compute facets", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // This can happen if there are no facet fields indexed
            LOGGER.log(Level.FINE, "No facet fields found in index", e);
        }

        return responses;
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

        try {
            IndexReader reader = searcher.getIndexReader();
            SortedSetDocValuesReaderState state = getOrCreateState(reader);
            Facets facets = new SortedSetDocValuesFacetCounts(state, fc);

            for (FacetRequest request : facetRequests) {
                responses.add(computeSingleFacet(facets, request));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to compute facets", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // This can happen if there are no facet fields indexed
            LOGGER.log(Level.FINE, "No facet fields found in index", e);
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
            SortedSetDocValuesReaderState newState = new DefaultSortedSetDocValuesReaderState(reader);
            cachedReaderKey = key;
            cachedState = newState;
            return newState;
        }
    }

    /**
     * Computes a single facet.
     */
    private FacetResponse computeSingleFacet(Facets facets, FacetRequest request) {
        String field = request.getField();
        int topN = request.getSize() > 0 ? request.getSize() : DEFAULT_TOP_N;

        try {
            FacetResult result = facets.getTopChildren(topN, field);
            FacetResponse.Builder responseBuilder = FacetResponse.newBuilder().setField(field);

            if (result != null && result.labelValues != null) {
                for (LabelAndValue lv : result.labelValues) {
                    FacetBucket.Builder bucketBuilder =
                            FacetBucket.newBuilder().setValue(lv.label).setCount(lv.value.longValue());

                    // Handle nested facets if requested
                    if (request.getNestedCount() > 0) {
                        LOGGER.log(Level.FINE, "Nested facets not yet fully implemented");
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
}
