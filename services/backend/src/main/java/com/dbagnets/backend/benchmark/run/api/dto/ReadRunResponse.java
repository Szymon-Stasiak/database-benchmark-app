package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;

import java.time.Instant;
import java.util.List;

public record ReadRunResponse(
        String id,
        String benchmarkId,
        String entityName,
        Integer sampleSize,
        Boolean includeChildren,
        String status,
        Instant createdAt,
        Instant finishedAt,
        List<ReadResultResponse> results
) {
    public static ReadRunResponse from(BenchmarkRun run, Integer sampleSize, Boolean includeChildren) {
        List<ReadResultResponse> resultDtos = run.getResults().stream()
                .map(ReadResultResponse::from)
                .toList();
        return new ReadRunResponse(
                run.getId(),
                run.getBenchmarkId(),
                run.getEntityName(),
                sampleSize,
                includeChildren,
                run.getStatus().name(),
                run.getCreatedAt(),
                run.getFinishedAt(),
                resultDtos);
    }
}