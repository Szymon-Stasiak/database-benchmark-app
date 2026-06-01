package com.dbagnets.backend.benchmark.model;

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
