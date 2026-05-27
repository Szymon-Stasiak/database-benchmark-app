package com.dbagnets.backend.insert.model;

/**
 * SSE payload broadcast on every completed insert batch so the frontend can fill the per-DB
 * progress bar smoothly without polling.
 */
public record BatchProgressEvent(
    String runId,
    String resultId,
    String databaseId,
    String entityName,
    int batchIndex,
    int batchCount,
    int recordsDone,
    int recordsTotal
) {}
