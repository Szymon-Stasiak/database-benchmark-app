package com.dbagnets.backend.engine.scenario;

public record ScenarioResult(
        String resultJson,
        String canonicalHash,
        long rowsReturned
) {
    public static ScenarioResult empty() {
        return new ScenarioResult("null", ResultCanonicalizer.hash("null"), 0L);
    }
}