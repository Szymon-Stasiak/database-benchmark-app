package com.dbagnets.backend.benchmark.model;

import com.dbagnets.backend.benchmark.execution.BenchmarkResult;

import java.time.Instant;

public record DeleteResultResponse(
        String id,
        String databaseId,
        String dbName,
        String entityName,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Long rowsDeleted,
        String errorMessage,
        Long meanDbTimeUs,
        Long p50DbTimeUs,
        Long p95DbTimeUs,
        Long p99DbTimeUs,
        Long wireTimeMs,
        Integer samplesRecorded,
        Long dataSizeBefore,
        Long dataSizeAfter,
        Long dataSizeDelta
) {
    public static DeleteResultResponse from(BenchmarkResult r) {
        Long delta = (r.getDataSizeBefore() != null && r.getDataSizeAfter() != null)
                ? r.getDataSizeAfter() - r.getDataSizeBefore()
                : null;
        return new DeleteResultResponse(
                r.getId(),
                r.getDatabaseId(),
                r.getDbName(),
                r.getEntityName(),
                r.getStatus().name(),
                r.getStartedAt(),
                r.getFinishedAt(),
                r.durationMs(),
                r.getRowsAffected(),
                r.getErrorMessage(),
                toMicros(r.getMeanDbTimeNs()),
                toMicros(r.getP50DbTimeNs()),
                toMicros(r.getP95DbTimeNs()),
                toMicros(r.getP99DbTimeNs()),
                r.getWireTimeNs() == null ? null : r.getWireTimeNs() / 1_000_000L,
                r.getSamplesRecorded(),
                r.getDataSizeBefore(),
                r.getDataSizeAfter(),
                delta);
    }

    private static Long toMicros(Long ns) {
        return ns == null ? null : ns / 1_000L;
    }
}
