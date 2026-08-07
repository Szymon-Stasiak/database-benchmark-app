package com.dbagnets.backend.benchmark.run.api.dto;

import java.util.List;

import com.dbagnets.backend.engine.driver.api.InsertMode;
import com.dbagnets.backend.engine.driver.api.ReadDepth;
import com.dbagnets.backend.engine.registry.SelectionStrategy;

public record StartReadRunRequest(
        String entityName,
        Integer sampleSize,
        Boolean includeChildren,
        ReadDepth readDepth,
        SelectionStrategy selectionStrategy,
        InsertMode mode,
        Integer iterations,
        List<String> databaseIds)
        implements EntityRunRequest {
    private static final int DEFAULT_ITERATIONS = 1;
    private static final int MAX_ITERATIONS = 50;

    public SelectionStrategy strategyOrDefault() {
        return selectionStrategy == null ? SelectionStrategy.RANDOM_UNIFORM : selectionStrategy;
    }

    public InsertMode modeOrDefault() {
        return mode == null ? InsertMode.SINGLE : mode;
    }

    public int iterationsOrDefault() {
        if (iterations == null || iterations < 1) return DEFAULT_ITERATIONS;
        return Math.min(iterations, MAX_ITERATIONS);
    }

    public ReadDepth readDepthOrDefault() {
        if (readDepth != null) return readDepth;
        return ReadDepth.fromIncludeChildren(includeChildren);
    }
}
