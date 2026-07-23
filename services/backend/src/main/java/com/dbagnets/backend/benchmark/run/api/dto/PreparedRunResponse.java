package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.benchmark.run.internal.RunPreview;

public record PreparedRunResponse(
        String runId,
        String benchmarkId,
        String operationType,
        String entityName,
        String status,
        RunPreview preview
) {
}