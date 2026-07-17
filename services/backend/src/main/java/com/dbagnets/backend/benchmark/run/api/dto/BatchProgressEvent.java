package com.dbagnets.backend.benchmark.run.api.dto;

public record BatchProgressEvent(
        String runId,
        String resultId,
        String databaseId,
        String entityName,
        int batchIndex,
        int batchCount,
        long recordsDone,
        long recordsTotal
) {
}
