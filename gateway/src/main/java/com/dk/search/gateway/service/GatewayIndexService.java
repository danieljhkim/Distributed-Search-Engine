package com.dk.search.gateway.service;

import com.dk.search.gateway.api.dto.IndexRequestDto;
import com.dk.search.gateway.api.dto.IndexResponseDto;
import com.dk.search.proto.index.*;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class GatewayIndexService {

    private final IndexServiceGrpc.IndexServiceBlockingStub indexStub;

    public GatewayIndexService(IndexServiceGrpc.IndexServiceBlockingStub indexStub) {
        this.indexStub = indexStub;
    }

    public IndexResponseDto index(IndexRequestDto requestDto) {
        int shardId = requestDto.getShardId() != null ? requestDto.getShardId() : 0;

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
                .setShardId(shardId)
                .setDocument(docBuilder.build())
                .build();

        IndexDocumentResponse resp = indexStub.indexDocument(grpcReq);

        return new IndexResponseDto(resp.getId(), resp.getSuccess());
    }
}