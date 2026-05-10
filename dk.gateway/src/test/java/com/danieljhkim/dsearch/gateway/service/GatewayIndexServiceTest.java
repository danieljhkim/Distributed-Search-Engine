package com.danieljhkim.dsearch.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.gateway.api.dto.IndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexResponseDto;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GatewayIndexServiceTest {

    @Mock
    private NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager;

    @Mock
    private IndexServiceGrpc.IndexServiceBlockingStub indexStub;

    private GatewayIndexService service;

    @BeforeEach
    void setUp() {
        service = new GatewayIndexService(indexNodeClientManager);
    }

    @Test
    void indexMapsDtoToGrpcRequestWithoutNetworkCalls() {
        IndexRequestDto request = new IndexRequestDto();
        request.setId("doc-1");
        request.setPartitionId("tenant-a");
        request.setFields(Map.of("title", "Distributed Search", "category", "docs"));
        when(indexNodeClientManager.nextClient("tenant-a", true)).thenReturn(indexStub);
        when(indexStub.indexDocument(any(IndexDocumentRequest.class)))
                .thenReturn(IndexDocumentResponse.newBuilder()
                        .setId("doc-1")
                        .setSuccess(true)
                        .build());

        IndexResponseDto response = service.index(request);

        assertThat(response.getId()).isEqualTo("doc-1");
        assertThat(response.isSuccess()).isTrue();

        ArgumentCaptor<IndexDocumentRequest> requestCaptor = ArgumentCaptor.forClass(IndexDocumentRequest.class);
        verify(indexStub).indexDocument(requestCaptor.capture());
        IndexDocumentRequest grpcRequest = requestCaptor.getValue();
        assertThat(grpcRequest.getPartitionId()).isEqualTo("tenant-a");
        assertThat(grpcRequest.getDocument().getId()).isEqualTo("doc-1");
        assertThat(grpcRequest.getDocument().getFieldsList())
                .extracting(Field::getName, Field::getValue)
                .containsExactlyInAnyOrder(tuple("title", "Distributed Search"), tuple("category", "docs"));
    }

    @Test
    void deleteMapsPathAndPartitionToGrpcRequestWithoutNetworkCalls() {
        when(indexNodeClientManager.nextClient("tenant-a", false)).thenReturn(indexStub);
        when(indexStub.deleteDocument(any(DeleteDocumentRequest.class)))
                .thenReturn(DeleteDocumentResponse.newBuilder().setSuccess(true).build());

        IndexResponseDto response = service.delete("doc-9", "tenant-a");

        assertThat(response.getId()).isEqualTo("doc-9");
        assertThat(response.isSuccess()).isTrue();

        ArgumentCaptor<DeleteDocumentRequest> requestCaptor = ArgumentCaptor.forClass(DeleteDocumentRequest.class);
        verify(indexStub).deleteDocument(requestCaptor.capture());
        DeleteDocumentRequest grpcRequest = requestCaptor.getValue();
        assertThat(grpcRequest.getPartitionId()).isEqualTo("tenant-a");
        assertThat(grpcRequest.getId()).isEqualTo("doc-9");
    }
}
