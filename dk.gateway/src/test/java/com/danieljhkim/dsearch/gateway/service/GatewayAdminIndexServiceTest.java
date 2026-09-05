package com.danieljhkim.dsearch.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.gateway.api.dto.AliasSwapRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.AnalyzeRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.CreateIndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.ReindexRequestDto;
import com.danieljhkim.dsearch.proto.index.AnalyzeIndexRequest;
import com.danieljhkim.dsearch.proto.index.AnalyzeIndexResponse;
import com.danieljhkim.dsearch.proto.index.AnalyzeToken;
import com.danieljhkim.dsearch.proto.index.CreateIndexRequest;
import com.danieljhkim.dsearch.proto.index.CreateIndexResponse;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.proto.index.ReindexRequest;
import com.danieljhkim.dsearch.proto.index.ReindexResponse;
import com.danieljhkim.dsearch.proto.index.RollbackAliasRequest;
import com.danieljhkim.dsearch.proto.index.RollbackAliasResponse;
import com.danieljhkim.dsearch.proto.index.SwapAliasRequest;
import com.danieljhkim.dsearch.proto.index.SwapAliasResponse;
import io.grpc.ManagedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GatewayAdminIndexServiceTest {

    @Mock
    private NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager;

    @Mock
    private IndexServiceGrpc.IndexServiceBlockingStub indexStub;

    @TempDir
    Path tempDir;

    private GatewayAdminIndexService service;

    @BeforeEach
    void setUp() {
        NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> client =
                new NodeClient<>("0", indexStub, mock(ManagedChannel.class), "localhost", 5100);
        when(indexNodeClientManager.getClientMap()).thenReturn(Map.of("0", client));
        when(indexStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(indexStub);
        service = new GatewayAdminIndexService(indexNodeClientManager, new AppConfig(), tempDir.resolve("audit.jsonl"));
    }

    @Test
    void createReindexSwapAndRollbackWriteAuditableResults() throws Exception {
        when(indexStub.createIndex(any(CreateIndexRequest.class)))
                .thenReturn(CreateIndexResponse.newBuilder()
                        .setSuccess(true)
                        .setIndexName("movies_1")
                        .setAlias("movies")
                        .setAuditId("n1")
                        .build());
        when(indexStub.reindex(any(ReindexRequest.class)))
                .thenReturn(ReindexResponse.newBuilder()
                        .setSuccess(true)
                        .setSourceIndex("movies_1")
                        .setTargetIndex("movies_2")
                        .setSourceCount(3)
                        .setTargetCount(3)
                        .setVerificationPassed(true)
                        .setAuditId("n2")
                        .build());
        when(indexStub.swapAlias(any(SwapAliasRequest.class)))
                .thenReturn(SwapAliasResponse.newBuilder()
                        .setSuccess(true)
                        .setAlias("movies")
                        .setPreviousIndex("movies_1")
                        .setCurrentIndex("movies_2")
                        .setAuditId("n3")
                        .build());
        when(indexStub.rollbackAlias(any(RollbackAliasRequest.class)))
                .thenReturn(RollbackAliasResponse.newBuilder()
                        .setSuccess(true)
                        .setAlias("movies")
                        .setCurrentIndex("movies_1")
                        .setPreviousIndex("movies_2")
                        .setAuditId("n4")
                        .build());

        CreateIndexRequestDto create = new CreateIndexRequestDto();
        create.setIndexName("movies_1");
        create.setAlias("movies");
        assertThat(service.createIndex(create, "admin").isSuccess()).isTrue();

        ReindexRequestDto reindex = new ReindexRequestDto();
        reindex.setTargetIndex("movies_2");
        assertThat(service.reindex("movies", reindex, "admin").getIndexName()).isEqualTo("movies_2");

        AliasSwapRequestDto swap = new AliasSwapRequestDto();
        swap.setAlias("movies");
        swap.setTargetIndex("movies_2");
        assertThat(service.swapAlias(swap, "admin").getPreviousIndexName()).isEqualTo("movies_1");
        assertThat(service.rollbackAlias("movies", "admin").getIndexName()).isEqualTo("movies_1");

        String audit = Files.readString(tempDir.resolve("audit.jsonl"));
        assertThat(audit).contains("create-index").contains("alias-swap").contains("alias-rollback");
    }

    @Test
    void analyzeTextReturnsTokensAndOmitsSampleTextFromTheAuditLog() throws Exception {
        when(indexStub.analyzeIndex(any(AnalyzeIndexRequest.class)))
                .thenReturn(AnalyzeIndexResponse.newBuilder()
                        .setIndexName("movies_1")
                        .setAlias("movies")
                        .setAnalyzer("standard")
                        .addTokens(AnalyzeToken.newBuilder()
                                .setToken("secret")
                                .setPosition(0)
                                .setStartOffset(0)
                                .setEndOffset(6)
                                .build())
                        .setTruncated(false)
                        .build());

        AnalyzeRequestDto request = new AnalyzeRequestDto();
        request.setText("classified sample text that must never be logged");

        var response = service.analyzeText("movies", request, "admin");
        assertThat(response.getAnalyzer()).isEqualTo("standard");
        assertThat(response.getTokens()).hasSize(1);
        assertThat(response.getTokens().get(0).getToken()).isEqualTo("secret");
        assertThat(response.isTruncated()).isFalse();

        String audit = Files.readString(tempDir.resolve("audit.jsonl"));
        assertThat(audit).contains("\"operation\":\"analyze\"");
        assertThat(audit).doesNotContain("classified sample text");
    }
}
