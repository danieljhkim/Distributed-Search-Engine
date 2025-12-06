package com.danieljhkim.dsearch.querynode.grpc;

import com.danieljhkim.dsearch.common.enums.SearchType;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;

import java.util.ArrayList;
import java.util.List;

public interface BaseIndexService {

    SearchResult search(String queryString, String nodeId, String shardId, int page, int size, SearchType searchType);

    default SearchResult searchShardTopK(String queryString, String nodeId, String shardId, int topK, SearchType searchType) {
        // page = 0, size = topK → from = 0, size = topK
        return search(queryString, nodeId, shardId, 0, topK, searchType);
    }

    default SearchResult mapToSearchResult(IndexSearchResponse grpcResp, int page) {
        List<SearchHit> hits = new ArrayList<>();
        for (com.danieljhkim.dsearch.proto.index.IndexHit hit : grpcResp.getHitsList()) {
            hits.add(new SearchHit(hit.getDocId(), hit.getTitle(), hit.getContent(), hit.getScore()));
        }
        return new SearchResult(
                hits,
                grpcResp.getTotalHits(),
                page
        );
    }
}
