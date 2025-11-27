package com.dk.dsearch.querynode.grpc;

import com.dk.dsearch.common.model.SearchHit;
import com.dk.dsearch.common.grpc.NodeClientManager;
import com.dk.dsearch.common.model.SearchResult;
import com.dk.dsearch.proto.index.IndexSearchRequest;
import com.dk.dsearch.proto.index.IndexSearchResponse;
import com.dk.dsearch.proto.index.IndexServiceGrpc;

import java.util.ArrayList;
import java.util.List;

public class IndexService {

    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager;

    public IndexService(NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> nodeClientManager) {
        this.nodeClientManager = nodeClientManager;
    }

    public SearchResult search(String queryString, String nodeId, String shardId, int page, int size) {
        if (!nodeClientManager.getStubsMap().containsKey(nodeId)) {
            throw new IllegalArgumentException("Unknown nodeId: " + nodeId);
        }
        int from = page * size;
        IndexSearchRequest.Builder grpcReqBuilder = IndexSearchRequest.newBuilder()
                .setQuery(queryString)
                .setFrom(from)
                .setSize(size)
                .setShardId(shardId);
        IndexServiceGrpc.IndexServiceBlockingStub stub = nodeClientManager.getStubsMap().get(nodeId);
        IndexSearchResponse grpcResp = stub.searchIndex(grpcReqBuilder.build());
        List<SearchHit> hits = new ArrayList<>();
        for (com.dk.dsearch.proto.index.IndexHit hit : grpcResp.getHitsList()) {
            hits.add(new SearchHit(hit.getDocId(), hit.getScore(), hit.getContent()));
        }
        return new SearchResult(
                hits,
                grpcResp.getTotalHits(),
                page
        );
    }

    public SearchResult searchShardTopK(String queryString, String nodeId, String shardId, int topK) {
        // page = 0, size = topK → from = 0, size = topK
        return search(queryString, nodeId, shardId, 0, topK);
    }
}