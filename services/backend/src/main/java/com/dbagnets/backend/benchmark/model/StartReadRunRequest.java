package com.dbagnets.backend.benchmark.model;

import com.dbagnets.backend.benchmark.driver.InsertMode;
import com.dbagnets.backend.benchmark.registry.SelectionStrategy;

import java.util.List;

public record StartReadRunRequest(
        String entityName,
        Integer sampleSize,
        Boolean includeChildren,
        SelectionStrategy selectionStrategy,
        InsertMode mode,
        List<String> databaseIds
) {
    public SelectionStrategy strategyOrDefault() {
        return selectionStrategy == null ? SelectionStrategy.RANDOM_UNIFORM : selectionStrategy;
    }

    public InsertMode modeOrDefault() {
        return mode == null ? InsertMode.SINGLE : mode;
    }
}
