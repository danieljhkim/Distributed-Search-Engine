package com.dk.search.indexnode.grpc;

import com.dk.search.common.model.SearchDocument;
import com.dk.search.indexnode.index.IndexManager;
import com.dk.search.indexnode.index.ShardIndex;
import com.dk.search.proto.index.IndexDocumentRequest;
import com.dk.search.proto.index.IndexDocumentResponse;
import com.dk.search.proto.index.BulkIndexDocumentRequest;
import com.dk.search.proto.index.BulkIndexDocumentResponse;
import com.dk.search.proto.index.DeleteDocumentRequest;
import com.dk.search.proto.index.DeleteDocumentResponse;
import com.dk.search.proto.index.IndexServiceGrpc;
import com.dk.search.proto.index.Document;
import com.dk.search.proto.index.Field;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.HashMap;
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
        int shardId = request.getShardId();
        Document protoDoc = request.getDocument();
        String docId = protoDoc.getId().isEmpty() ? UUID.randomUUID().toString() : protoDoc.getId();

        try {
            SearchDocument searchDoc = toSearchDocument(docId, protoDoc);
            indexManager.indexDocument(shardId, searchDoc);

            IndexDocumentResponse response = IndexDocumentResponse.newBuilder()
                    .setId(docId)
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IndexDocument failed", e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("IndexDocument failed: " + e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    @Override
    public void bulkIndexDocument(BulkIndexDocumentRequest request,
                                  StreamObserver<BulkIndexDocumentResponse> responseObserver) {
        int shardId = request.getShardId();
        BulkIndexDocumentResponse.Builder respBuilder = BulkIndexDocumentResponse.newBuilder();
        boolean success = true;

        try {
            for (Document protoDoc : request.getDocumentsList()) {
                String docId = protoDoc.getId().isEmpty() ? UUID.randomUUID().toString() : protoDoc.getId();
                SearchDocument searchDoc = toSearchDocument(docId, protoDoc);
                indexManager.indexDocument(shardId, searchDoc);
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
    public void deleteDocument(DeleteDocumentRequest request,
                               StreamObserver<DeleteDocumentResponse> responseObserver) {
        int shardId = request.getShardId();
        String docId = request.getId();

        try {
            indexManager.deleteDocument(shardId, docId);
            DeleteDocumentResponse response = DeleteDocumentResponse.newBuilder()
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "DeleteDocument failed", e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("DeleteDocument failed: " + e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    private SearchDocument toSearchDocument(String docId, Document protoDoc) {
        Map<String, String> fields = new HashMap<>();
        for (Field field : protoDoc.getFieldsList()) {
            fields.put(field.getName(), field.getValue());
        }
        // ensure id is available in the map if you want
        fields.putIfAbsent(ShardIndex.FIELD_ID, docId);
        return new SearchDocument(docId, fields);
    }
}