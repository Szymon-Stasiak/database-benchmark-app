package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

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
        Long cascadeRowsDeleted,
        Map<String, Integer> cascadeBreakdown,
        String errorMessage,
        Long meanDbTimeUs,
        Long p50DbTimeUs,
        Long p95DbTimeUs,
        Long p99DbTimeUs,
        Long wireTimeMs,
        Integer samplesRecorded,
        Long dataSizeBefore,
        Long dataSizeAfter,
        Long dataSizeDelta,
        Double cpuPercentMax,
        Double cpuPercentMean,
        Double cpuPercentP95,
        Long memoryBytesMax,
        Long memoryBytesMean,
        Long memoryBytesP95,
        Integer resourceSampleCount
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> BREAKDOWN_TYPE = new TypeReference<>() {};

    public static DeleteResultResponse from(BenchmarkResult r) {
        Long delta = (r.getDataSizeBefore() != null && r.getDataSizeAfter() != null)
                ? r.getDataSizeAfter() - r.getDataSizeBefore()
                : null;
        Map<String, Integer> breakdown = parseBreakdown(r.getCascadeBreakdownJson());
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
                r.getCascadeRowsAffected(),
                breakdown,
                r.getErrorMessage(),
                toMicros(r.getMeanDbTimeNs()),
                toMicros(r.getP50DbTimeNs()),
                toMicros(r.getP95DbTimeNs()),
                toMicros(r.getP99DbTimeNs()),
                r.getWireTimeNs() == null ? null : r.getWireTimeNs() / 1_000_000L,
                r.getSamplesRecorded(),
                r.getDataSizeBefore(),
                r.getDataSizeAfter(),
                delta,
                r.getCpuPercentMax(),
                r.getCpuPercentMean(),
                r.getCpuPercentP95(),
                r.getMemoryBytesMax(),
                r.getMemoryBytesMean(),
                r.getMemoryBytesP95(),
                r.getResourceSampleCount());
    }

    private static Map<String, Integer> parseBreakdown(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, BREAKDOWN_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Long toMicros(Long ns) {
        return ns == null ? null : ns / 1_000L;
    }
}
