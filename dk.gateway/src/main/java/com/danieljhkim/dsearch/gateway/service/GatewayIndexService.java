package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.exception.NodeUnavailableException;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.shard.ReplicaPlacement;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.gateway.api.dto.BulkDeleteItemResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkDeleteRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkDeleteResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkIndexItemResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkIndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkIndexResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.GetDocumentResponseDto;
import com.danieljhkim.dsearch.proto.index.BulkDeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkDeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.BulkDeleteDocumentResult;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResult;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.index.GetDocumentRequest;
import com.danieljhkim.dsearch.proto.index.GetDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.proto.index.MutationMetadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gateway-side entry point for document mutations.
 *
 * <p>A Lucene upsert only replaces the document on the node that performs it, so
 * every mutation of {@code (partitionId, documentId)} is routed to the single
 * node that owns that key. See {@code docs/DOCUMENT_OWNERSHIP.md}.
 */
@Service
public class GatewayIndexService {

    private static final String DEFAULT_PARTITION_ID = "default";
    /** Protobuf sentinel asking the authoritative primary to allocate the next document generation. */
    private static final long ALLOCATE_OPERATION_GENERATION = 0L;

    private static final Counter ACK_OUTCOMES = Counter.build()
            .name("dsearch_replication_acknowledgements_total")
            .help("Replicated mutation acknowledgement outcomes by bounded policy and outcome")
            .labelNames("policy", "outcome")
            .register();
    private static final Histogram ACK_LATENCY = Histogram.build()
            .name("dsearch_replication_ack_latency_seconds")
            .help("Latency until a replicated mutation reaches its configured acknowledgement policy")
            .register();
    private static final Gauge REPLICA_LAG = Gauge.build()
            .name("dsearch_replication_unacknowledged_replicas")
            .help("Replica attempts missing from the most recently completed mutation")
            .register();

    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager;
    private final AppConfig.RequestLimitsConfig requestLimits;

    @Autowired
    public GatewayIndexService(
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager, AppConfig appConfig) {
        this.indexNodeClientManager = indexNodeClientManager;
        this.requestLimits = RequestLimitsValidator.limitsOrDefaults(appConfig.getRequestLimits());
    }

    GatewayIndexService(NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager) {
        this.indexNodeClientManager = indexNodeClientManager;
        this.requestLimits = new AppConfig.RequestLimitsConfig();
    }

