package com.danieljhkim.dsearch.querynode.grpc;

import java.util.List;

import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.index.IndexSearchRequest;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;

public class IndexService implements BaseIndexService {

	private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;

	public IndexService(NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
		this.nodeClientManager = nodeClientManager;
	}

	@Override
	public SearchResult search(String queryString, String nodeId, String partitionId, int page, int size,
			SearchType searchType) {
		return search(queryString, nodeId, partitionId, page, size, searchType, null, false);
	}

	@Override
	public SearchResult search(String queryString, String nodeId, String partitionId, int page, int size,
			SearchType searchType, List<Filter> filters, boolean highlight) {
		return search(queryString, nodeId, partitionId, page, size, searchType, filters, highlight, null);
	}

	@Override
	public SearchResult search(String queryString, String nodeId, String partitionId, int page, int size,
			SearchType searchType, List<Filter> filters, boolean highlight,
			List<FacetRequest> facetRequests) {
		if (!nodeClientManager.getClientMap().containsKey(nodeId)) {
			throw new IllegalArgumentException("Unknown nodeId: " + nodeId);
		}
		int from = page * size;
		IndexSearchRequest.Builder grpcReqBuilder = IndexSearchRequest.newBuilder()
				.setQuery(queryString)
				.setFrom(from)
				.setSize(size)
				.setPartitionId(partitionId)
				.setSearchType(searchType)
				.setHighlight(highlight);

		if (filters != null) {
			grpcReqBuilder.addAllFilters(filters);
		}
		if (facetRequests != null) {
			grpcReqBuilder.addAllFacets(facetRequests);
		}

		IndexServiceGrpc.IndexServiceBlockingStub stub = nodeClientManager.getClientMap().get(nodeId).getStub();
		IndexSearchResponse grpcResp = stub.searchIndex(grpcReqBuilder.build());
		return mapToSearchResult(grpcResp, page);
	}
}