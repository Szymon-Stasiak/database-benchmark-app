package com.dbagnets.backend.benchmark.model;

import com.dbagnets.backend.benchmark.execution.BenchmarkResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

public record ScenarioResultResponse(
        String id,
        String databaseId,
        String dbName,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorMessage,
        Long meanDbTimeUs,
        Long p50DbTimeUs,
        Long p95DbTimeUs,
        Long p99DbTimeUs,
        Integer samplesRecorded,
        String scenarioType,
        String scenarioResultHash,
        Long scenarioRowsReturned,
        Object scenarioResultPreview,
        Double cpuPercentMax,
        Double cpuPercentMean,
        Double cpuPercentP95,
        Long memoryBytesMax,
        Long memoryBytesMean,
        Long memoryBytesP95,
        Integer resourceSampleCount
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PREVIEW_MAX_LENGTH = 4096;

    public static ScenarioResultResponse from(BenchmarkResult r) {
        Object preview = parsePreview(r.getScenarioResultJson());
        return new ScenarioResultResponse(
                r.getId(),
                r.getDatabaseId(),
                r.getDbName(),
                r.getStatus().name(),
                r.getStartedAt(),
                r.getFinishedAt(),
                r.durationMs(),
                r.getErrorMessage(),
                toMicros(r.getMeanDbTimeNs()),
                toMicros(r.getP50DbTimeNs()),
                toMicros(r.getP95DbTimeNs()),
                toMicros(r.getP99DbTimeNs()),
                r.getSamplesRecorded(),
                r.getScenarioType(),
                r.getScenarioResultHash(),
                r.getScenarioRowsReturned(),
                preview,
                r.getCpuPercentMax(),
                r.getCpuPercentMean(),
                r.getCpuPercentP95(),
                r.getMemoryBytesMax(),
                r.getMemoryBytesMean(),
                r.getMemoryBytesP95(),
                r.getResourceSampleCount());
    }

    private static Object parsePreview(String json) {
        if (json == null || json.isBlank()) return null;
        String truncated = json.length() > PREVIEW_MAX_LENGTH
                ? json.substring(0, PREVIEW_MAX_LENGTH)
                : json;
        try {
            return MAPPER.readTree(truncated);
        } catch (Exception e) {
            return truncated;
        }
    }

    private static Long toMicros(Long ns) {
        return ns == null ? null : ns / 1_000L;
    }
}
