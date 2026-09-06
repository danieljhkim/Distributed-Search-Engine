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
import com.danieljhkim.dsearch.indexnode.index.ReplicaRepairStore;
import com.danieljhkim.dsearch.indexnode.index.ShardIndex;
import com.danieljhkim.dsearch.proto.common.FacetRequest;
import com.danieljhkim.dsearch.proto.common.Filter;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.common.SortValue;
import com.danieljhkim.dsearch.proto.index.AbortReplicaRepairRequest;
import com.danieljhkim.dsearch.proto.index.AbortReplicaRepairResponse;
import com.danieljhkim.dsearch.proto.index.AnalyzeIndexRequest;
import com.danieljhkim.dsearch.proto.index.AnalyzeIndexResponse;
import com.danieljhkim.dsearch.proto.index.AnalyzeToken;
import com.danieljhkim.dsearch.proto.index.BeginReplicaRepairRequest;
import com.danieljhkim.dsearch.proto.index.BeginReplicaRepairResponse;
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
import com.danieljhkim.dsearch.proto.index.FinishReplicaRepairRequest;
import com.danieljhkim.dsearch.proto.index.FinishReplicaRepairResponse;
import com.danieljhkim.dsearch.proto.index.GetDocumentRequest;
import com.danieljhkim.dsearch.proto.index.GetDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexHit;
import com.danieljhkim.dsearch.proto.index.IndexSearchRequest;
import com.danieljhkim.dsearch.proto.index.IndexSearchResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.proto.index.InspectSchemaRequest;
import com.danieljhkim.dsearch.proto.index.InspectSchemaResponse;
import com.danieljhkim.dsearch.proto.index.ListReplicaManifestsRequest;
import com.danieljhkim.dsearch.proto.index.ListReplicaManifestsResponse;
import com.danieljhkim.dsearch.proto.index.MutationMetadata;
import com.danieljhkim.dsearch.proto.index.OpenReplicaSnapshotRequest;
import com.danieljhkim.dsearch.proto.index.OpenReplicaSnapshotResponse;
import com.danieljhkim.dsearch.proto.index.ReadReplicaSnapshotChunkRequest;
import com.danieljhkim.dsearch.proto.index.ReadReplicaSnapshotChunkResponse;
import com.danieljhkim.dsearch.proto.index.ReindexRequest;
import com.danieljhkim.dsearch.proto.index.ReindexResponse;
import com.danieljhkim.dsearch.proto.index.ReplicaManifest;
import com.danieljhkim.dsearch.proto.index.RollbackAliasRequest;
import com.danieljhkim.dsearch.proto.index.RollbackAliasResponse;
import com.danieljhkim.dsearch.proto.index.SwapAliasRequest;
import com.danieljhkim.dsearch.proto.index.SwapAliasResponse;
import com.danieljhkim.dsearch.proto.index.WriteReplicaRepairChunkRequest;
import com.danieljhkim.dsearch.proto.index.WriteReplicaRepairChunkResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IndexServiceImpl extends IndexServiceGrpc.IndexServiceImplBase {

    private static final Logger LOGGER = Logger.getLogger(IndexServiceImpl.class.getName());
    private static final Counter REPLICATION_OUTCOMES = Counter.build()
            .name("dsearch_replication_operations_total")
            .help("Replicated index operations by bounded role and outcome")
            .labelNames("role", "outcome")
            .register();
    private static final Histogram REPLICATION_APPLY_DURATION = Histogram.build()
            .name("dsearch_replication_apply_duration_seconds")
            .help("Replica mutation apply latency")
            .register();
    private static final Counter REPAIR_TRANSFERS = Counter.build()
            .name("dsearch_replica_repair_transfers_total")
            .help("Replica repair transfer operations by bounded outcome")
            .labelNames("operation", "outcome")
            .register();

    private final IndexManager indexManager;
    private final AppConfig.RequestLimitsConfig requestLimits;
    private final String localNodeId;
    private final ReplicaRepairStore replicaRepairStore;
    private final AtomicLong latestPlacementGeneration = new AtomicLong();

    public IndexServiceImpl(IndexManager indexManager) {
        this(indexManager, new AppConfig.RequestLimitsConfig(), null);
    }

    public IndexServiceImpl(IndexManager indexManager, AppConfig.RequestLimitsConfig requestLimits) {
        this(indexManager, requestLimits, null);
    }

    public IndexServiceImpl(
            IndexManager indexManager, AppConfig.RequestLimitsConfig requestLimits, String localNodeId) {
        this.indexManager = indexManager;
        this.requestLimits = RequestLimitsValidator.limitsOrDefaults(requestLimits);
        this.localNodeId = localNodeId;
        this.replicaRepairStore = new ReplicaRepairStore(indexManager);
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
            IndexManager.MutationResult mutationResult = request.hasMutation()
                    ? applyReplicatedIndex(partitionId, searchDoc, request.getMutation())
                    : legacyIndex(partitionId, searchDoc);

            IndexDocumentResponse response = IndexDocumentResponse.newBuilder()
                    .setId(docId)
                    .setSuccess(true)
                    .setDuplicate(mutationResult.duplicate())
                    .setCommittedGeneration(mutationResult.committedGeneration())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (SchemaMismatchException
                | IndexManager.StaleMutationException
                | IndexManager.RepairInProgressException e) {
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
            if (request.getMutationsCount() != 0 && request.getMutationsCount() != request.getDocumentsCount()) {
                throw new IllegalArgumentException("bulk mutations must be absent or positionally match documents");
            }
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
                if (request.getMutationsCount() == 0) {
                    indexManager.indexDocumentDurably(partitionId, searchDoc);
                } else {
                    applyReplicatedIndex(partitionId, searchDoc, request.getMutations(requestIndex));
                }
                respBuilder.addIds(docId);
                result.setSuccess(true);
            } catch (RequestAdmissionException e) {
                success = false;
                result.setSuccess(false).setError("request admission exhausted; retry with the returned id");
            } catch (SchemaMismatchException
                    | IndexManager.StaleMutationException
                    | IndexManager.RepairInProgressException e) {
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
            IndexManager.MutationResult mutationResult = request.hasMutation()
                    ? applyReplicatedDelete(partitionId, docId, request.getMutation())
                    : legacyDelete(partitionId, docId);
            DeleteDocumentResponse response = DeleteDocumentResponse.newBuilder()
                    .setSuccess(true)
                    .setDuplicate(mutationResult.duplicate())
                    .setCommittedGeneration(mutationResult.committedGeneration())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (SchemaMismatchException
                | IndexManager.StaleMutationException
                | IndexManager.RepairInProgressException e) {
            failedPrecondition(responseObserver, e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "DeleteDocument failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to delete document " + docId)
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void getDocument(GetDocumentRequest request, StreamObserver<GetDocumentResponse> responseObserver) {
        String partitionId = request.getPartitionId();
        if (!validatePartition(partitionId, responseObserver)) {
            return;
        }
        if (request.getId().isBlank()) {
            invalidArgument(responseObserver, new IllegalArgumentException("id must not be blank"));
            return;
        }
        try {
            SearchDocument document = indexManager.getDocument(partitionId, request.getId());
            if (document == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Document " + request.getId() + " was not found in partition " + partitionId)
                        .asRuntimeException());
                return;
            }
            Document.Builder responseDocument = Document.newBuilder().setId(document.getId());
            document.getFields().forEach((name, value) -> responseDocument.addFields(
                    Field.newBuilder().setName(name).setValue(value)));
            responseObserver.onNext(GetDocumentResponse.newBuilder()
                    .setDocument(responseDocument)
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (SchemaMismatchException e) {
            failedPrecondition(responseObserver, e);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "getDocument failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to retrieve document from shard " + partitionId)
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
            if (request.getMutationsCount() != 0 && request.getMutationsCount() != request.getIdsCount()) {
                throw new IllegalArgumentException("bulk mutations must be absent or positionally match ids");
            }
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
                if (request.getMutationsCount() == 0) {
                    indexManager.deleteDocumentDurably(partitionId, docId);
                } else {
                    applyReplicatedDelete(partitionId, docId, request.getMutations(requestIndex));
                }
                result.setSuccess(true);
            } catch (IllegalArgumentException e) {
                success = false;
                result.setSuccess(false).setError(e.getMessage());
            } catch (RequestAdmissionException e) {
                success = false;
                result.setSuccess(false).setError("request admission exhausted; retry with the same id");
            } catch (SchemaMismatchException
                    | IndexManager.StaleMutationException
                    | IndexManager.RepairInProgressException e) {
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

    private IndexManager.MutationResult legacyIndex(String partitionId, SearchDocument document) throws IOException {
        indexManager.indexDocumentDurably(partitionId, document);
        return new IndexManager.MutationResult(false, 0L);
    }

    private IndexManager.MutationResult legacyDelete(String partitionId, String documentId) throws IOException {
        indexManager.deleteDocumentDurably(partitionId, documentId);
        return new IndexManager.MutationResult(false, 0L);
    }

    private IndexManager.MutationResult applyReplicatedIndex(
            String partitionId, SearchDocument document, MutationMetadata mutation) throws IOException {
        validateMutationTarget(mutation);
        long started = System.nanoTime();
        String role = mutation.getReplica() ? "replica" : "primary";
        try {
            IndexManager.MutationResult result = indexManager.applyReplicatedIndex(
                    partitionId,
                    document,
                    mutation.getOperationId(),
                    mutation.getOperationGeneration(),
                    mutation.getPlacementGeneration(),
                    mutation.getLogicalPartitionId().isBlank() ? partitionId : mutation.getLogicalPartitionId(),
                    mutation.getPrimaryNodeId(),
                    !mutation.getReplica());
            REPLICATION_OUTCOMES
                    .labels(role, result.duplicate() ? "duplicate" : "applied")
                    .inc();
            return result;
        } catch (RuntimeException | IOException e) {
            REPLICATION_OUTCOMES
                    .labels(role, e instanceof IndexManager.StaleMutationException ? "stale" : "failed")
                    .inc();
            throw e;
        } finally {
            REPLICATION_APPLY_DURATION.observe((System.nanoTime() - started) / 1_000_000_000.0);
        }
    }

    private IndexManager.MutationResult applyReplicatedDelete(
            String partitionId, String documentId, MutationMetadata mutation) throws IOException {
        validateMutationTarget(mutation);
        long started = System.nanoTime();
        String role = mutation.getReplica() ? "replica" : "primary";
        try {
            IndexManager.MutationResult result = indexManager.applyReplicatedDelete(
                    partitionId,
                    documentId,
                    mutation.getOperationId(),
                    mutation.getOperationGeneration(),
                    mutation.getPlacementGeneration(),
                    mutation.getLogicalPartitionId().isBlank() ? partitionId : mutation.getLogicalPartitionId(),
                    mutation.getPrimaryNodeId(),
                    !mutation.getReplica());
            REPLICATION_OUTCOMES
                    .labels(role, result.duplicate() ? "duplicate" : "applied")
                    .inc();
            return result;
        } catch (RuntimeException | IOException e) {
            REPLICATION_OUTCOMES
                    .labels(role, e instanceof IndexManager.StaleMutationException ? "stale" : "failed")
                    .inc();
            throw e;
        } finally {
            REPLICATION_APPLY_DURATION.observe((System.nanoTime() - started) / 1_000_000_000.0);
        }
    }

    private void validateMutationTarget(MutationMetadata mutation) {
        if (mutation.getPrimaryNodeId().isBlank() || mutation.getTargetNodeId().isBlank()) {
            throw new IllegalArgumentException("replicated mutation must declare primary_node_id and target_node_id");
        }
        if (localNodeId != null && !localNodeId.isBlank() && !localNodeId.equals(mutation.getTargetNodeId())) {
            throw new IndexManager.StaleMutationException(
                    "mutation target " + mutation.getTargetNodeId() + " does not match local node " + localNodeId);
        }
        if (mutation.getReplica() == mutation.getPrimaryNodeId().equals(mutation.getTargetNodeId())) {
            throw new IndexManager.StaleMutationException(
                    mutation.getReplica()
                            ? "primary cannot accept a replica-role mutation"
                            : "non-primary node cannot accept a primary-role mutation");
        }
        long observed = latestPlacementGeneration.accumulateAndGet(mutation.getPlacementGeneration(), Math::max);
        if (mutation.getPlacementGeneration() < observed) {
            throw new IndexManager.StaleMutationException("placement generation " + mutation.getPlacementGeneration()
                    + " is older than node generation " + observed);
        }
    }

    @Override
    public void listReplicaManifests(
            ListReplicaManifestsRequest request, StreamObserver<ListReplicaManifestsResponse> responseObserver) {
        try {
            ListReplicaManifestsResponse.Builder response = ListReplicaManifestsResponse.newBuilder();
            for (IndexManager.ReplicaManifestData manifest : indexManager.replicaManifests()) {
                response.addManifests(toProto(manifest));
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (IOException | RuntimeException e) {
            repairError(responseObserver, "Failed to inspect replica manifests", e);
        }
    }

    @Override
    public void openReplicaSnapshot(
            OpenReplicaSnapshotRequest request, StreamObserver<OpenReplicaSnapshotResponse> responseObserver) {
        try {
            ReplicaRepairStore.SourceSnapshot snapshot =
                    replicaRepairStore.openSnapshot(request.getShardId(), request.getMaxSnapshotBytes());
            REPAIR_TRANSFERS.labels("open", "success").inc();
            responseObserver.onNext(OpenReplicaSnapshotResponse.newBuilder()
                    .setSnapshotId(snapshot.snapshotId())
                    .setTotalBytes(snapshot.payload().length)
                    .setTransferChecksum(snapshot.transferChecksum())
                    .setManifest(toProto(snapshot.manifest()))
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            REPAIR_TRANSFERS.labels("open", "rejected").inc();
            invalidArgument(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            REPAIR_TRANSFERS.labels("open", "failed").inc();
            repairError(responseObserver, "Failed to open replica snapshot", e);
        }
    }

    @Override
    public void readReplicaSnapshotChunk(
            ReadReplicaSnapshotChunkRequest request,
            StreamObserver<ReadReplicaSnapshotChunkResponse> responseObserver) {
        try {
            ReplicaRepairStore.SnapshotChunk chunk = replicaRepairStore.readSnapshot(
                    request.getSnapshotId(), request.getOffset(), request.getMaxBytes());
            responseObserver.onNext(ReadReplicaSnapshotChunkResponse.newBuilder()
                    .setOffset(chunk.offset())
                    .setData(com.google.protobuf.ByteString.copyFrom(chunk.data()))
                    .setComplete(chunk.complete())
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (RuntimeException e) {
            repairError(responseObserver, "Failed to read replica snapshot chunk", e);
        }
    }

    @Override
    public void beginReplicaRepair(
            BeginReplicaRepairRequest request, StreamObserver<BeginReplicaRepairResponse> responseObserver) {
        try {
            long offset = replicaRepairStore.begin(
                    request.getRepairId(),
                    request.getSnapshotId(),
                    request.getTotalBytes(),
                    request.getTransferChecksum(),
                    fromProto(request.getManifest()));
            REPAIR_TRANSFERS.labels("begin", "success").inc();
            responseObserver.onNext(BeginReplicaRepairResponse.newBuilder()
                    .setAcceptedOffset(offset)
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            REPAIR_TRANSFERS.labels("begin", "rejected").inc();
            invalidArgument(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            REPAIR_TRANSFERS.labels("begin", "failed").inc();
            repairError(responseObserver, "Failed to begin replica repair", e);
        }
    }

    @Override
    public void writeReplicaRepairChunk(
            WriteReplicaRepairChunkRequest request, StreamObserver<WriteReplicaRepairChunkResponse> responseObserver) {
        try {
            long offset = replicaRepairStore.write(
                    request.getRepairId(),
                    request.getOffset(),
                    request.getData().toByteArray());
            responseObserver.onNext(WriteReplicaRepairChunkResponse.newBuilder()
                    .setAcceptedOffset(offset)
                    .build());
            responseObserver.onCompleted();
        } catch (ReplicaRepairStore.OffsetMismatchException e) {
            responseObserver.onError(
                    Status.ABORTED.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            repairError(responseObserver, "Failed to write replica repair chunk", e);
        }
    }

    @Override
    public void finishReplicaRepair(
            FinishReplicaRepairRequest request, StreamObserver<FinishReplicaRepairResponse> responseObserver) {
        try {
            IndexManager.ReplicaManifestData manifest = replicaRepairStore.finish(request.getRepairId());
            REPAIR_TRANSFERS.labels("finish", "success").inc();
            responseObserver.onNext(FinishReplicaRepairResponse.newBuilder()
                    .setManifest(toProto(manifest))
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            REPAIR_TRANSFERS.labels("finish", "rejected").inc();
            invalidArgument(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            REPAIR_TRANSFERS.labels("finish", "failed").inc();
            repairError(responseObserver, "Failed to finish replica repair", e);
        }
    }

    @Override
    public void abortReplicaRepair(
            AbortReplicaRepairRequest request, StreamObserver<AbortReplicaRepairResponse> responseObserver) {
        try {
            replicaRepairStore.abort(request.getRepairId(), request.getReason());
            REPAIR_TRANSFERS.labels("abort", "success").inc();
            responseObserver.onNext(
                    AbortReplicaRepairResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            invalidArgument(responseObserver, e);
        } catch (IOException | RuntimeException e) {
            repairError(responseObserver, "Failed to abort replica repair", e);
        }
    }

    private static ReplicaManifest toProto(IndexManager.ReplicaManifestData manifest) {
        return ReplicaManifest.newBuilder()
                .setShardId(manifest.shardId())
                .setLogicalPartitionId(manifest.logicalPartitionId())
                .setPrimaryNodeId(manifest.primaryNodeId())
                .setPlacementGeneration(manifest.placementGeneration())
                .setCommittedPosition(manifest.committedPosition())
                .setContentChecksum(manifest.contentChecksum())
                .setDocumentCount(manifest.documentCount())
                .setState(manifest.state())
                .setLastError(manifest.lastError())
                .build();
    }

    private static IndexManager.ReplicaManifestData fromProto(ReplicaManifest manifest) {
        return new IndexManager.ReplicaManifestData(
                manifest.getShardId(),
                manifest.getLogicalPartitionId(),
                manifest.getPrimaryNodeId(),
                manifest.getPlacementGeneration(),
                manifest.getCommittedPosition(),
                manifest.getContentChecksum(),
                manifest.getDocumentCount(),
                manifest.getState(),
                manifest.getLastError());
    }

    private static void repairError(StreamObserver<?> responseObserver, String description, Exception error) {
        LOGGER.log(Level.SEVERE, description, error);
        responseObserver.onError(Status.INTERNAL
                .withDescription(description + ": " + error.getMessage())
                .withCause(error)
                .asRuntimeException());
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

    /**
     * Read-only admin preview: tokenizes sample text with the analyzer the resolved index actually
     * uses for indexing and query parsing. Never logs {@code request.getText()}, since sample text
     * is caller-supplied content, not an identifier safe to persist in logs or audit output.
     */
    @Override
    public void analyzeIndex(AnalyzeIndexRequest request, StreamObserver<AnalyzeIndexResponse> responseObserver) {
        try {
            RequestLimitsValidator.validateAnalyzeText(request.getText(), requestLimits);
            int maxTokens = Math.max(1, requestLimits.getMaxAnalyzeTokens());
            IndexManager.AnalyzedIndex analyzed =
                    indexManager.analyzeText(request.getIndexOrAlias(), request.getText(), maxTokens);
            AnalyzeIndexResponse.Builder builder = AnalyzeIndexResponse.newBuilder()
                    .setIndexName(analyzed.indexName())
                    .setAlias(analyzed.alias())
                    .setAnalyzer(analyzed.analyzer())
                    .setTruncated(analyzed.truncated());
            for (ShardIndex.AnalyzedToken token : analyzed.tokens()) {
                builder.addTokens(AnalyzeToken.newBuilder()
                        .setToken(token.text())
                        .setPosition(token.position())
                        .setStartOffset(token.startOffset())
                        .setEndOffset(token.endOffset())
                        .build());
            }
            responseObserver.onNext(builder.build());
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
            LOGGER.log(Level.SEVERE, "AnalyzeIndex failed for index " + request.getIndexOrAlias(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to analyze text")
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

    private static void failedPrecondition(StreamObserver<?> responseObserver, RuntimeException error) {
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
