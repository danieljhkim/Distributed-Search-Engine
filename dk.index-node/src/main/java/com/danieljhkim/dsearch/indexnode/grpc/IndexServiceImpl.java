package com.danieljhkim.dsearch.indexnode.grpc;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.exception.SchemaMismatchException;
import com.danieljhkim.dsearch.common.exception.ShardNotFoundException;
import com.danieljhkim.dsearch.common.model.SearchDocument;
import com.danieljhkim.dsearch.common.model.SearchHit;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.common.pagination.SortOptions;
import com.danieljhkim.dsearch.common.pagination.SortSpec;
import com.danieljhkim.dsearch.common.schema.IndexAlias;
import com.danieljhkim.dsearch.common.schema.IndexSchema;
import com.danieljhkim.dsearch.common.schema.SchemaProtoMapper;
import com.danieljhkim.dsearch.common.validation.PartitionIdValidator;
import com.danieljhkim.dsearch.common.validation.RequestAdmissionException;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.indexnode.index.IndexManager;
import com.danieljhkim.dsearch.indexnode.index.ShardIndex;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.common.SortValue;
import com.danieljhkim.dsearch.proto.index.BulkDeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkDeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.BulkDeleteDocumentResult;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResult;
import com.danieljhkim.dsearch.proto.index.CreateIndexRequest;
import com.danieljhkim.dsearch.proto.index.CreateIndexResponse;
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
import com.danieljhkim.dsearch.proto.index.InspectSchemaRequest;
import com.danieljhkim.dsearch.proto.index.InspectSchemaResponse;
import com.danieljhkim.dsearch.proto.index.ReindexRequest;
import com.danieljhkim.dsearch.proto.index.ReindexResponse;
import com.danieljhkim.dsearch.proto.index.RollbackAliasRequest;
import com.danieljhkim.dsearch.proto.index.RollbackAliasResponse;
import com.danieljhkim.dsearch.proto.index.SwapAliasRequest;
import com.danieljhkim.dsearch.proto.index.SwapAliasResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IndexServiceImpl extends IndexServiceGrpc.IndexServiceImplBase {

    private static final Logger LOGGER = Logger.getLogger(IndexServiceImpl.class.getName());

    private final IndexManager indexManager;
    private final AppConfig.RequestLimitsConfig requestLimits;

    public IndexServiceImpl(IndexManager indexManager) {
        this(indexManager, new AppConfig.RequestLimitsConfig());
    }

    public IndexServiceImpl(IndexManager indexManager, AppConfig.RequestLimitsConfig requestLimits) {
        this.indexManager = indexManager;
        this.requestLimits = RequestLimitsValidator.limitsOrDefaults(requestLimits);
    }

    @Override
    public void indexDocument(IndexDocumentRequest request, StreamObserver<IndexDocumentResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        if (!validatePartition(partitionId, responseObserver)) {
            return;
        }
        Document protoDoc = request.getDocument();
        String docId = protoDoc.getId().isEmpty() ? UUID.randomUUID().toString() : protoDoc.getId();

        try {
            RequestLimitsValidator.validateDocument(
                    protoDoc.toBuilder().setId(docId).build(), requestLimits);
            SearchDocument searchDoc = toSearchDocument(docId, protoDoc);
            indexManager.indexDocumentDurably(partitionId, searchDoc);

            IndexDocumentResponse response = IndexDocumentResponse.newBuilder()
                    .setId(docId)
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (SchemaMismatchException e) {
            failedPrecondition(responseObserver, e);
        } catch (RequestAdmissionException e) {
            resourceExhausted(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "IndexDocument failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to durably index document " + docId)
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void bulkIndexDocument(
            BulkIndexDocumentRequest request, StreamObserver<BulkIndexDocumentResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        if (!validatePartition(partitionId, responseObserver)) {
            return;
        }
        try {
            RequestLimitsValidator.validateBulkIndexRequest(request, requestLimits);
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
            return;
        }
        BulkIndexDocumentResponse.Builder respBuilder = BulkIndexDocumentResponse.newBuilder();
        boolean success = true;
        for (int requestIndex = 0; requestIndex < request.getDocumentsCount(); requestIndex++) {
            Document protoDoc = request.getDocuments(requestIndex);
            String docId = protoDoc.getId().isEmpty() ? UUID.randomUUID().toString() : protoDoc.getId();
            BulkIndexDocumentResult.Builder result = BulkIndexDocumentResult.newBuilder()
                    .setRequestIndex(requestIndex)
                    .setId(docId);
            try {
                SearchDocument searchDoc = toSearchDocument(docId, protoDoc);
                indexManager.indexDocumentDurably(partitionId, searchDoc);
                respBuilder.addIds(docId);
                result.setSuccess(true);
            } catch (RequestAdmissionException e) {
                success = false;
                result.setSuccess(false).setError("request admission exhausted; retry with the returned id");
            } catch (SchemaMismatchException e) {
                success = false;
                result.setSuccess(false).setError(e.getMessage());
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.SEVERE, "BulkIndexDocument failed for request index " + requestIndex, e);
                success = false;
                result.setSuccess(false).setError("durable index failed; retry with the returned id");
            }
            respBuilder.addResults(result);
        }

        respBuilder.setSuccess(success);
        responseObserver.onNext(respBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void deleteDocument(DeleteDocumentRequest request, StreamObserver<DeleteDocumentResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        if (!validatePartition(partitionId, responseObserver)) {
            return;
        }
        String docId = request.getId();
        try {
            RequestLimitsValidator.validateDocumentId(docId, requestLimits);
            indexManager.deleteDocumentDurably(partitionId, docId);
            DeleteDocumentResponse response =
                    DeleteDocumentResponse.newBuilder().setSuccess(true).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (SchemaMismatchException e) {
            failedPrecondition(responseObserver, e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "DeleteDocument failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to delete document " + docId)
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    /**
     * Applies bounded, ordered document deletions.
     *
     * <p>Each id is deleted independently via {@link IndexManager#deleteDocumentDurably}, which is
     * idempotent: a missing document deletes successfully, exactly like single delete. A per-item
     * failure never prevents independent ids in the same request from being attempted.
     */
    @Override
    public void bulkDeleteDocument(
            BulkDeleteDocumentRequest request, StreamObserver<BulkDeleteDocumentResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        if (!validatePartition(partitionId, responseObserver)) {
            return;
        }
        try {
            RequestLimitsValidator.validateBulkItemCount(request.getIdsCount(), requestLimits);
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
            return;
        }
        BulkDeleteDocumentResponse.Builder respBuilder = BulkDeleteDocumentResponse.newBuilder();
        boolean success = true;
        for (int requestIndex = 0; requestIndex < request.getIdsCount(); requestIndex++) {
            String docId = request.getIds(requestIndex);
            BulkDeleteDocumentResult.Builder result = BulkDeleteDocumentResult.newBuilder()
                    .setRequestIndex(requestIndex)
                    .setId(docId);
            try {
                RequestLimitsValidator.validateDocumentId(docId, requestLimits);
                indexManager.deleteDocumentDurably(partitionId, docId);
                result.setSuccess(true);
            } catch (IllegalArgumentException e) {
                success = false;
                result.setSuccess(false).setError(e.getMessage());
            } catch (RequestAdmissionException e) {
                success = false;
                result.setSuccess(false).setError("request admission exhausted; retry with the same id");
            } catch (SchemaMismatchException e) {
                success = false;
                result.setSuccess(false).setError(e.getMessage());
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.SEVERE, "BulkDeleteDocument failed for request index " + requestIndex, e);
                success = false;
                result.setSuccess(false)
                        .setError("durable delete failed; retry with the same id because delete is idempotent");
            }
            respBuilder.addResults(result);
        }

        respBuilder.setSuccess(success);
        responseObserver.onNext(respBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void searchIndex(IndexSearchRequest request, StreamObserver<IndexSearchResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        if (!validatePartition(partitionId, responseObserver)) {
            return;
        }
        String query = request.getQuery();
        SearchType protoType = request.getSearchType();
        int from = request.getFrom();
        int size = request.getSize();

        // Extract filters, highlight flag, and facet requests from request
        List<Filter> filters = request.getFiltersList();
        boolean highlight = request.getHighlight();
        List<FacetRequest> facetRequests = request.getFacetsList();
        List<String> storedFields = request.hasStoredFieldSelection()
                ? List.copyOf(request.getStoredFieldSelection().getFieldsList())
                : null;

        try {
            RequestLimitsValidator.validateIndexSearchRequest(request, requestLimits);
            SortOptions sortOptions = toSortOptions(request);
            SearchResult res = storedFields == null
                    ? indexManager.searchDocument(
                            partitionId, query, size, from, protoType, filters, highlight, facetRequests, sortOptions)
                    : indexManager.searchDocument(
                            partitionId,
                            query,
                            size,
                            from,
                            protoType,
                            filters,
                            highlight,
                            facetRequests,
                            sortOptions,
                            storedFields);
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
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (SchemaMismatchException e) {
            failedPrecondition(responseObserver, e);
        } catch (RequestAdmissionException e) {
            resourceExhausted(responseObserver, e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "searchIndex failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to search shard " + partitionId)
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void createIndex(CreateIndexRequest request, StreamObserver<CreateIndexResponse> responseObserver) {
        try {
            IndexSchema schema = SchemaProtoMapper.fromProto(request.getSchema());
            String indexName = request.getIndexName().isBlank() ? request.getAlias() : request.getIndexName();
            IndexManager.CreatedIndex created = indexManager.createIndex(indexName, request.getAlias(), schema);
            responseObserver.onNext(CreateIndexResponse.newBuilder()
                    .setSuccess(true)
                    .setIndexName(created.indexName())
                    .setAlias(created.alias())
                    .setAuditId(newAuditId())
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (SchemaMismatchException e) {
            failedPrecondition(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "CreateIndex failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to create index")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void inspectSchema(InspectSchemaRequest request, StreamObserver<InspectSchemaResponse> responseObserver) {
        try {
            IndexManager.InspectedSchema inspected = indexManager.inspectSchema(request.getIndexOrAlias());
            responseObserver.onNext(InspectSchemaResponse.newBuilder()
                    .setIndexName(inspected.indexName())
                    .setAlias(inspected.alias())
                    .setSchema(SchemaProtoMapper.toProto(inspected.schema()))
                    .setGeneration(inspected.generation())
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (ShardNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (SchemaMismatchException e) {
            failedPrecondition(responseObserver, e);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "InspectSchema failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to inspect schema")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void reindex(ReindexRequest request, StreamObserver<ReindexResponse> responseObserver) {
        try {
            IndexSchema schema = SchemaProtoMapper.fromProto(request.getSchema());
            IndexManager.ReindexResult result = indexManager.reindex(
                    request.getSourceAlias(), request.getTargetIndex(), schema, request.getVerificationQueriesList());
            responseObserver.onNext(ReindexResponse.newBuilder()
                    .setSuccess(result.success())
                    .setSourceIndex(result.sourceIndex())
                    .setTargetIndex(result.targetIndex())
                    .setSourceCount(result.sourceCount())
                    .setTargetCount(result.targetCount())
                    .setVerificationPassed(result.verificationPassed())
                    .setAuditId(newAuditId())
                    .setError(result.error() == null ? "" : result.error())
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (ShardNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (SchemaMismatchException e) {
            failedPrecondition(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Reindex failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to reindex")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void swapAlias(SwapAliasRequest request, StreamObserver<SwapAliasResponse> responseObserver) {
        try {
            IndexAlias swapped = indexManager.swapAlias(request.getAlias(), request.getTargetIndex());
            responseObserver.onNext(SwapAliasResponse.newBuilder()
                    .setSuccess(true)
                    .setAlias(swapped.getAlias())
                    .setPreviousIndex(swapped.getPreviousIndexName() == null ? "" : swapped.getPreviousIndexName())
                    .setCurrentIndex(swapped.getIndexName())
                    .setAuditId(newAuditId())
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (ShardNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (SchemaMismatchException e) {
            failedPrecondition(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "SwapAlias failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to swap alias")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void rollbackAlias(RollbackAliasRequest request, StreamObserver<RollbackAliasResponse> responseObserver) {
        try {
            IndexAlias rolledBack = indexManager.rollbackAlias(request.getAlias());
            responseObserver.onNext(RollbackAliasResponse.newBuilder()
                    .setSuccess(true)
                    .setAlias(rolledBack.getAlias())
                    .setCurrentIndex(rolledBack.getIndexName())
                    .setPreviousIndex(
                            rolledBack.getPreviousIndexName() == null ? "" : rolledBack.getPreviousIndexName())
                    .setAuditId(newAuditId())
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (ShardNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "RollbackAlias failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to roll back alias")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    /**
     * Reads the ordering off the wire.
     *
     * <p>The sort arrives already tie-broken from the query node, so it is taken verbatim rather
     * than re-normalized: every shard must collect under exactly the ordering the merge assumes,
     * and re-deriving it here would risk the two drifting apart.
     */
    private static SortOptions toSortOptions(IndexSearchRequest request) {
        if (request.getSortCount() == 0) {
            return SortOptions.NONE;
        }
        SortSpec spec = new SortSpec(request.getSortList().stream()
                .map(field -> new SortSpec.SortComponent(
                        field.getField(),
                        field.getOrder() == com.danieljhkim.dsearch.proto.common.SortOrder.SORT_ORDER_DESC))
                .toList());
        List<SortValue> searchAfter = request.getHasSearchAfter() ? request.getSearchAfterList() : null;
        return new SortOptions(spec, searchAfter);
    }

    private boolean validatePartition(String partitionId, StreamObserver<?> responseObserver) {
        try {
            PartitionIdValidator.validate(partitionId);
            return true;
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
            return false;
        }
    }

    private static void invalidArgument(StreamObserver<?> responseObserver, IllegalArgumentException error) {
        responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription(error.getMessage())
                .withCause(error)
                .asRuntimeException());
    }

    private static void resourceExhausted(StreamObserver<?> responseObserver, RequestAdmissionException error) {
        responseObserver.onError(Status.RESOURCE_EXHAUSTED
                .withDescription(error.getMessage())
                .withCause(error)
                .asRuntimeException());
    }

    private static void failedPrecondition(StreamObserver<?> responseObserver, SchemaMismatchException error) {
        responseObserver.onError(Status.FAILED_PRECONDITION
                .withDescription(error.getMessage())
                .withCause(error)
                .asRuntimeException());
    }

    private static String newAuditId() {
        return UUID.randomUUID().toString();
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
        if (hit.getSortValues() != null && !hit.getSortValues().isEmpty()) {
            builder.addAllSortValues(hit.getSortValues());
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
