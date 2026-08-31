package com.danieljhkim.dsearch.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.exception.NodeUnavailableException;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.gateway.api.dto.BulkIndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.BulkIndexResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexResponseDto;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.BulkIndexDocumentResult;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentRequest;
import com.danieljhkim.dsearch.proto.index.DeleteDocumentResponse;
import com.danieljhkim.dsearch.proto.index.Field;
import com.danieljhkim.dsearch.proto.index.IndexDocumentRequest;
import com.danieljhkim.dsearch.proto.index.IndexDocumentResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    private NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> ownerClient;
    private GatewayIndexService service;

    @BeforeEach
    void setUp() {
        ownerClient = new NodeClient<>("1", indexStub, mock(ManagedChannel.class), "localhost", 5101);
        service = new GatewayIndexService(indexNodeClientManager);
        lenient()
                .when(indexStub.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(indexStub);
    }

    @Test
    void indexRoutesToTheOwnerOfTheDocumentKey() {
        IndexRequestDto request = new IndexRequestDto();
        request.setId("doc-1");
        request.setPartitionId("tenant-a");
        request.setFields(Map.of("title", "Distributed Search", "category", "docs"));
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-1")).thenReturn(ownerClient);
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
        assertThat(ownerClient.getShardDocCount("tenant-a")).isEqualTo(1);
    }

    @Test
    void indexUsesTheDefaultPartitionWhenNoneIsSupplied() {
        IndexRequestDto request = new IndexRequestDto();
        request.setId("doc-1");
        when(indexNodeClientManager.ownerClient("default", "doc-1")).thenReturn(ownerClient);
        when(indexStub.indexDocument(any(IndexDocumentRequest.class)))
                .thenReturn(IndexDocumentResponse.newBuilder()
                        .setId("doc-1")
                        .setSuccess(true)
                        .build());

        service.index(request);

        ArgumentCaptor<IndexDocumentRequest> requestCaptor = ArgumentCaptor.forClass(IndexDocumentRequest.class);
        verify(indexStub).indexDocument(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getPartitionId()).isEqualTo("default");
    }

    @Test
    void indexMintsTheDocumentIdSoOwnershipIsKnownBeforeTheCall() {
        IndexRequestDto request = new IndexRequestDto();
        request.setPartitionId("tenant-a");
        request.setFields(Map.of("title", "Generated"));
        ArgumentCaptor<String> documentIdCaptor = ArgumentCaptor.forClass(String.class);
        when(indexNodeClientManager.ownerClient(any(), documentIdCaptor.capture()))
                .thenReturn(ownerClient);
        when(indexStub.indexDocument(any(IndexDocumentRequest.class))).thenAnswer(invocation -> {
            IndexDocumentRequest req = invocation.getArgument(0);
            return IndexDocumentResponse.newBuilder()
                    .setId(req.getDocument().getId())
                    .setSuccess(true)
                    .build();
        });

        IndexResponseDto response = service.index(request);

        ArgumentCaptor<IndexDocumentRequest> requestCaptor = ArgumentCaptor.forClass(IndexDocumentRequest.class);
        verify(indexStub).indexDocument(requestCaptor.capture());
        String routedId = documentIdCaptor.getValue();
        assertThat(routedId).isNotBlank();
        // The id used for routing must be the id the node stores, otherwise a later update
        // of the same document could be routed to a different node.
        assertThat(requestCaptor.getValue().getDocument().getId()).isEqualTo(routedId);
        assertThat(response.getId()).isEqualTo(routedId);
    }

    @Test
    void indexDoesNotCountDocumentsTheOwnerRejected() {
        IndexRequestDto request = new IndexRequestDto();
        request.setId("doc-1");
        request.setPartitionId("tenant-a");
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-1")).thenReturn(ownerClient);
        when(indexStub.indexDocument(any(IndexDocumentRequest.class)))
                .thenReturn(IndexDocumentResponse.newBuilder()
                        .setId("doc-1")
                        .setSuccess(false)
                        .build());

        IndexResponseDto response = service.index(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(ownerClient.getShardDocCount("tenant-a")).isZero();
    }

    @Test
    void indexPropagatesOwnerUnavailabilityInsteadOfWritingElsewhere() {
        IndexRequestDto request = new IndexRequestDto();
        request.setId("doc-1");
        request.setPartitionId("tenant-a");
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-1"))
                .thenThrow(new NodeUnavailableException("1", "owner down"));

        assertThatThrownBy(() -> service.index(request)).isInstanceOf(NodeUnavailableException.class);

        verify(indexStub, never()).indexDocument(any(IndexDocumentRequest.class));
    }

    @Test
    void indexRejectsNullFieldValuesBeforeOwnerLookup() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", null);
        IndexRequestDto request = indexRequest("doc-1", fields);

        assertThatThrownBy(() -> service.index(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("field value must not be null");

        verify(indexNodeClientManager, never()).ownerClient(any(), any());
        verify(indexStub, never()).indexDocument(any(IndexDocumentRequest.class));
    }

    @Test
    void deleteRoutesToTheOwnerAndDecrementsOnce() {
        ownerClient.incrementDocToShard("tenant-a");
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-9")).thenReturn(ownerClient);
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
        assertThat(ownerClient.getShardDocCount("tenant-a")).isZero();
    }

    @Test
    void deleteDoesNotDecrementWhenTheOwnerReportsFailure() {
        ownerClient.incrementDocToShard("tenant-a");
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-9")).thenReturn(ownerClient);
        when(indexStub.deleteDocument(any(DeleteDocumentRequest.class)))
                .thenReturn(
                        DeleteDocumentResponse.newBuilder().setSuccess(false).build());

        IndexResponseDto response = service.delete("doc-9", "tenant-a");

        assertThat(response.isSuccess()).isFalse();
        assertThat(ownerClient.getShardDocCount("tenant-a")).isEqualTo(1);
    }

    @Test
    void deleteDoesNotDecrementWhenTheCallFails() {
        ownerClient.incrementDocToShard("tenant-a");
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-9")).thenReturn(ownerClient);
        when(indexStub.deleteDocument(any(DeleteDocumentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> service.delete("doc-9", "tenant-a")).isInstanceOf(StatusRuntimeException.class);

        assertThat(ownerClient.getShardDocCount("tenant-a")).isEqualTo(1);
    }

    @Test
    void deletePropagatesOwnerUnavailability() {
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-9"))
                .thenThrow(new NodeUnavailableException("1", "owner down"));

        assertThatThrownBy(() -> service.delete("doc-9", "tenant-a"))
                .isInstanceOf(NodeUnavailableException.class)
                .hasMessageContaining("owner down");

        verify(indexStub, never()).deleteDocument(any(DeleteDocumentRequest.class));
    }

    @Test
    void deleteRejectsBlankIds() {
        assertThatThrownBy(() -> service.delete("  ", "tenant-a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bulkUsesTheGrpcBulkContractAndRestoresInputOrder() {
        IndexRequestDto first = indexRequest("doc-1", Map.of("title", "first"));
        IndexRequestDto second = indexRequest("doc-2", Map.of("title", "second"));
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-1")).thenReturn(ownerClient);
        when(indexNodeClientManager.ownerClient("tenant-a", "doc-2")).thenReturn(ownerClient);
        when(indexStub.bulkIndexDocument(any(BulkIndexDocumentRequest.class)))
                .thenReturn(BulkIndexDocumentResponse.newBuilder()
                        .setSuccess(true)
                        .addResults(BulkIndexDocumentResult.newBuilder()
                                .setRequestIndex(1)
                                .setId("doc-2")
                                .setSuccess(true))
                        .addResults(BulkIndexDocumentResult.newBuilder()
                                .setRequestIndex(0)
                                .setId("doc-1")
                                .setSuccess(true))
                        .build());

        BulkIndexResponseDto response = service.bulkIndex(bulkRequest("tenant-a", first, second));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getItems()).extracting(item -> item.getId()).containsExactly("doc-1", "doc-2");
        assertThat(response.getItems()).extracting(item -> item.getStatus()).containsOnly("success");
        ArgumentCaptor<BulkIndexDocumentRequest> requestCaptor =
                ArgumentCaptor.forClass(BulkIndexDocumentRequest.class);
        verify(indexStub).bulkIndexDocument(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getPartitionId()).isEqualTo("tenant-a");
        assertThat(requestCaptor.getValue().getDocumentsList())
                .extracting(document -> document.getId())
                .containsExactly("doc-1", "doc-2");
    }

    @Test
    void bulkKeepsSuccessfulWritesWhenOtherItemsFailValidation() {
        IndexRequestDto missingId = indexRequest(null, Map.of("title", "invalid"));
        IndexRequestDto valid = indexRequest("doc-2", Map.of("title", "valid"));
        when(indexNodeClientManager.ownerClient("default", "doc-2")).thenReturn(ownerClient);
        when(indexStub.bulkIndexDocument(any(BulkIndexDocumentRequest.class)))
                .thenReturn(successfulBulkResult(0, "doc-2"));

        BulkIndexResponseDto response = service.bulkIndex(bulkRequest(null, missingId, valid));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getItems())
                .extracting(item -> item.getStatus())
                .containsExactly("validation_failure", "success");
        verify(indexStub).bulkIndexDocument(any(BulkIndexDocumentRequest.class));
    }

    @Test
    void bulkReportsNullFieldValuesWithoutPreventingValidItems() {
        Map<String, String> invalidFields = new LinkedHashMap<>();
        invalidFields.put("title", null);
        IndexRequestDto invalid = indexRequest("doc-1", invalidFields);
        IndexRequestDto valid = indexRequest("doc-2", Map.of("title", "valid"));
        when(indexNodeClientManager.ownerClient("default", "doc-2")).thenReturn(ownerClient);
        when(indexStub.bulkIndexDocument(any(BulkIndexDocumentRequest.class)))
                .thenReturn(successfulBulkResult(0, "doc-2"));

        BulkIndexResponseDto response = service.bulkIndex(bulkRequest(null, invalid, valid));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getItems())
                .extracting(item -> item.getStatus())
                .containsExactly("validation_failure", "success");
        assertThat(response.getItems().getFirst().getError()).isEqualTo("field value must not be null");
        verify(indexNodeClientManager, never()).ownerClient("default", "doc-1");
        ArgumentCaptor<BulkIndexDocumentRequest> requestCaptor =
                ArgumentCaptor.forClass(BulkIndexDocumentRequest.class);
        verify(indexStub).bulkIndexDocument(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getDocumentsList())
                .extracting(document -> document.getId())
                .containsExactly("doc-2");
    }

    @Test
    void bulkRejectsDuplicateIdsWithoutApplyingTheSecondItem() {
        IndexRequestDto first = indexRequest("doc-1", Map.of("title", "first"));
        IndexRequestDto duplicate = indexRequest("doc-1", Map.of("title", "second"));
        when(indexNodeClientManager.ownerClient("default", "doc-1")).thenReturn(ownerClient);
        when(indexStub.bulkIndexDocument(any(BulkIndexDocumentRequest.class)))
                .thenReturn(successfulBulkResult(0, "doc-1"));

        BulkIndexResponseDto response = service.bulkIndex(bulkRequest(null, first, duplicate));

        assertThat(response.getItems())
                .extracting(item -> item.getStatus())
                .containsExactly("success", "validation_failure");
        ArgumentCaptor<BulkIndexDocumentRequest> requestCaptor =
                ArgumentCaptor.forClass(BulkIndexDocumentRequest.class);
        verify(indexStub).bulkIndexDocument(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getDocumentsCount()).isOne();
    }

    @Test
    void bulkTimeoutIsRetryableWithTheSameId() {
        IndexRequestDto item = indexRequest("doc-1", Map.of("title", "retry"));
        when(indexNodeClientManager.ownerClient("default", "doc-1")).thenReturn(ownerClient);
        when(indexStub.bulkIndexDocument(any(BulkIndexDocumentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED))
                .thenReturn(successfulBulkResult(0, "doc-1"));

        BulkIndexResponseDto timedOut = service.bulkIndex(bulkRequest(null, item));
        BulkIndexResponseDto retried = service.bulkIndex(bulkRequest(null, item));

        assertThat(timedOut.getItems().getFirst().getStatus()).isEqualTo("retryable_failure");
        assertThat(retried.getItems().getFirst().getStatus()).isEqualTo("success");
        ArgumentCaptor<BulkIndexDocumentRequest> requestCaptor =
                ArgumentCaptor.forClass(BulkIndexDocumentRequest.class);
        verify(indexStub, org.mockito.Mockito.times(2)).bulkIndexDocument(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .allSatisfy(
                        request -> assertThat(request.getDocuments(0).getId()).isEqualTo("doc-1"));
    }

    @Test
    void bulkReportsPermanentOwnerValidationFailuresPerItem() {
        IndexRequestDto item = indexRequest("doc-1", Map.of("title", "invalid downstream"));
        when(indexNodeClientManager.ownerClient("default", "doc-1")).thenReturn(ownerClient);
        when(indexStub.bulkIndexDocument(any(BulkIndexDocumentRequest.class)))
                .thenThrow(
                        new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("owner validation failed")));

        BulkIndexResponseDto response = service.bulkIndex(bulkRequest(null, item));

        assertThat(response.getItems().getFirst().getStatus()).isEqualTo("permanent_failure");
        assertThat(response.getItems().getFirst().getError()).isEqualTo("owner validation failed");
    }

    @Test
    void bulkRejectsItemAndEmbeddingWorkLimitsBeforeCallingOwners() {
        AppConfig config = new AppConfig();
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxBulkItems(1);
        limits.setMaxBulkEmbeddingBytes(2);
        config.setRequestLimits(limits);
        service = new GatewayIndexService(indexNodeClientManager, config);

        assertThatThrownBy(() -> service.bulkIndex(
                        bulkRequest(null, indexRequest("doc-1", Map.of()), indexRequest("doc-2", Map.of()))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Bulk item count");
        verify(indexNodeClientManager, never()).ownerClient(any(), any());

        limits.setMaxBulkItems(2);
        assertThatThrownBy(
                        () -> service.bulkIndex(bulkRequest(null, indexRequest("doc-1", Map.of("title", "too long")))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Bulk embedding bytes");
        verify(indexNodeClientManager, never()).ownerClient(any(), any());
    }

    private static IndexRequestDto indexRequest(String id, Map<String, String> fields) {
        IndexRequestDto request = new IndexRequestDto();
        request.setId(id);
        request.setFields(fields);
        return request;
    }

    private static BulkIndexRequestDto bulkRequest(String partitionId, IndexRequestDto... items) {
        BulkIndexRequestDto request = new BulkIndexRequestDto();
        request.setPartitionId(partitionId);
        request.setItems(List.of(items));
        return request;
    }

    private static BulkIndexDocumentResponse successfulBulkResult(int requestIndex, String id) {
        return BulkIndexDocumentResponse.newBuilder()
                .setSuccess(true)
                .addResults(BulkIndexDocumentResult.newBuilder()
                        .setRequestIndex(requestIndex)
                        .setId(id)
                        .setSuccess(true))
                .build();
    }
}
