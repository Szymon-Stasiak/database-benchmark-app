package com.dbagnets.backend.benchmark.run.api.dto;

import java.util.List;

public record PreparedScenarioRunResponse(
        String runId,
        String benchmarkId,
        String scenarioType,
        String status,
        List<Applicability> applicability
) {
    public record Applicability(String databaseId, String dbName, boolean applicable, String reason) {
    }
}
