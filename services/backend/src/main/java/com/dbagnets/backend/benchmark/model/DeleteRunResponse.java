package com.dbagnets.backend.benchmark.model;

import com.dbagnets.backend.benchmark.execution.BenchmarkRun;

import java.time.Instant;
import java.util.List;

public record DeleteRunResponse(
        String id,
        String benchmarkId,
        String entityName,
        Integer sampleSize,
        Boolean includeChildren,
        String status,
        Instant createdAt,
        Instant finishedAt,
        List<DeleteResultResponse> results
) {
    public static DeleteRunResponse from(BenchmarkRun run, Integer sampleSize, Boolean includeChildren) {
        List<DeleteResultResponse> resultDtos = run.getResults().stream()
                .map(DeleteResultResponse::from)
                .toList();
        return new DeleteRunResponse(
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
