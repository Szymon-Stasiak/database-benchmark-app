package com.dbagnets.backend.benchmark.model;

import com.dbagnets.backend.benchmark.execution.BenchmarkRun;

import java.time.Instant;
import java.util.List;

public record InsertRunResponse(
        String id,
        String benchmarkId,
        String entityName,
        Long recordCount,
        String mode,
        Integer batchSize,
        Integer workerCount,
        String cascadeJson,
        String status,
        Instant createdAt,
        Instant finishedAt,
        List<InsertResultResponse> results
) {
    public static InsertRunResponse from(BenchmarkRun run) {
        List<InsertResultResponse> resultDtos = run.getResults().stream()
                .map(InsertResultResponse::from)
                .toList();
        return new InsertRunResponse(
                run.getId(),
                run.getBenchmarkId(),
                run.getEntityName(),
                run.getRecordCount(),
                run.getMode(),
                run.getBatchSize(),
                run.getWorkerCount(),
                run.getCascadeJson(),
                run.getStatus().name(),
                run.getCreatedAt(),
                run.getFinishedAt(),
                resultDtos);
    }
}
