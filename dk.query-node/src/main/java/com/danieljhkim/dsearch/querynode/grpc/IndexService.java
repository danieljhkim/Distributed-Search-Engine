package com.danieljhkim.dsearch.querynode.grpc;

import com.danieljhkim.dsearch.common.enums.EnumMapper;
import com.danieljhkim.dsearch.common.enums.SearchType;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.index.IndexSearchRequest;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;

public class IndexService implements BaseIndexService {

    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;

    public IndexService(NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
        this.nodeClientManager = nodeClientManager;
    }

    public SearchResult search(String queryString, String nodeId, String shardId, int page, int size, SearchType searchType) {
        if (!nodeClientManager.getClientMap().containsKey(nodeId)) {
            throw new IllegalArgumentException("Unknown nodeId: " + nodeId);
        }
        int from = page * size;
        IndexSearchRequest.Builder grpcReqBuilder = IndexSearchRequest.newBuilder()
                .setQuery(queryString)
                .setFrom(from)
                .setSize(size)
                .setShardId(shardId)
                .setSearchType(EnumMapper.mapToProtoEnum(searchType));
        IndexServiceGrpc.IndexServiceBlockingStub stub = nodeClientManager.getClientMap().get(nodeId).getStub();
        IndexSearchResponse grpcResp = stub.searchIndex(grpcReqBuilder.build());
        return mapToSearchResult(grpcResp, page);
    }
}