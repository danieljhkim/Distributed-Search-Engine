package com.dk.search.querynode.grpc;

import com.dk.search.common.model.SearchResult;
import com.dk.search.querynode.search.SearchExecutor;
import com.dk.search.proto.query.QueryRequest;
import com.dk.search.proto.query.QueryResponse;
import com.dk.search.proto.query.QueryServiceGrpc;
import com.dk.search.proto.query.SearchHit;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
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
        List<Integer> shardIds = request.getShardIdsList();
        int topK = request.getTopK() > 0 ? request.getTopK() : 10;

        try {
            SearchResult result = searchExecutor.search(queryString, shardIds, topK);

            QueryResponse.Builder respBuilder = QueryResponse.newBuilder()
                    .setTotalHits(result.getTotalHits())
                    .setTookMillis(result.getTookMillis());

            for (SearchResult.SearchHit hit : result.getHits()) {
                SearchHit protoHit = SearchHit.newBuilder()
                        .setDocId(hit.getDocId())
                        .setScore(hit.getScore())
                        .build();
                respBuilder.addHits(protoHit);
            }

            responseObserver.onNext(respBuilder.build());
            responseObserver.onCompleted();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Search failed", e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Search failed: " + e.getMessage())
                            .asRuntimeException()
            );
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error during search", e);
            responseObserver.onError(
                    Status.UNKNOWN
                            .withDescription("Unexpected error: " + e.getMessage())
                            .asRuntimeException()
            );
        }
    }
}