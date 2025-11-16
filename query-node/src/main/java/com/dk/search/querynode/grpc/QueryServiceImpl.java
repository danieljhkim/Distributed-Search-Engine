package com.dk.search.querynode.grpc;

import com.dk.search.common.model.SearchResult;
import com.dk.search.proto.query.QueryRequest;
import com.dk.search.proto.query.QueryResponse;
import com.dk.search.proto.query.QueryServiceGrpc;
import com.dk.search.proto.query.SearchHit;
import com.dk.search.querynode.search.SearchExecutor;
import io.grpc.stub.StreamObserver;
import org.apache.lucene.queryparser.classic.ParseException;

import java.io.IOException;
import java.io.UncheckedIOException;
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
            throw new UncheckedIOException(e);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }
}