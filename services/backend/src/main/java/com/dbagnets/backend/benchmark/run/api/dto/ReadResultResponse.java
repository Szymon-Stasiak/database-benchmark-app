package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;

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
        Integer samplesRecorded,
        Double cpuPercentMax,
        Double cpuPercentMean,
        Double cpuPercentP95,
        Long memoryBytesMax,
        Long memoryBytesMean,
        Long memoryBytesP95,
        Integer resourceSampleCount
) {
    private static final long NS_PER_US = 1_000L;
    private static final long NS_PER_MS = 1_000_000L;

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
                r.getWireTimeNs() == null ? null : r.getWireTimeNs() / NS_PER_MS,
                r.getSamplesRecorded(),
                r.getCpuPercentMax(),
                r.getCpuPercentMean(),
                r.getCpuPercentP95(),
                r.getMemoryBytesMax(),
                r.getMemoryBytesMean(),
                r.getMemoryBytesP95(),
                r.getResourceSampleCount());
    }

    private static Long toMicros(Long ns) {
        return ns == null ? null : ns / NS_PER_US;
    }
}