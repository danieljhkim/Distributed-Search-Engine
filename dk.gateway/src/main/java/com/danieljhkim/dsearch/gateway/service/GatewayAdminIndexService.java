package com.danieljhkim.dsearch.gateway.service;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.grpc.NodeClient;
import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.common.validation.RequestLimitsValidator;
import com.danieljhkim.dsearch.gateway.api.dto.AdminAuditResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.AliasSwapRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.CreateIndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.InspectSchemaResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.ReindexRequestDto;
import com.danieljhkim.dsearch.proto.common.SearchType;
import com.danieljhkim.dsearch.proto.index.CreateIndexRequest;
import com.danieljhkim.dsearch.proto.index.CreateIndexResponse;
import com.danieljhkim.dsearch.proto.index.IndexSchemaField;
import com.danieljhkim.dsearch.proto.index.IndexServiceGrpc;
import com.danieljhkim.dsearch.proto.index.InspectSchemaRequest;
import com.danieljhkim.dsearch.proto.index.InspectSchemaResponse;
import com.danieljhkim.dsearch.proto.index.ReindexRequest;
import com.danieljhkim.dsearch.proto.index.ReindexResponse;
import com.danieljhkim.dsearch.proto.index.RepresentativeQuery;
import com.danieljhkim.dsearch.proto.index.RollbackAliasRequest;
import com.danieljhkim.dsearch.proto.index.RollbackAliasResponse;
import com.danieljhkim.dsearch.proto.index.SwapAliasRequest;
import com.danieljhkim.dsearch.proto.index.SwapAliasResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GatewayAdminIndexService {

    private final NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager;
    private final AppConfig.RequestLimitsConfig requestLimits;
    private final Path auditLog;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public GatewayAdminIndexService(
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager,
            AppConfig appConfig,
            @Value("${dsearch.admin.audit-log:data/admin-audit.jsonl}") String auditLogPath) {
        this(indexNodeClientManager, appConfig, Path.of(auditLogPath));
    }

    GatewayAdminIndexService(
            NodeClientManager<IndexServiceGrpc.IndexServiceBlockingStub> indexNodeClientManager,
            AppConfig appConfig,
            Path auditLog) {
        this.indexNodeClientManager = indexNodeClientManager;
        this.requestLimits = RequestLimitsValidator.limitsOrDefaults(appConfig.getRequestLimits());
        this.auditLog = auditLog;
    }

    public AdminAuditResponseDto createIndex(CreateIndexRequestDto request, String actor) {
        String alias = request.getAlias() == null || request.getAlias().isBlank()
                ? request.getIndexName()
                : request.getAlias();
        CreateIndexResponse last = fanout(stub -> stub.createIndex(CreateIndexRequest.newBuilder()
                .setIndexName(request.getIndexName())
                .setAlias(alias)
                .build()));
        return audit(
                "create-index",
                actor,
                true,
                alias,
                last.getIndexName(),
                null,
                "created index " + last.getIndexName(),
                Map.of("nodes", indexNodeClientManager.getClientMap().size()));
    }

    public InspectSchemaResponseDto inspectSchema(String indexOrAlias, String actor) {
        InspectSchemaResponse response = first(stub -> stub.inspectSchema(
                InspectSchemaRequest.newBuilder().setIndexOrAlias(indexOrAlias).build()));
        InspectSchemaResponseDto dto = new InspectSchemaResponseDto();
        dto.setAuditId(UUID.randomUUID().toString());
        dto.setIndexName(response.getIndexName());
        dto.setAlias(response.getAlias());
        dto.setCompatibilityVersion(response.getSchema().getCompatibilityVersion());
        dto.setAnalyzer(response.getSchema().getAnalyzer().getName());
        List<InspectSchemaResponseDto.FieldSchemaDto> fields = new ArrayList<>();
        for (IndexSchemaField field : response.getSchema().getFieldsList()) {
            InspectSchemaResponseDto.FieldSchemaDto fieldDto = new InspectSchemaResponseDto.FieldSchemaDto();
            fieldDto.setName(field.getName());
            fieldDto.setType(field.getType());
            fieldDto.setFilterable(field.getFilterable());
            fieldDto.setSortable(field.getSortable());
            fieldDto.setFacetable(field.getFacetable());
            fieldDto.setHighlightable(field.getHighlightable());
            fieldDto.setAnalyzer(field.getAnalyzer());
            fields.add(fieldDto);
        }
        dto.setFields(fields);
        InspectSchemaResponseDto.EmbeddingIdentityDto embedding = new InspectSchemaResponseDto.EmbeddingIdentityDto();
        embedding.setModelId(response.getSchema().getEmbedding().getModelId());
        embedding.setEngine(response.getSchema().getEmbedding().getEngine());
        embedding.setDigest(response.getSchema().getEmbedding().getDigest());
        embedding.setDimension(response.getSchema().getEmbedding().getDimension());
        dto.setEmbedding(embedding);
        AdminAuditResponseDto audit = audit(
                "inspect-schema",
                actor,
                true,
                response.getAlias(),
                response.getIndexName(),
                null,
                "inspected schema",
                Map.of("compatibilityVersion", dto.getCompatibilityVersion()));
        dto.setAuditId(audit.getAuditId());
        return dto;
    }

    public AdminAuditResponseDto reindex(String sourceAlias, ReindexRequestDto request, String actor) {
        ReindexRequest.Builder builder = ReindexRequest.newBuilder().setSourceAlias(sourceAlias);
        if (request.getTargetIndex() != null && !request.getTargetIndex().isBlank()) {
            builder.setTargetIndex(request.getTargetIndex());
        }
        if (request.getVerificationQueries() != null) {
            for (ReindexRequestDto.RepresentativeQueryDto query : request.getVerificationQueries()) {
                SearchType searchType;
                try {
                    searchType = SearchType.valueOf(query.getSearchType());
                } catch (IllegalArgumentException e) {
                    searchType = SearchType.BM25;
                }
                builder.addVerificationQueries(RepresentativeQuery.newBuilder()
                        .setQuery(query.getQuery())
                        .setSearchType(searchType)
                        .setSize(query.getSize())
                        .build());
            }
        }
        ReindexResponse last = fanout(stub -> stub.reindex(builder.build()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceCount", last.getSourceCount());
        details.put("targetCount", last.getTargetCount());
        details.put("verificationPassed", last.getVerificationPassed());
        return audit(
                "reindex",
                actor,
                last.getSuccess(),
                sourceAlias,
                last.getTargetIndex(),
                last.getSourceIndex(),
                last.getError().isBlank()
                        ? "reindex verified; source remains active until alias swap"
                        : last.getError(),
                details);
    }

    public AdminAuditResponseDto swapAlias(AliasSwapRequestDto request, String actor) {
        SwapAliasResponse last = fanout(stub -> stub.swapAlias(SwapAliasRequest.newBuilder()
                .setAlias(request.getAlias())
                .setTargetIndex(request.getTargetIndex())
                .build()));
        return audit(
                "alias-swap",
                actor,
                last.getSuccess(),
                last.getAlias(),
                last.getCurrentIndex(),
                last.getPreviousIndex(),
                "alias now points to " + last.getCurrentIndex(),
                Map.of());
    }

    public AdminAuditResponseDto rollbackAlias(String alias, String actor) {
        RollbackAliasResponse last = fanout(stub -> stub.rollbackAlias(
                RollbackAliasRequest.newBuilder().setAlias(alias).build()));
        return audit(
                "alias-rollback",
                actor,
                last.getSuccess(),
                last.getAlias(),
                last.getCurrentIndex(),
                last.getPreviousIndex(),
                "alias restored to " + last.getCurrentIndex(),
                Map.of());
    }

    private <T> T fanout(Function<IndexServiceGrpc.IndexServiceBlockingStub, T> call) {
        T last = null;
        for (NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> client :
                indexNodeClientManager.getClientMap().values()) {
            last = call.apply(withDeadline(client.getStub()));
        }
        if (last == null) {
            throw new IllegalStateException("No index nodes available for admin operation");
        }
        return last;
    }

    private <T> T first(Function<IndexServiceGrpc.IndexServiceBlockingStub, T> call) {
        StatusRuntimeException lastError = null;
        for (NodeClient<IndexServiceGrpc.IndexServiceBlockingStub> client :
                indexNodeClientManager.getClientMap().values()) {
            try {
                return call.apply(withDeadline(client.getStub()));
            } catch (StatusRuntimeException e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("No index nodes available for admin operation");
    }

    private IndexServiceGrpc.IndexServiceBlockingStub withDeadline(IndexServiceGrpc.IndexServiceBlockingStub stub) {
        return stub.withDeadlineAfter(Math.max(1, requestLimits.getRequestTimeoutMillis()), TimeUnit.MILLISECONDS);
    }

    private AdminAuditResponseDto audit(
            String operation,
            String actor,
            boolean success,
            String alias,
            String indexName,
            String previousIndexName,
            String message,
            Map<String, Object> details) {
        AdminAuditResponseDto response = new AdminAuditResponseDto(
                UUID.randomUUID().toString(),
                Instant.now(),
                operation,
                actor == null || actor.isBlank() ? "admin" : actor,
                success,
                alias,
                indexName,
                previousIndexName,
                message,
                details);
        persist(response);
        return response;
    }

    private void persist(AdminAuditResponseDto response) {
        try {
            if (auditLog.getParent() != null) {
                Files.createDirectories(auditLog.getParent());
            }
            Files.writeString(
                    auditLog,
                    objectMapper.writeValueAsString(response) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist admin audit record", e);
        }
    }
}
