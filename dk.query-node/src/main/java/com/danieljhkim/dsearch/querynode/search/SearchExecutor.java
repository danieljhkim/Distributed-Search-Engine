package com.danieljhkim.dsearch.querynode.search;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.danieljhkim.dsearch.common.enums.HybridFusionStrategy;
import com.danieljhkim.dsearch.common.enums.SearchType;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.common.FacetBucket;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.FacetResponse;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.querynode.grpc.BaseIndexService;

public class SearchExecutor implements Closeable {

	private static final Logger LOG = LoggerFactory.getLogger(SearchExecutor.class);

	private final ExecutorService shardExecutor;
	private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;
	private final Duration shardTimeout = Duration.ofSeconds(2);

	public SearchExecutor(
			ExecutorService shardExecutor,
			NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
		this.shardExecutor = shardExecutor;
		this.nodeClientManager = nodeClientManager;
	}

	public SearchExecutor(
			NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
		this(Executors.newVirtualThreadPerTaskExecutor(), nodeClientManager);
	}

	public SearchResult searchHybrid(String queryString,
			String shardId,
			int page,
			int size,
			BaseIndexService indexService,
			HybridFusionStrategy fusionStrategy) {
		return searchHybrid(queryString, shardId, page, size, indexService, fusionStrategy, null, false, null);
	}

	public SearchResult searchHybrid(String queryString,
			String shardId,
			int page,
			int size,
			BaseIndexService indexService,
			HybridFusionStrategy fusionStrategy,
			List<Filter> filters,
			boolean highlight,
			List<FacetRequest> facetRequests) {
		int fetchSize = size * (page + 1);
		SearchResult bm25Result = search(
				queryString, shardId, 0, fetchSize, SearchType.BM25, indexService, filters, highlight, facetRequests);
		SearchResult semanticResult = search(
				queryString, shardId, 0, fetchSize, SearchType.SEMANTIC, indexService, filters, highlight,
				facetRequests);

		List<SearchHit> res = HybridFusion.fuse(
				bm25Result, semanticResult, fusionStrategy, fetchSize, 0.5, 0.5);

		List<SearchHit> pageHits = slicePage(res, normalizePage(page), normalizeSize(size));

		// Use facets from BM25 result (should be same as semantic since computed on
		// same filtered query)
		List<FacetResponse> facets = bm25Result.getFacets();

		return new SearchResult(
				pageHits,
				Math.max(semanticResult.getTotalHits(), bm25Result.getTotalHits()), // approx
				normalizePage(page),
				facets);
	}

	/**
	 * Global search across all index nodes for a given shardId.
	 */
	public SearchResult search(String queryString,
			String shardId,
			int page,
			int size,
			SearchType searchType,
			BaseIndexService indexService) {
		return search(queryString, shardId, page, size, searchType, indexService, null, false, null);
	}

	/**
	 * Global search across all index nodes for a given shardId with filters,
	 * highlighting, and facets.
	 */
	@SuppressWarnings("all")
	public SearchResult search(String queryString,
			String shardId,
			int page,
			int size,
			SearchType searchType,
			BaseIndexService indexService,
			List<Filter> filters,
			boolean highlight,
			List<FacetRequest> facetRequests) {

		page = normalizePage(page);
		size = normalizeSize(size);

		String requestId = MDC.get("requestId");
		int requiredForPage = (page + 1) * size;
		int perShardLimit = requiredForPage;
		Map<String, Long> nodeTimingsMs = new ConcurrentHashMap<>();

		// Fan out
		List<Map.Entry<String, CompletableFuture<SearchResult>>> futures = new ArrayList<>();
		for (String nodeId : nodeClientManager.getClientMap().keySet()) {
			futures.add(Map.entry(
					nodeId,
					submitNodeSearch(requestId, nodeId, shardId, queryString, perShardLimit, searchType,
							indexService, filters, highlight, facetRequests, nodeTimingsMs)));
		}

		long deadlineNanos = System.nanoTime() + shardTimeout.toNanos();

		// Join + merge
		MergeAccumulator acc = awaitAndMerge(
				futures, deadlineNanos, requestId, shardId, searchType);

		// best-effort cancellation of any remaining futures
		cancelOutstanding(futures);

		// Global sort + page
		acc.allHits.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
		List<SearchHit> pageHits = slicePage(acc.allHits, page, size);

		// Materialize facets in request order
		List<FacetResponse> aggregatedFacets = buildAggregatedFacets(facetRequests, acc.facetAggregation);

		// log a summary per request
		long sumMs = nodeTimingsMs.values().stream().mapToLong(Long::longValue).sum();
		LOG.info(
				"Search fanout summary: requestId={}, shardId={}, searchType={}, totalHits={}, page={}, size={}, totalNodeTimeMs={}, nodeTimingsMs={}",
				requestId, shardId, searchType, acc.totalHits, page, size, sumMs, nodeTimingsMs);

		return new SearchResult(
				pageHits,
				acc.totalHits,
				page,
				aggregatedFacets.isEmpty() ? null : aggregatedFacets);
	}

