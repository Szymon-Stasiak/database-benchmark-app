package com.dbagnets.backend.insert.model;

import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.entity.InsertRun;
import com.dbagnets.backend.insert.entity.InsertStatus;

import java.time.Instant;
import java.util.List;

public record InsertRunResponse(
    String id,
    String benchmarkId,
    String entityName,
    int recordCount,
    InsertMode mode,
    Integer batchSize,
    Integer workerCount,
    String cascadeJson,
    InsertStatus status,
    Instant createdAt,
    Instant finishedAt,
    List<InsertResultResponse> results
) {
    public static InsertRunResponse from(InsertRun run) {
        return new InsertRunResponse(
            run.getId(),
            run.getBenchmark().getId(),
            run.getEntityName(),
            run.getRecordCount(),
            run.getMode(),
            run.getBatchSize(),
            run.getWorkerCount(),
            run.getCascadeJson(),
            run.getStatus(),
            run.getCreatedAt(),
            run.getFinishedAt(),
            run.getResults().stream().map(InsertResultResponse::from).toList()
        );
    }
}
