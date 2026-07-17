package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.engine.scenario.ScenarioParams;

import java.util.List;

public record StartScenarioRunRequest(
        ScenarioParams params,
        Integer iterations,
        List<String> databaseIds
) {
    private static final int DEFAULT_ITERATIONS = 10;
    private static final int MAX_ITERATIONS = 50;

    public int iterationsOrDefault() {
        if (iterations == null || iterations < 1) return DEFAULT_ITERATIONS;
        return Math.min(iterations, MAX_ITERATIONS);
    }
}
