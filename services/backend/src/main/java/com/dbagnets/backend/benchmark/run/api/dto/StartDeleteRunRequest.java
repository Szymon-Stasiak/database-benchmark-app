package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.engine.driver.DeletionMode;
import com.dbagnets.backend.engine.driver.InsertMode;
import com.dbagnets.backend.engine.registry.SelectionStrategy;

import java.util.List;

public record StartDeleteRunRequest(
        String entityName,
        Integer sampleSize,
        Boolean includeChildren,
        DeletionMode deletionMode,
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

    public DeletionMode deletionModeOrDefault() {
        if (deletionMode != null) return deletionMode;
        return Boolean.TRUE.equals(includeChildren) ? DeletionMode.WITH_CHILDREN : DeletionMode.NATIVE;
    }
}
