package com.dbagnets.backend.benchmark.model;

import com.dbagnets.backend.benchmark.execution.BenchmarkResult;

import java.time.Instant;

public record ReadResultResponse(
        String id,
        String databaseId,
        String dbName,
        String entityName,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Long recordsRead,
        String errorMessage,
        Long meanDbTimeUs,
        Long p50DbTimeUs,
        Long p95DbTimeUs,
        Long p99DbTimeUs,
        Long wireTimeMs,
        Integer samplesRecorded
) {
    public static ReadResultResponse from(BenchmarkResult r) {
        return new ReadResultResponse(
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
                r.getSamplesRecorded());
    }

    private static Long toMicros(Long ns) {
        return ns == null ? null : ns / 1_000L;
    }
}