	private CompletableFuture<SearchResult> submitNodeSearch(
			String requestId,
			String nodeId,
			String shardId,
			String queryString,
			int perShardLimit,
			SearchType searchType,
			BaseIndexService indexService,
			List<Filter> filters,
			boolean highlight,
			List<FacetRequest> facetRequests,
			Map<String, Long> nodeTimingsMs) {

		return CompletableFuture.supplyAsync(() -> {
			if (requestId != null) {
				MDC.put("requestId", requestId);
			}
			long startNanos = System.nanoTime();
			try {
				return indexService.searchShardTopK(
						queryString,
						nodeId,
						shardId,
						perShardLimit,
						searchType,
						filters,
						highlight,
						facetRequests);
			} finally {
				long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
				nodeTimingsMs.put(nodeId, tookMs);
				LOG.info("Shard search timing: requestId={}, nodeId={}, shardId={}, searchType={}, tookMs={}",
						requestId, nodeId, shardId, searchType, tookMs);
				if (requestId != null) {
					MDC.remove("requestId");
				}
			}
		}, shardExecutor);
	}

	private MergeAccumulator awaitAndMerge(
			List<Map.Entry<String, CompletableFuture<SearchResult>>> futures,
			long deadlineNanos,
			String requestId,
			String shardId,
			SearchType searchType) {

		MergeAccumulator acc = new MergeAccumulator();

		for (Map.Entry<String, CompletableFuture<SearchResult>> entry : futures) {
			String nodeId = entry.getKey();
			CompletableFuture<SearchResult> future = entry.getValue();
			long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0) {
				LOG.warn(
						"Global shard search timeout budget exhausted; skipping remaining nodes; requestId={}, shardId={}, searchType={}",
						requestId, shardId, searchType);
				break;
			}

			try {
				SearchResult shardResult = future.get(remainingNanos, TimeUnit.NANOSECONDS);
				if (shardResult != null) {
					acc.totalHits += shardResult.getTotalHits();
					acc.allHits.addAll(shardResult.getHits());
					aggregateFacets(acc.facetAggregation, shardResult.getFacets());
				}
			} catch (TimeoutException te) {
				LOG.warn(
						"Node search timed out before global deadline; requestId={}, shardId={}, searchType={}, nodeId={}",
						requestId, shardId, searchType, nodeId, te);
			} catch (ExecutionException ee) {
				LOG.error("Node search failed; requestId={}, shardId={}, searchType={}, nodeId={}",
						requestId, shardId, searchType, nodeId, ee.getCause());
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				LOG.warn("Node search interrupted; requestId={}, shardId={}, searchType={}, nodeId={}",
						requestId, shardId, searchType, nodeId, ie);
				break;
			}
		}

		return acc;
	}

	private void cancelOutstanding(List<Map.Entry<String, CompletableFuture<SearchResult>>> futures) {
		for (Map.Entry<String, CompletableFuture<SearchResult>> entry : futures) {
			CompletableFuture<SearchResult> future = entry.getValue();
			if (!future.isDone()) {
				future.cancel(true);
			}
		}
	}

	private void aggregateFacets(Map<String, Map<String, Long>> facetAggregation, List<FacetResponse> facets) {
		if (facets == null || facets.isEmpty()) {
			return;
		}
		for (FacetResponse facetResp : facets) {
			String field = facetResp.getField();
			facetAggregation.putIfAbsent(field, new HashMap<>());
			Map<String, Long> fieldCounts = facetAggregation.get(field);
			for (FacetBucket bucket : facetResp.getBucketsList()) {
				fieldCounts.merge(bucket.getValue(), bucket.getCount(), Long::sum);
			}
		}
	}

	private List<FacetResponse> buildAggregatedFacets(
			List<FacetRequest> facetRequests,
			Map<String, Map<String, Long>> facetAggregation) {

		if (facetRequests == null || facetRequests.isEmpty()) {
			return Collections.emptyList();
		}

		List<FacetResponse> aggregatedFacets = new ArrayList<>(facetRequests.size());

		for (FacetRequest facetReq : facetRequests) {
			String field = facetReq.getField();
			int topN = facetReq.getSize() > 0 ? facetReq.getSize() : 10;

			Map<String, Long> fieldCounts = facetAggregation.get(field);
			FacetResponse.Builder facetBuilder = FacetResponse.newBuilder().setField(field);

			if (fieldCounts != null && !fieldCounts.isEmpty()) {
				fieldCounts.entrySet().stream()
						.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
						.limit(topN)
						.forEach(e -> facetBuilder.addBuckets(
								FacetBucket.newBuilder()
										.setValue(e.getKey())
										.setCount(e.getValue())
										.build()));
			}

			aggregatedFacets.add(facetBuilder.build());
		}

		return aggregatedFacets;
	}

	private static int normalizePage(int page) {
		return Math.max(0, page);
	}

	private static int normalizeSize(int size) {
		return size <= 0 ? 10 : size;
	}

	private static List<SearchHit> slicePage(List<SearchHit> hits, int page, int size) {
		if (hits == null || hits.isEmpty()) {
			return Collections.emptyList();
		}
		int fromIndex = page * size;
		if (fromIndex >= hits.size()) {
			return Collections.emptyList();
		}
		int toIndex = Math.min(fromIndex + size, hits.size());
		return hits.subList(fromIndex, toIndex);
	}

	private static final class MergeAccumulator {
		final List<SearchHit> allHits = new ArrayList<>();
		long totalHits = 0L;
		final Map<String, Map<String, Long>> facetAggregation = new HashMap<>();
	}

	@Override
	public void close() throws IOException {
		shardExecutor.shutdown();
	}
}