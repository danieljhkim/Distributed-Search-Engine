package com.dk.dsearch.querynode.grpc;

import com.dk.dsearch.common.model.SearchHit;
import com.dk.dsearch.common.model.SearchResult;
import com.dk.dsearch.proto.index.IndexSearchResponse;

import java.util.ArrayList;
import java.util.List;

public interface BaseIndexService {

    SearchResult search(String queryString, String nodeId, String shardId, int page, int size, String searchType);

    default SearchResult searchShardTopK(String queryString, String nodeId, String shardId, int topK, String searchType) {
        // page = 0, size = topK → from = 0, size = topK
        return search(queryString, nodeId, shardId, 0, topK, searchType);
    }

    default SearchResult mapToSearchResult(IndexSearchResponse grpcResp, int page) {
        List<SearchHit> hits = new ArrayList<>();
        for (com.dk.dsearch.proto.index.IndexHit hit : grpcResp.getHitsList()) {
            hits.add(new SearchHit(hit.getDocId(), hit.getTitle(), hit.getContent(), hit.getScore()));
        }
        return new SearchResult(
                hits,
                grpcResp.getTotalHits(),
                page
        );
    }
}
