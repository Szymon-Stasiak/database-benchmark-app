package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;

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
        Integer conflictsSkipped,
        Double cpuPercentMax,
        Double cpuPercentMean,
        Double cpuPercentP95,
        Long memoryBytesMax,
        Long memoryBytesMean,
        Long memoryBytesP95,
        Integer resourceSampleCount
) {
    private static final long NS_PER_MS = 1_000_000L;

    public static InsertResultResponse from(BenchmarkResult r) {
        Long duration = r.durationMs();
        Double throughput = duration != null && duration > 0 && r.getRowsAffected() != null
                ? r.getRowsAffected() * 1000.0 / duration
                : null;
        Long dbMs = r.getDbTimeNs() == null ? null : r.getDbTimeNs() / NS_PER_MS;
        Long wireMs = r.getWireTimeNs() == null ? null : r.getWireTimeNs() / NS_PER_MS;
        Long overheadMs = r.getOverheadNs() == null ? null : Math.max(0L, r.getOverheadNs()) / NS_PER_MS;
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
                r.getConflictsSkipped(),
                r.getCpuPercentMax(),
                r.getCpuPercentMean(),
                r.getCpuPercentP95(),
                r.getMemoryBytesMax(),
                r.getMemoryBytesMean(),
                r.getMemoryBytesP95(),
                r.getResourceSampleCount());
    }
}