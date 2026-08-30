package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.gateway.api.dto.IndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexResponseDto;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.Document;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    private static String resolvePartitionId(String partitionId) {
        return partitionId != null && !partitionId.isBlank() ? partitionId : DEFAULT_PARTITION_ID;
    }
}
