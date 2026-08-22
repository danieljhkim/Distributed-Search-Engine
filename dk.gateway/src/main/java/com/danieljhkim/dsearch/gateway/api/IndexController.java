package com.danieljhkim.dsearch.gateway.api;

import com.danieljhkim.dsearch.common.validation.PartitionIdValidator;
import com.danieljhkim.dsearch.gateway.api.dto.IndexRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.IndexResponseDto;
import com.danieljhkim.dsearch.gateway.service.GatewayIndexService;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/index")
public class IndexController {

    private final GatewayIndexService indexService;
    private final MeterRegistry meterRegistry;

    public IndexController(GatewayIndexService indexService, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.indexService = indexService;
    }

    @Timed(
            value = "dsearch.index.http",
            extraTags = {"endpoint", "/api/v1/index"})
    @PostMapping(consumes = "application/json", produces = "application/json")
    public IndexResponseDto indexDocument(@Valid @RequestBody IndexRequestDto req) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return indexService.index(req);
        } finally {
            sample.stop(Timer.builder("dsearch.gateway.index.latency")
                    .tag("partitionId", req.getPartitionId() != null ? req.getPartitionId() : "UNKNOWN")
                    .register(meterRegistry));
        }
    }

    @Timed(
            value = "dsearch.delete.http",
            extraTags = {"endpoint", "/api/v1/index/{id}"})
    @DeleteMapping(value = "/{id}", produces = "application/json")
    public IndexResponseDto deleteDocument(
            @PathVariable("id") String id,
            @RequestParam(name = "partitionId", defaultValue = "default") String partitionId) {
        PartitionIdValidator.validate(partitionId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return indexService.delete(id, partitionId);
        } finally {
            sample.stop(Timer.builder("dsearch.gateway.delete.latency")
                    .tag("partitionId", partitionId != null ? partitionId : "UNKNOWN")
                    .register(meterRegistry));
        }
    }
}
