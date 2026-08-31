package com.danieljhkim.dsearch.gateway.api;

import com.danieljhkim.dsearch.common.validation.PartitionIdValidator;
import com.danieljhkim.dsearch.gateway.api.dto.AdminAuditResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.AliasSwapRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.CreateIndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.InspectSchemaResponseDto;
import com.danieljhkim.dsearch.gateway.api.dto.ReindexRequestDto;
import com.danieljhkim.dsearch.gateway.config.AdminAuthFilter;
import com.danieljhkim.dsearch.gateway.service.GatewayAdminIndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminIndexController {

    private final GatewayAdminIndexService adminIndexService;

    public AdminIndexController(GatewayAdminIndexService adminIndexService) {
        this.adminIndexService = adminIndexService;
    }

    @PostMapping(value = "/indexes", consumes = "application/json", produces = "application/json")
    public AdminAuditResponseDto createIndex(
            @Valid @RequestBody CreateIndexRequestDto request, HttpServletRequest httpRequest) {
        return adminIndexService.createIndex(request, actor(httpRequest));
    }

    @GetMapping(value = "/indexes/{name}/schema", produces = "application/json")
    public InspectSchemaResponseDto inspectSchema(@PathVariable("name") String name, HttpServletRequest httpRequest) {
        PartitionIdValidator.validate(name);
        return adminIndexService.inspectSchema(name, actor(httpRequest));
    }

    @PostMapping(value = "/indexes/{name}/reindex", consumes = "application/json", produces = "application/json")
    public AdminAuditResponseDto reindex(
            @PathVariable("name") String name,
            @Valid @RequestBody(required = false) ReindexRequestDto request,
            HttpServletRequest httpRequest) {
        PartitionIdValidator.validate(name);
        ReindexRequestDto body = request == null ? new ReindexRequestDto() : request;
        return adminIndexService.reindex(name, body, actor(httpRequest));
    }

    @PostMapping(value = "/aliases/swap", consumes = "application/json", produces = "application/json")
    public AdminAuditResponseDto swapAlias(
            @Valid @RequestBody AliasSwapRequestDto request, HttpServletRequest httpRequest) {
        return adminIndexService.swapAlias(request, actor(httpRequest));
    }

    @PostMapping(value = "/aliases/{alias}/rollback", produces = "application/json")
    public AdminAuditResponseDto rollbackAlias(@PathVariable("alias") String alias, HttpServletRequest httpRequest) {
        PartitionIdValidator.validate(alias);
        return adminIndexService.rollbackAlias(alias, actor(httpRequest));
    }

    private static String actor(HttpServletRequest request) {
        Object actor = request.getAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE);
        return actor instanceof String value && !value.isBlank() ? value : AdminAuthFilter.ADMIN_ACTOR;
    }
}
