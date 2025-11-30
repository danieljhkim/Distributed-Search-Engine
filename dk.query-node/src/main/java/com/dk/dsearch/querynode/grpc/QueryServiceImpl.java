package com.dk.dsearch.querynode.grpc;

import com.dk.dsearch.common.enums.EnumMapper;
import com.dk.dsearch.common.enums.SearchType;
import com.dk.dsearch.common.exception.ParseGoneWrongException;
import com.dk.dsearch.common.model.SearchResult;
import com.dk.dsearch.proto.query.QueryRequest;
import com.dk.dsearch.proto.query.QueryResponse;
import com.dk.dsearch.proto.query.QueryServiceGrpc;
import com.dk.dsearch.proto.query.SearchHit;
import com.dk.dsearch.querynode.search.SearchExecutor;
import io.grpc.stub.StreamObserver;

import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryServiceImpl extends QueryServiceGrpc.QueryServiceImplBase {

    private static final Logger LOGGER = Logger.getLogger(QueryServiceImpl.class.getName());

    private final SearchExecutor searchExecutor;
    private final BaseIndexService indexService;

    public QueryServiceImpl(SearchExecutor searchExecutor, BaseIndexService indexService) {
        this.searchExecutor = searchExecutor;
        this.indexService = indexService;
    }

    @Override
    public void search(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
        String queryString = request.getQueryString();
        int page = request.getPage();
        int size = request.getSize();
        String shardId = request.getShardId();
        SearchType searchType = EnumMapper.mapFromProtoEnum(request.getSearchType());
        try {
            SearchResult result;
            if (searchType == SearchType.HYBRID) {
                result = searchExecutor.searchHybrid(
                        queryString,
                        shardId,
                        page,
                        size,
                        indexService
                );
            } else {
                result = searchExecutor.search(
                        queryString,
                        shardId,
                        page,
                        size,
                        searchType,
                        indexService
                );
            }
            QueryResponse response = buildQueryResponse(result, page, size);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse query: " + queryString, e);
            responseObserver.onError(new ParseGoneWrongException("Failed to parse query: " + queryString, e));
        }
    }

    private QueryResponse buildQueryResponse(SearchResult result, int page, int size) {
        QueryResponse.Builder respBuilder = QueryResponse.newBuilder()
                .setTotalHits(result.getTotalHits())
                .setTookMillis(result.getTookMillis())
                .setPage(page)
                .setSize(size);
        for (com.dk.dsearch.common.model.SearchHit hit : result.getHits()) {
            SearchHit.Builder hitBuilder = SearchHit.newBuilder()
                    .setDocId(hit.getDocId())
                    .setScore(hit.getScore());

            if (hit.getTitle() != null) {
                hitBuilder.setTitle(hit.getTitle());
            }
            if (hit.getContent() != null) {
                hitBuilder.setContent(hit.getContent());
            }
            SearchHit protoHit = hitBuilder.build();
            respBuilder.addHits(protoHit);
        }
        return respBuilder.build();
    }
}