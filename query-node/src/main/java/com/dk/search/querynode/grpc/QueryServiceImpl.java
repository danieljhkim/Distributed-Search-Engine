package com.dk.search.querynode.grpc;

import com.dk.search.common.exception.ParseGoneWrongException;
import com.dk.search.common.model.SearchResult;
import com.dk.search.proto.query.QueryRequest;
import com.dk.search.proto.query.QueryResponse;
import com.dk.search.proto.query.QueryServiceGrpc;
import com.dk.search.proto.query.SearchHit;
import com.dk.search.querynode.search.SearchExecutor;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryServiceImpl extends QueryServiceGrpc.QueryServiceImplBase {

    private static final Logger LOGGER = Logger.getLogger(QueryServiceImpl.class.getName());

    private final SearchExecutor searchExecutor;

    public QueryServiceImpl(SearchExecutor searchExecutor) {
        this.searchExecutor = searchExecutor;
    }

    @Override
    public void search(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
        String queryString = request.getQueryString();
        int page = request.getPage();
        int size = request.getSize();
        int topK = request.getTopK();
        List<String> shardIds = request.getShardIdsList();
        try {
            SearchResult result = searchExecutor.search(queryString, shardIds, page, size, topK);
            QueryResponse.Builder respBuilder = QueryResponse.newBuilder()
                    .setTotalHits(result.getTotalHits())
                    .setTookMillis(result.getTookMillis())
                    .setPage(page)
                    .setSize(size);

            for (com.dk.search.common.model.SearchHit hit : result.getHits()) {
                SearchHit protoHit = SearchHit.newBuilder()
                        .setDocId(hit.getDocId())
                        .setScore(hit.getScore())
                        .build();
                respBuilder.addHits(protoHit);
            }

            responseObserver.onNext(respBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse query: " + queryString, e);
            responseObserver.onError(new ParseGoneWrongException("Failed to parse query: " + queryString, e));
        }
    }
}