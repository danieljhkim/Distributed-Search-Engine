package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.exception.NodeUnavailableException;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.gateway.api.dto.BulkDeleteItemResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkDeleteRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkDeleteResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkIndexItemResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkIndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkIndexResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexResponseDto;
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
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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

        NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner =
                indexNodeClientManager.ownerClient(partitionId, documentId);

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

        IndexDocumentRequest grpcReq = IndexDocumentRequest.newBuilder()
                .setPartitionId(partitionId)
                .setDocument(docBuilder.build())
                .build();
        IndexDocumentResponse resp = owner.getStub()
                .withDeadlineAfter(Math.max(1, requestLimits.getRequestTimeoutMillis()), TimeUnit.MILLISECONDS)
                .indexDocument(grpcReq);
        if (resp.getSuccess()) {
            owner.incrementDocToShard(partitionId);
        }
        return new IndexResponseDto(resp.getId(), resp.getSuccess());
    }

    public IndexResponseDto delete(String id, String partitionId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        RequestLimitsValidator.validateDocument(id, Map.of(), requestLimits);

        String resolvedPartitionId = resolvePartitionId(partitionId);
        NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> owner =
                indexNodeClientManager.ownerClient(resolvedPartitionId, id);

        DeleteDocumentRequest grpcReq = DeleteDocumentRequest.newBuilder()
                .setPartitionId(resolvedPartitionId)
                .setId(id)
                .build();
        DeleteDocumentResponse resp = owner.getStub()
                .withDeadlineAfter(Math.max(1, requestLimits.getRequestTimeoutMillis()), TimeUnit.MILLISECONDS)
                .deleteDocument(grpcReq);
        if (resp.getSuccess()) {
            owner.decrementDocFromShard(resolvedPartitionId);
        }
        return new IndexResponseDto(id, resp.getSuccess());
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
