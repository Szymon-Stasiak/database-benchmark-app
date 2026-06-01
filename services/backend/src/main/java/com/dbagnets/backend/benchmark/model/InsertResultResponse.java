package com.dbagnets.backend.benchmark.model;

import com.dbagnets.backend.benchmark.execution.BenchmarkResult;

import java.time.Instant;

public record InsertResultResponse(
        String id,
        String databaseId,
        String dbName,
        String entityName,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Long recordsInserted,
        Double throughputRps,
        String errorMessage,
        Long dbTimeMs,
        Long wireTimeMs,
        Long overheadMs,
        Integer conflictsSkipped
) {
    public static InsertResultResponse from(BenchmarkResult r) {
        Long duration = r.durationMs();
        Double throughput = duration != null && duration > 0 && r.getRowsAffected() != null
                ? r.getRowsAffected() * 1000.0 / duration
                : null;
        Long dbMs = r.getDbTimeNs() == null ? null : r.getDbTimeNs() / 1_000_000L;
        Long wireMs = r.getWireTimeNs() == null ? null : r.getWireTimeNs() / 1_000_000L;
        Long overheadMs = r.getOverheadNs() == null ? null : Math.max(0L, r.getOverheadNs()) / 1_000_000L;
        return new InsertResultResponse(
                r.getId(),
                r.getDatabaseId(),
                r.getDbName(),
                r.getEntityName(),
                r.getStatus().name(),
                r.getStartedAt(),
                r.getFinishedAt(),
                duration,
                r.getRowsAffected(),
                throughput,
                r.getErrorMessage(),
                dbMs,
                wireMs,
                overheadMs,
                r.getConflictsSkipped());
    }
}
