package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
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
import org.springframework.stereotype.Service;

@Service
public class GatewayIndexService {

    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager;

    public GatewayIndexService(NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager) {
        this.indexNodeClientManager = indexNodeClientManager;
    }

    public IndexResponseDto index(IndexRequestDto requestDto) {
        String partitionId = requestDto.getPartitionId() != null ? requestDto.getPartitionId() : "default";
        var indexStub = indexNodeClientManager.nextClient(partitionId, true);

        Document.Builder docBuilder = Document.newBuilder();
        if (requestDto.getId() != null && !requestDto.getId().isEmpty()) {
            docBuilder.setId(requestDto.getId());
        }

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
        IndexDocumentResponse resp = indexStub.indexDocument(grpcReq);
        return new IndexResponseDto(resp.getId(), resp.getSuccess());
    }

    public IndexResponseDto delete(String id, String partitionId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }

        String resolvedPartitionId = partitionId != null && !partitionId.isBlank() ? partitionId : "default";
        var indexStub = indexNodeClientManager.nextClient(resolvedPartitionId, false);

        DeleteDocumentRequest grpcReq = DeleteDocumentRequest.newBuilder()
                .setPartitionId(resolvedPartitionId)
                .setId(id)
                .build();
        DeleteDocumentResponse resp = indexStub.deleteDocument(grpcReq);
        return new IndexResponseDto(id, resp.getSuccess());
    }
}