    public IndexResponseDto index(IndexRequestDto requestDto) {
        String partitionId = resolvePartitionId(requestDto.getPartitionId());
        // The id has to be minted here: an id assigned downstream would be unknown to the
        // ownership function, so later updates to the same document could pick another node.
        String documentId = requestDto.getId() != null && !requestDto.getId().isBlank()
                ? requestDto.getId()
                : UUID.randomUUID().toString();
        RequestLimitsValidator.validateDocument(documentId, requestDto.getFields(), requestLimits);

        Document.Builder docBuilder = Document.newBuilder().setId(documentId);
        Map<String, String> fields = requestDto.getFields();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                Field f = Field.newBuilder()
                        .setName(e.getKey())
                        .setValue(e.getValue())
                        .build();
                docBuilder.addFields(f);
            }
        }

        String operationId = requestDto.getOperationId() == null
                        || requestDto.getOperationId().isBlank()
                ? UUID.randomUUID().toString()
                : requestDto.getOperationId();
        long operationGeneration = resolveOperationGeneration(requestDto.getGeneration());
        if (indexNodeClientManager.getReplicationFactor() == 0) {
            return legacyIndexViaOwner(partitionId, docBuilder.build());
        }
        return replicateIndex(partitionId, docBuilder.build(), operationId, operationGeneration);
    }

    public IndexResponseDto delete(String id, String partitionId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        RequestLimitsValidator.validateDocument(id, Map.of(), requestLimits);

        String resolvedPartitionId = resolvePartitionId(partitionId);
        if (indexNodeClientManager.getReplicationFactor() == 0) {
            return legacyDeleteViaOwner(resolvedPartitionId, id);
        }
        return replicateDelete(resolvedPartitionId, id, UUID.randomUUID().toString(), ALLOCATE_OPERATION_GENERATION);
    }

    /**
     * Retrieves one exact document through the declared logical-shard read plan. This deliberately
     * never uses the write-owner client: an eligible replica may serve an acknowledged read after
     * primary failure, while a range with no eligible copy is unavailable rather than absent.
     */
    public GetDocumentResponseDto get(String id, String partitionId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        RequestLimitsValidator.validateDocument(id, Map.of(), requestLimits);
        String resolvedPartitionId = resolvePartitionId(partitionId);
        String ownerNodeId = indexNodeClientManager.ownerNodeId(resolvedPartitionId, id);
        String logicalShardId = ReplicaPlacement.logicalShardId(ownerNodeId);
        ReplicaPlacement.ReadPlan readPlan = indexNodeClientManager.replicaReadPlan(resolvedPartitionId);
        if (readPlan.unavailableLogicalShardIds().contains(logicalShardId)) {
            throw new NodeUnavailableException(
                    ownerNodeId, "No eligible replica for logical shard " + logicalShardId + "; exact lookup is unavailable");
        }
        ReplicaPlacement.ReadTarget target = readPlan.targets().stream()
                .filter(candidate -> logicalShardId.equals(candidate.logicalShardId()))
                .findFirst()
                .orElseThrow(() -> new NodeUnavailableException(
                        ownerNodeId, "No read target for logical shard " + logicalShardId + "; exact lookup is unavailable"));
        NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> client =
                indexNodeClientManager.getClientMap().get(target.nodeId());
        if (client == null || !client.isActive()) {
            throw new NodeUnavailableException(
                    target.nodeId(), "Selected read replica " + target.nodeId() + " is unavailable for " + logicalShardId);
        }
        GetDocumentResponse response = client.getStub()
                .withDeadlineAfter(Math.max(1, requestLimits.getRequestTimeoutMillis()), TimeUnit.MILLISECONDS)
                .getDocument(GetDocumentRequest.newBuilder()
                        .setPartitionId(target.storagePartitionId())
                        .setId(id)
                        .build());
        Map<String, String> fields = new LinkedHashMap<>();
        response.getDocument().getFieldsList().forEach(field -> fields.put(field.getName(), field.getValue()));
        return new GetDocumentResponseDto(resolvedPartitionId, response.getDocument().getId(), fields);
    }

    /** Compatibility seam for older injected manager doubles; constructed managers always report at least one. */
    private IndexResponseDto legacyIndexViaOwner(String partitionId, Document document) {
        NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner =
                indexNodeClientManager.ownerClient(partitionId, document.getId());
        IndexDocumentResponse response = owner.getStub()
                .withDeadlineAfter(Math.max(1, requestLimits.getRequestTimeoutMillis()), TimeUnit.MILLISECONDS)
                .indexDocument(IndexDocumentRequest.newBuilder()
                        .setPartitionId(partitionId)
                        .setDocument(document)
                        .build());
        if (response.getSuccess()) {
            owner.incrementDocToShard(partitionId);
        }
        return new IndexResponseDto(response.getId(), response.getSuccess());
    }

    private IndexResponseDto legacyDeleteViaOwner(String partitionId, String documentId) {
        NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner =
                indexNodeClientManager.ownerClient(partitionId, documentId);
        DeleteDocumentResponse response = owner.getStub()
                .withDeadlineAfter(Math.max(1, requestLimits.getRequestTimeoutMillis()), TimeUnit.MILLISECONDS)
                .deleteDocument(DeleteDocumentRequest.newBuilder()
                        .setPartitionId(partitionId)
                        .setId(documentId)
                        .build());
        if (response.getSuccess()) {
            owner.decrementDocFromShard(partitionId);
        }
        return new IndexResponseDto(documentId, response.getSuccess());
    }

    private IndexResponseDto replicateIndex(
            String partitionId, Document document, String operationId, long operationGeneration) {
        var plan = indexNodeClientManager.replicaWritePlan(partitionId, document.getId());
        long deadlineNanos = replicationDeadlineNanos();
        long started = System.nanoTime();
        int acknowledgements = 0;
        long committedGeneration = operationGeneration;
        RuntimeException lastFailure = null;
        for (var target : plan.targets()) {
            if (!target.active() || target.client() == null) {
                lastFailure = new NodeUnavailableException(
                        target.nodeId(),
                        "Replica " + target.nodeId() + " is unavailable for logical shard "
                                + plan.replicaSet().shardId());
                continue;
            }
            try {
                IndexDocumentResponse response = target.client()
                        .getStub()
                        .withDeadlineAfter(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
                        .indexDocument(IndexDocumentRequest.newBuilder()
                                .setPartitionId(target.storagePartitionId())
                                .setDocument(document)
                                .setMutation(mutationMetadata(
                                        plan.replicaSet(),
                                        target.nodeId(),
                                        target.primary(),
                                        operationId,
                                        target.primary() ? operationGeneration : committedGeneration,
                                        partitionId))
                                .build());
                if (!response.getSuccess()) {
                    throw Status.INTERNAL
                            .withDescription("replica returned an unsuccessful durable index response")
                            .asRuntimeException();
                }
                committedGeneration = confirmCommittedGeneration(
                        target.primary(), operationGeneration, committedGeneration, response.getCommittedGeneration());
                acknowledgements++;
                if (!response.getDuplicate()) {
                    target.client().incrementDocToShard(partitionId);
                }
            } catch (RuntimeException e) {
                lastFailure = e;
                if (target.primary()) {
                    recordAck(plan, acknowledgements, started, false);
                    throw e;
                }
            }
        }
        requireAcknowledgements(plan, acknowledgements, lastFailure, started);
        return new IndexResponseDto(
                document.getId(),
                true,
                operationId,
                committedGeneration,
                acknowledgements,
                plan.replicaSet().requiredAcknowledgements());
    }

    private IndexResponseDto replicateDelete(
            String partitionId, String documentId, String operationId, long operationGeneration) {
        var plan = indexNodeClientManager.replicaWritePlan(partitionId, documentId);
        long deadlineNanos = replicationDeadlineNanos();
        long started = System.nanoTime();
        int acknowledgements = 0;
        long committedGeneration = operationGeneration;
        RuntimeException lastFailure = null;
        for (var target : plan.targets()) {
            if (!target.active() || target.client() == null) {
                lastFailure = new NodeUnavailableException(
                        target.nodeId(),
                        "Replica " + target.nodeId() + " is unavailable for logical shard "
                                + plan.replicaSet().shardId());
                continue;
            }
            try {
                DeleteDocumentResponse response = target.client()
                        .getStub()
                        .withDeadlineAfter(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
                        .deleteDocument(DeleteDocumentRequest.newBuilder()
                                .setPartitionId(target.storagePartitionId())
                                .setId(documentId)
                                .setMutation(mutationMetadata(
                                        plan.replicaSet(),
                                        target.nodeId(),
                                        target.primary(),
                                        operationId,
                                        target.primary() ? operationGeneration : committedGeneration,
                                        partitionId))
                                .build());
                if (!response.getSuccess()) {
                    throw Status.INTERNAL
                            .withDescription("replica returned an unsuccessful durable delete response")
                            .asRuntimeException();
                }
                committedGeneration = confirmCommittedGeneration(
                        target.primary(), operationGeneration, committedGeneration, response.getCommittedGeneration());
                acknowledgements++;
                if (!response.getDuplicate()) {
                    target.client().decrementDocFromShard(partitionId);
                }
            } catch (RuntimeException e) {
                lastFailure = e;
                if (target.primary()) {
                    recordAck(plan, acknowledgements, started, false);
                    throw e;
                }
            }
        }
        requireAcknowledgements(plan, acknowledgements, lastFailure, started);
        return new IndexResponseDto(
                documentId,
                true,
                operationId,
                committedGeneration,
                acknowledgements,
                plan.replicaSet().requiredAcknowledgements());
    }

    private static long confirmCommittedGeneration(
            boolean primary, long requestedGeneration, long committedGeneration, long responseGeneration) {
        if (responseGeneration < 1) {
            throw Status.INTERNAL
                    .withDescription("replica returned an invalid committed operation generation")
                    .asRuntimeException();
        }
        if (primary) {
            if (requestedGeneration > 0 && responseGeneration != requestedGeneration) {
                throw Status.INTERNAL
                        .withDescription("primary changed an explicitly supplied operation generation")
                        .asRuntimeException();
            }
            return responseGeneration;
        }
        if (responseGeneration != committedGeneration) {
            throw Status.INTERNAL
                    .withDescription("replica committed an unexpected operation generation")
                    .asRuntimeException();
        }
        return committedGeneration;
    }

    private static long resolveOperationGeneration(Long callerGeneration) {
        if (callerGeneration == null) {
            return ALLOCATE_OPERATION_GENERATION;
        }
        if (callerGeneration < 1) {
            throw new IllegalArgumentException("generation must be positive when supplied");
        }
        return callerGeneration;
    }

    private MutationMetadata mutationMetadata(
            com.danieljhkim.dsearch.common.shard.ReplicaPlacement.ReplicaSet replicaSet,
            String targetNodeId,
            boolean primary,
            String operationId,
            long operationGeneration,
            String logicalPartitionId) {
        return MutationMetadata.newBuilder()
                .setOperationId(operationId)
                .setOperationGeneration(operationGeneration)
                .setPlacementGeneration(replicaSet.generation())
                .setPrimaryNodeId(replicaSet.primaryNodeId())
                .setTargetNodeId(targetNodeId)
                .setReplica(!primary)
                .setLogicalPartitionId(logicalPartitionId)
                .build();
    }

    private void requireAcknowledgements(
            NodeClientManager.ReplicaWritePlan<IndexServiceGrpc.IndexServiceBlockingStub> plan,
            int acknowledgements,
            RuntimeException lastFailure,
            long started) {
        boolean success = acknowledgements >= plan.replicaSet().requiredAcknowledgements();
        recordAck(plan, acknowledgements, started, success);
        if (!success) {
            throw Status.UNAVAILABLE
                    .withDescription("replicated mutation reached " + acknowledgements + " acknowledgements but policy "
                            + indexNodeClientManager
                                    .getDurabilityPolicy()
                                    .name()
                                    .toLowerCase() + " requires "
                            + plan.replicaSet().requiredAcknowledgements())
                    .withCause(lastFailure)
                    .asRuntimeException();
        }
    }

    private void recordAck(
            NodeClientManager.ReplicaWritePlan<IndexServiceGrpc.IndexServiceBlockingStub> plan,
            int acknowledgements,
            long started,
            boolean success) {
        String policy = indexNodeClientManager.getDurabilityPolicy().name().toLowerCase();
        ACK_OUTCOMES.labels(policy, success ? "acknowledged" : "insufficient").inc();
        ACK_LATENCY.observe((System.nanoTime() - started) / 1_000_000_000.0);
        REPLICA_LAG.set(Math.max(0, plan.targets().size() - acknowledgements));
    }

    private long replicationDeadlineNanos() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, requestLimits.getRequestTimeoutMillis()));
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw Status.DEADLINE_EXCEEDED
                    .withDescription("replication acknowledgement deadline exhausted")
                    .asRuntimeException();
        }
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    /**
     * Sends valid items to their ownership node in batches while retaining the original result order.
     *
     * <p>Every accepted item has a caller-provided id. It is both the routing key and Lucene upsert key,
     * which makes a retry after a transport timeout safe even when the previous durable commit outcome is
     * unknown. Per-item validation failures never prevent independent valid items from being attempted.
     */
    public BulkIndexResponseDto bulkIndex(BulkIndexRequestDto requestDto) {
        List<IndexRequestDto> items = requestDto.getItems() == null ? List.of() : requestDto.getItems();
        ensureBulkItemCount(items.size());

        String partitionId = resolvePartitionId(requestDto.getPartitionId());
        if (indexNodeClientManager.getReplicationFactor() > 1) {
            return bulkIndexReplicated(partitionId, items);
        }
        List<BulkIndexItemResponseDto> results = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            results.add(null);
        }

        Map<NodeClient<IndexServiceGrpc.IndexServiceBlockingStub>, List<PreparedBulkItem>> byOwner =
                new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        long embeddingBytes = 0;
        for (int requestIndex = 0; requestIndex < items.size(); requestIndex++) {
            IndexRequestDto item = items.get(requestIndex);
            if (item == null || item.getId() == null || item.getId().isBlank()) {
                results.set(
                        requestIndex,
                        validationFailure(requestIndex, item == null ? null : item.getId(), "id is required"));
                continue;
            }
            if (!seenIds.add(item.getId())) {
                results.set(
                        requestIndex, validationFailure(requestIndex, item.getId(), "duplicate id in bulk request"));
                continue;
            }

            try {
                RequestLimitsValidator.validateDocument(item.getId(), item.getFields(), requestLimits);
                embeddingBytes = Math.addExact(embeddingBytes, embeddingWorkBytes(item.getFields()));
            } catch (IllegalArgumentException | ArithmeticException e) {
                results.set(requestIndex, validationFailure(requestIndex, item.getId(), e.getMessage()));
                continue;
            }
            ensureBulkEmbeddingBytes(embeddingBytes);

            Document document = toProtoDocument(item.getId(), item.getFields());
            try {
                NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner =
                        indexNodeClientManager.ownerClient(partitionId, item.getId());
                byOwner.computeIfAbsent(owner, ignored -> new ArrayList<>())
                        .add(new PreparedBulkItem(requestIndex, item.getId(), document));
            } catch (NodeUnavailableException e) {
                results.set(requestIndex, retryableFailure(requestIndex, item.getId(), e.getMessage()));
            }
        }

        long deadlineNanos =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, requestLimits.getRequestTimeoutMillis()));
        byOwner.forEach(
                (owner, ownerItems) -> bulkIndexOnOwner(owner, partitionId, ownerItems, results, deadlineNanos));
        boolean success = results.stream().allMatch(result -> result != null && "success".equals(result.getStatus()));
        return new BulkIndexResponseDto(success, results);
    }

    /**
     * Deletes explicit ids from their ownership node in batches while retaining the original result
     * order, including duplicates.
     *
     * <p>Deleting an id is idempotent: a missing document deletes successfully, exactly like single
     * delete. Duplicate ids are therefore never rejected as a conflict the way a duplicate upsert id
     * is; each occurrence is routed and reported independently.
     */
    public BulkDeleteResponseDto bulkDelete(BulkDeleteRequestDto requestDto) {
        List<String> ids = requestDto.getIds() == null ? List.of() : requestDto.getIds();
        ensureBulkItemCount(ids.size());

        String partitionId = resolvePartitionId(requestDto.getPartitionId());
        if (indexNodeClientManager.getReplicationFactor() > 1) {
            return bulkDeleteReplicated(partitionId, ids);
        }
        List<BulkDeleteItemResponseDto> results = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            results.add(null);
        }

        Map<NodeClient<IndexServiceGrpc.IndexServiceBlockingStub>, List<PreparedBulkDeleteItem>> byOwner =
                new LinkedHashMap<>();
        for (int requestIndex = 0; requestIndex < ids.size(); requestIndex++) {
            String id = ids.get(requestIndex);
            if (id == null || id.isBlank()) {
                results.set(requestIndex, deleteValidationFailure(requestIndex, id, "id must not be blank"));
                continue;
            }
            try {
                RequestLimitsValidator.validateDocumentId(id, requestLimits);
            } catch (IllegalArgumentException e) {
                results.set(requestIndex, deleteValidationFailure(requestIndex, id, e.getMessage()));
                continue;
            }

            try {
                NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner =
                        indexNodeClientManager.ownerClient(partitionId, id);
                byOwner.computeIfAbsent(owner, ignored -> new ArrayList<>())
                        .add(new PreparedBulkDeleteItem(requestIndex, id));
            } catch (NodeUnavailableException e) {
                results.set(requestIndex, deleteRetryableFailure(requestIndex, id, e.getMessage()));
            }
        }

        long deadlineNanos =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, requestLimits.getRequestTimeoutMillis()));
        byOwner.forEach(
                (owner, ownerItems) -> bulkDeleteOnOwner(owner, partitionId, ownerItems, results, deadlineNanos));
        boolean success = results.stream().allMatch(result -> result != null && "success".equals(result.getStatus()));
        return new BulkDeleteResponseDto(success, results);
    }

    private void bulkDeleteOnOwner(
            NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner,
            String partitionId,
            List<PreparedBulkDeleteItem> ownerItems,
            List<BulkDeleteItemResponseDto> results,
            long deadlineNanos) {
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new StatusRuntimeException(
                        Status.DEADLINE_EXCEEDED.withDescription("bulk delete deadline exhausted"));
            }
            BulkDeleteDocumentRequest request = BulkDeleteDocumentRequest.newBuilder()
                    .setPartitionId(partitionId)
                    .addAllIds(
                            ownerItems.stream().map(PreparedBulkDeleteItem::id).toList())
                    .build();
            BulkDeleteDocumentResponse response = owner.getStub()
                    .withDeadlineAfter(
                            Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)), TimeUnit.MILLISECONDS)
                    .bulkDeleteDocument(request);
            applyOwnerDeleteResults(owner, partitionId, ownerItems, response, results);
        } catch (StatusRuntimeException | NodeUnavailableException e) {
            for (PreparedBulkDeleteItem item : ownerItems) {
                results.set(item.requestIndex(), deleteTransportFailure(item.requestIndex(), item.id(), e));
            }
        }
    }

    private BulkIndexResponseDto bulkIndexReplicated(String partitionId, List<IndexRequestDto> items) {
        List<BulkIndexItemResponseDto> results = new ArrayList<>(items.size());
        Set<String> seenIds = new HashSet<>();
        long embeddingBytes = 0;
        for (int requestIndex = 0; requestIndex < items.size(); requestIndex++) {
            IndexRequestDto item = items.get(requestIndex);
            if (item == null || item.getId() == null || item.getId().isBlank()) {
                results.add(validationFailure(requestIndex, item == null ? null : item.getId(), "id is required"));
                continue;
            }
            if (!seenIds.add(item.getId())) {
                results.add(validationFailure(requestIndex, item.getId(), "duplicate id in bulk request"));
                continue;
            }
            try {
                RequestLimitsValidator.validateDocument(item.getId(), item.getFields(), requestLimits);
                embeddingBytes = Math.addExact(embeddingBytes, embeddingWorkBytes(item.getFields()));
                ensureBulkEmbeddingBytes(embeddingBytes);
                String operationId =
                        item.getOperationId() == null || item.getOperationId().isBlank()
                                ? UUID.randomUUID().toString()
                                : item.getOperationId();
                long generation = resolveOperationGeneration(item.getGeneration());
                replicateIndex(partitionId, toProtoDocument(item.getId(), item.getFields()), operationId, generation);
                results.add(new BulkIndexItemResponseDto(requestIndex, item.getId(), "success", null));
            } catch (IllegalArgumentException e) {
                results.add(validationFailure(requestIndex, item.getId(), e.getMessage()));
            } catch (RuntimeException e) {
                results.add(transportFailure(requestIndex, item.getId(), e));
            }
        }
        return new BulkIndexResponseDto(
                results.stream().allMatch(result -> "success".equals(result.getStatus())), results);
    }

    private BulkDeleteResponseDto bulkDeleteReplicated(String partitionId, List<String> ids) {
        List<BulkDeleteItemResponseDto> results = new ArrayList<>(ids.size());
        for (int requestIndex = 0; requestIndex < ids.size(); requestIndex++) {
            String id = ids.get(requestIndex);
            if (id == null || id.isBlank()) {
                results.add(deleteValidationFailure(requestIndex, id, "id must not be blank"));
                continue;
            }
            try {
                RequestLimitsValidator.validateDocumentId(id, requestLimits);
                replicateDelete(partitionId, id, UUID.randomUUID().toString(), ALLOCATE_OPERATION_GENERATION);
                results.add(new BulkDeleteItemResponseDto(requestIndex, id, "success", null));
            } catch (IllegalArgumentException e) {
                results.add(deleteValidationFailure(requestIndex, id, e.getMessage()));
            } catch (RuntimeException e) {
                results.add(deleteTransportFailure(requestIndex, id, e));
            }
        }
        return new BulkDeleteResponseDto(
                results.stream().allMatch(result -> "success".equals(result.getStatus())), results);
    }

    private static void applyOwnerDeleteResults(
            NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner,
            String partitionId,
            List<PreparedBulkDeleteItem> ownerItems,
            BulkDeleteDocumentResponse response,
            List<BulkDeleteItemResponseDto> results) {
        boolean[] resultSeen = new boolean[ownerItems.size()];
        for (BulkDeleteDocumentResult itemResult : response.getResultsList()) {
            int responseIndex = itemResult.getRequestIndex();
            if (responseIndex < 0 || responseIndex >= ownerItems.size()) {
                continue;
            }
            resultSeen[responseIndex] = true;
            PreparedBulkDeleteItem item = ownerItems.get(responseIndex);
            if (itemResult.getSuccess()) {
                owner.decrementDocFromShard(partitionId);
                results.set(
                        item.requestIndex(),
                        new BulkDeleteItemResponseDto(item.requestIndex(), item.id(), "success", null));
            } else {
                results.set(
                        item.requestIndex(),
                        deleteRetryableFailure(
                                item.requestIndex(),
                                item.id(),
                                itemResult.getError().isBlank() ? "durable delete failed" : itemResult.getError()));
            }
        }
        for (int responseIndex = 0; responseIndex < ownerItems.size(); responseIndex++) {
            if (!resultSeen[responseIndex]) {
                PreparedBulkDeleteItem item = ownerItems.get(responseIndex);
                results.set(
                        item.requestIndex(),
                        deleteRetryableFailure(item.requestIndex(), item.id(), "owner returned no item result"));
            }
        }
    }

    private void bulkIndexOnOwner(
            NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner,
            String partitionId,
            List<PreparedBulkItem> ownerItems,
            List<BulkIndexItemResponseDto> results,
            long deadlineNanos) {
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("bulk deadline exhausted"));
            }
            BulkIndexDocumentRequest request = BulkIndexDocumentRequest.newBuilder()
                    .setPartitionId(partitionId)
                    .addAllDocuments(
                            ownerItems.stream().map(PreparedBulkItem::document).toList())
                    .build();
            BulkIndexDocumentResponse response = owner.getStub()
                    .withDeadlineAfter(
                            Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)), TimeUnit.MILLISECONDS)
                    .bulkIndexDocument(request);
            applyOwnerResults(owner, partitionId, ownerItems, response, results);
        } catch (StatusRuntimeException | NodeUnavailableException e) {
            for (PreparedBulkItem item : ownerItems) {
                results.set(item.requestIndex(), transportFailure(item.requestIndex(), item.id(), e));
            }
        }
    }

    private static void applyOwnerResults(
            NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner,
            String partitionId,
            List<PreparedBulkItem> ownerItems,
            BulkIndexDocumentResponse response,
            List<BulkIndexItemResponseDto> results) {
        boolean[] resultSeen = new boolean[ownerItems.size()];
        for (BulkIndexDocumentResult itemResult : response.getResultsList()) {
            int responseIndex = itemResult.getRequestIndex();
            if (responseIndex < 0 || responseIndex >= ownerItems.size()) {
                continue;
            }
            resultSeen[responseIndex] = true;
            PreparedBulkItem item = ownerItems.get(responseIndex);
            if (itemResult.getSuccess()) {
                owner.incrementDocToShard(partitionId);
                results.set(
                        item.requestIndex(),
                        new BulkIndexItemResponseDto(item.requestIndex(), item.id(), "success", null));
            } else {
                results.set(
                        item.requestIndex(),
                        retryableFailure(
                                item.requestIndex(),
                                item.id(),
                                itemResult.getError().isBlank() ? "durable index failed" : itemResult.getError()));
            }
        }
        for (int responseIndex = 0; responseIndex < ownerItems.size(); responseIndex++) {
            if (!resultSeen[responseIndex]) {
                PreparedBulkItem item = ownerItems.get(responseIndex);
                results.set(
                        item.requestIndex(),
                        retryableFailure(item.requestIndex(), item.id(), "owner returned no item result"));
            }
        }
    }

    private void ensureBulkItemCount(int itemCount) {
        try {
            RequestLimitsValidator.validateBulkItemCount(itemCount, requestLimits);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage(), e);
        }
    }

    private void ensureBulkEmbeddingBytes(long embeddingBytes) {
        try {
            RequestLimitsValidator.validateBulkEmbeddingBytes(embeddingBytes, requestLimits);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage(), e);
        }
    }

    private static long embeddingWorkBytes(Map<String, String> fields) {
        if (fields == null) {
            return 0;
        }
        long bytes = 0;
        for (String value : fields.values()) {
            bytes = Math.addExact(bytes, value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length);
        }
        return bytes;
    }

    private static Document toProtoDocument(String documentId, Map<String, String> fields) {
        Document.Builder document = Document.newBuilder().setId(documentId);
        if (fields != null) {
            fields.forEach((name, value) ->
                    document.addFields(Field.newBuilder().setName(name).setValue(value)));
        }
        return document.build();
    }

    private static BulkIndexItemResponseDto validationFailure(int requestIndex, String id, String error) {
        return new BulkIndexItemResponseDto(requestIndex, id, "validation_failure", error);
    }

    private static BulkIndexItemResponseDto retryableFailure(int requestIndex, String id, String error) {
        return new BulkIndexItemResponseDto(
                requestIndex,
                id,
                "retryable_failure",
                error + "; retry with the same id because indexing is an upsert");
    }

    private static BulkIndexItemResponseDto permanentFailure(int requestIndex, String id, String error) {
        return new BulkIndexItemResponseDto(requestIndex, id, "permanent_failure", error);
    }

    private static BulkIndexItemResponseDto transportFailure(int requestIndex, String id, RuntimeException exception) {
        if (exception instanceof StatusRuntimeException statusException
                && switch (statusException.getStatus().getCode()) {
                    case INVALID_ARGUMENT, ALREADY_EXISTS, FAILED_PRECONDITION, PERMISSION_DENIED -> true;
                    default -> false;
                }) {
            return permanentFailure(requestIndex, id, retryMessage(exception));
        }
        return retryableFailure(requestIndex, id, retryMessage(exception));
    }

    private static String retryMessage(RuntimeException exception) {
        if (exception instanceof StatusRuntimeException statusException
                && statusException.getStatus().getDescription() != null) {
            return statusException.getStatus().getDescription();
        }
        return exception.getMessage() == null ? "bulk transport failed" : exception.getMessage();
    }

    private record PreparedBulkItem(int requestIndex, String id, Document document) {}

    private record PreparedBulkDeleteItem(int requestIndex, String id) {}

    private static BulkDeleteItemResponseDto deleteValidationFailure(int requestIndex, String id, String error) {
        return new BulkDeleteItemResponseDto(requestIndex, id, "validation_failure", error);
    }

    private static BulkDeleteItemResponseDto deleteRetryableFailure(int requestIndex, String id, String error) {
        return new BulkDeleteItemResponseDto(
                requestIndex,
                id,
                "retryable_failure",
                error + "; retry with the same id because deletion is idempotent");
    }

    private static BulkDeleteItemResponseDto deletePermanentFailure(int requestIndex, String id, String error) {
        return new BulkDeleteItemResponseDto(requestIndex, id, "permanent_failure", error);
    }

    private static BulkDeleteItemResponseDto deleteTransportFailure(
            int requestIndex, String id, RuntimeException exception) {
        if (exception instanceof StatusRuntimeException statusException
                && switch (statusException.getStatus().getCode()) {
                    case INVALID_ARGUMENT, ALREADY_EXISTS, FAILED_PRECONDITION, PERMISSION_DENIED -> true;
                    default -> false;
                }) {
            return deletePermanentFailure(requestIndex, id, retryMessage(exception));
        }
        return deleteRetryableFailure(requestIndex, id, retryMessage(exception));
    }

    private static String resolvePartitionId(String partitionId) {
        return partitionId != null && !partitionId.isBlank() ? partitionId : DEFAULT_PARTITION_ID;
    }
}
