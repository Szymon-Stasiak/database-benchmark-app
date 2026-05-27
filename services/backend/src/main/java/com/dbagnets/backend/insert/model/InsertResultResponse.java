package com.dbagnets.backend.insert.model;

import com.dbagnets.backend.insert.entity.InsertResult;
import com.dbagnets.backend.insert.entity.InsertStatus;

import java.time.Instant;

public record InsertResultResponse(
    String id,
    String databaseId,
    String dbName,
    String entityName,
    InsertStatus status,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    Integer recordsInserted,
    Double throughputRps,
    String errorMessage
) {
    public static InsertResultResponse from(InsertResult r) {
        return new InsertResultResponse(
            r.getId(), r.getDatabaseId(), r.getDbName(), r.getEntityName(),
            r.getStatus(), r.getStartedAt(), r.getFinishedAt(),
            r.getDurationMs(), r.getRecordsInserted(), r.getThroughputRps(),
            r.getErrorMessage()
        );
    }
}
