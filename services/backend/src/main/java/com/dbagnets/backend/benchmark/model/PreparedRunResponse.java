package com.dbagnets.backend.benchmark.model;

import com.dbagnets.backend.benchmark.preview.RunPreview;

public record PreparedRunResponse(
        String runId,
        String benchmarkId,
        String operationType,
        String entityName,
        String status,
        RunPreview preview
) {
}
