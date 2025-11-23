package com.dk.search.querynode.grpc;

import com.dk.search.common.model.SearchResult;
import com.dk.search.proto.index.IndexSearchRequest;
import com.dk.search.proto.index.IndexSearchResponse;
import com.dk.search.proto.index.IndexServiceGrpc;

import java.util.ArrayList;
import java.util.List;

public class IndexService {

    private final IndexServiceGrpc.IndexServiceBlockingStub indexStub;

    public IndexService(IndexServiceGrpc.IndexServiceBlockingStub indexStub) {
        this.indexStub = indexStub;
    }

    public SearchResult search(String queryString, String shardId, int page, int size) {
        int from = page * size;
        IndexSearchRequest.Builder grpcReqBuilder = IndexSearchRequest.newBuilder()
                .setQuery(queryString)
                .setFrom(from)
                .setSize(size)
                .setShardId(shardId);

        IndexSearchResponse grpcResp = indexStub.searchIndex(grpcReqBuilder.build());
        List<com.dk.search.common.model.SearchHit> hits = new ArrayList<>();
        for (com.dk.search.proto.index.IndexHit hit : grpcResp.getHitsList()) {
            hits.add(new com.dk.search.common.model.SearchHit(hit.getDocId(), hit.getScore(), hit.getContent()));
        }
        return new SearchResult(
                hits,
                grpcResp.getTotalHits(),
                page
        );
    }

    public SearchResult searchShardTopK(String queryString, String shardId, int topK) {
        // page = 0, size = topK → from = 0, size = topK
        return search(queryString, shardId, 0, topK);
    }
}