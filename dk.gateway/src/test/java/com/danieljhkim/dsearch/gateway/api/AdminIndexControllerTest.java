package com.danieljhkim.dsearch.gateway.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.danieljhkim.dsearch.gateway.api.dto.AdminAuditResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.InspectSchemaResponseDto;
import com.danieljhkim.dsearch.gateway.config.AdminAuthFilter;
import com.danieljhkim.dsearch.gateway.service.GatewayAdminIndexService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminIndexController.class)
@Import(GlobalExceptionHandler.class)
class AdminIndexControllerTest {

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockBean
    private GatewayAdminIndexService adminIndexService;

    @Test
    void createInspectReindexSwapAndRollbackReturnAuditableJson() throws Exception {
        when(adminIndexService.createIndex(any(), any()))
                .thenReturn(audit("create-index", "movies", "movies_1", null));
        when(adminIndexService.inspectSchema(eq("movies"), any())).thenReturn(schema());
        when(adminIndexService.reindex(eq("movies"), any(), any()))
                .thenReturn(audit("reindex", "movies", "movies_2", "movies_1"));
        when(adminIndexService.swapAlias(any(), any()))
                .thenReturn(audit("alias-swap", "movies", "movies_2", "movies_1"));
        when(adminIndexService.rollbackAlias(eq("movies"), any()))
                .thenReturn(audit("alias-rollback", "movies", "movies_1", "movies_2"));

        mockMvc.perform(post("/api/v1/admin/indexes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indexName\":\"movies_1\",\"alias\":\"movies\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditId").exists())
                .andExpect(jsonPath("$.operation").value("create-index"))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/admin/indexes/movies/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatibilityVersion").value(1))
                .andExpect(jsonPath("$.embedding.dimension").value(384));

        mockMvc.perform(post("/api/v1/admin/indexes/movies/reindex")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetIndex\":\"movies_2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("reindex"));

        mockMvc.perform(post("/api/v1/admin/aliases/swap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"movies\",\"targetIndex\":\"movies_2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("alias-swap"));

        mockMvc.perform(post("/api/v1/admin/aliases/movies/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("alias-rollback"));

        verify(adminIndexService).createIndex(any(), any());
    }

    private static AdminAuditResponseDto audit(String operation, String alias, String index, String previous) {
        return new AdminAuditResponseDto(
                "audit-1", Instant.parse("2026-08-31T00:00:00Z"), operation, AdminAuthFilter.ADMIN_ACTOR, true, alias, index, previous, "ok", Map.of());
    }

    private static InspectSchemaResponseDto schema() {
        InspectSchemaResponseDto dto = new InspectSchemaResponseDto();
        dto.setAuditId("audit-schema");
        dto.setIndexName("movies_1");
        dto.setAlias("movies");
        dto.setCompatibilityVersion(1);
        dto.setAnalyzer("standard");
        dto.setFields(List.of());
        InspectSchemaResponseDto.EmbeddingIdentityDto embedding = new InspectSchemaResponseDto.EmbeddingIdentityDto();
        embedding.setModelId("djl://model");
        embedding.setEngine("PyTorch");
        embedding.setDigest("sha256:abc");
        embedding.setDimension(384);
        dto.setEmbedding(embedding);
        return dto;
    }
}
