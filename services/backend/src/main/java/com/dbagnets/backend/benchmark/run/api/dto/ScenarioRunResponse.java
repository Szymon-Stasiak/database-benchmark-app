package com.dbagnets.backend.benchmark.run.api.dto;

import java.time.Instant;
import java.util.List;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;

public record ScenarioRunResponse(
        String id,
        String benchmarkId,
        String scenarioType,
        Integer iterations,
        String status,
        String consistencyStatus,
        Instant createdAt,
        Instant finishedAt,
        String configJson,
        List<ScenarioResultResponse> results) {
    public static ScenarioRunResponse from(BenchmarkRun run) {
        List<ScenarioResultResponse> resultDtos =
                run.getResults().stream().map(ScenarioResultResponse::from).toList();
        return new ScenarioRunResponse(
                run.getId(),
                run.getBenchmarkId(),
                run.getScenarioType(),
                run.getIterations(),
                run.getStatus().name(),
                run.getScenarioConsistencyStatus(),
                run.getCreatedAt(),
                run.getFinishedAt(),
                run.getConfigJson(),
                resultDtos);
    }
}
