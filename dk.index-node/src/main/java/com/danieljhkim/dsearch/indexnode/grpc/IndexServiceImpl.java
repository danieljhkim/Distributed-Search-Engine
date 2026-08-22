package com.danieljhkim.dsearch.indexnode.grpc;

import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.validation.PartitionIdValidator;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import com.danieljhkim.dsearch.indexnode.index.ShardIndex;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexHit;
import com.danieljhkim.dsearch.proto.index.IndexSearchRequest;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IndexServiceImpl extends IndexServiceGrpc.IndexServiceImplBase {

    private static final Logger LOGGER = Logger.getLogger(IndexServiceImpl.class.getName());

    private final IndexManager indexManager;

    public IndexServiceImpl(IndexManager indexManager) {
        this.indexManager = indexManager;
    }

    @Override
    public void indexDocument(IndexDocumentRequest request, StreamObserver<IndexDocumentResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        PartitionIdValidator.validate(partitionId);
        Document protoDoc = request.getDocument();
        String docId = protoDoc.getId().isEmpty() ? UUID.randomUUID().toString() : protoDoc.getId();

        try {
            SearchDocument searchDoc = toSearchDocument(docId, protoDoc);
            indexManager.indexDocument(partitionId, searchDoc);

            IndexDocumentResponse response = IndexDocumentResponse.newBuilder()
                    .setId(docId)
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IndexDocument failed", e);
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void bulkIndexDocument(
            BulkIndexDocumentRequest request, StreamObserver<BulkIndexDocumentResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        PartitionIdValidator.validate(partitionId);
        BulkIndexDocumentResponse.Builder respBuilder = BulkIndexDocumentResponse.newBuilder();
        boolean success = true;
        try {
            for (Document protoDoc : request.getDocumentsList()) {
                String docId = protoDoc.getId().isEmpty() ? UUID.randomUUID().toString() : protoDoc.getId();
                SearchDocument searchDoc = toSearchDocument(docId, protoDoc);
                indexManager.indexDocument(partitionId, searchDoc);
                respBuilder.addIds(docId);
            }
            // optional: commit per bulk call
            indexManager.commitAll();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "BulkIndexDocument failed", e);
            success = false;
        }

        respBuilder.setSuccess(success);
        responseObserver.onNext(respBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void deleteDocument(DeleteDocumentRequest request, StreamObserver<DeleteDocumentResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        PartitionIdValidator.validate(partitionId);
        String docId = request.getId();
        try {
            indexManager.deleteDocument(partitionId, docId);
            DeleteDocumentResponse response =
                    DeleteDocumentResponse.newBuilder().setSuccess(true).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "DeleteDocument failed", e);
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void searchIndex(IndexSearchRequest request, StreamObserver<IndexSearchResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        String query = request.getQuery();
        SearchType protoType = request.getSearchType();
        int from = request.getFrom();
        int size = request.getSize();

        // Extract filters, highlight flag, and facet requests from request
        List<Filter> filters = request.getFiltersList();
        boolean highlight = request.getHighlight();
        List<FacetRequest> facetRequests = request.getFacetsList();

        try {
            SearchResult res = indexManager.searchDocument(
                    partitionId, query, size, from, protoType, filters, highlight, facetRequests);
            IndexSearchResponse.Builder respBuilder =
                    IndexSearchResponse.newBuilder().setTotalHits(res.getTotalHits());
            for (SearchHit hit : res.getHits()) {
                IndexHit protoHit = toIndexHit(hit);
                respBuilder.addHits(protoHit);
            }
            // Add facets to response if present
            if (res.getFacets() != null && !res.getFacets().isEmpty()) {
                respBuilder.addAllFacets(res.getFacets());
            }
            IndexSearchResponse response = respBuilder.build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "searchIndex failed", e);
            throw new UncheckedIOException(e);
        }
    }

    private IndexHit toIndexHit(SearchHit hit) {
        IndexHit.Builder builder =
                IndexHit.newBuilder().setDocId(hit.getDocId()).setScore(hit.getScore());
        if (hit.getTitle() != null) {
            builder.setTitle(hit.getTitle());
        }
        if (hit.getContent() != null) {
            builder.setContent(hit.getContent());
        }
        if (hit.getHighlightedFields() != null && !hit.getHighlightedFields().isEmpty()) {
            builder.putAllHighlightedFields(hit.getHighlightedFields());
        }
        if (hit.getFields() != null && !hit.getFields().isEmpty()) {
            builder.putAllFields(hit.getFields());
        }
        return builder.build();
    }

    private SearchDocument toSearchDocument(String docId, Document protoDoc) {
        Map<String, String> fields = new HashMap<>();
        for (Field field : protoDoc.getFieldsList()) {
            fields.put(field.getName(), field.getValue());
        }
        fields.putIfAbsent(ShardIndex.FIELD_ID, docId);
        return new SearchDocument(docId, fields);
    }
}
