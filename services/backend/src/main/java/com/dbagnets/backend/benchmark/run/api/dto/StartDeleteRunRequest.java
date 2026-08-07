package com.dbagnets.backend.benchmark.run.api.dto;

import java.util.List;

import com.dbagnets.backend.engine.driver.api.DeletionMode;
import com.dbagnets.backend.engine.driver.api.InsertMode;
import com.dbagnets.backend.engine.registry.SelectionStrategy;

public record StartDeleteRunRequest(
        String entityName,
        Integer sampleSize,
        Boolean includeChildren,
        DeletionMode deletionMode,
        SelectionStrategy selectionStrategy,
        InsertMode mode,
        List<String> databaseIds)
        implements EntityRunRequest {
    public SelectionStrategy strategyOrDefault() {
        return selectionStrategy == null ? SelectionStrategy.RANDOM_UNIFORM : selectionStrategy;
    }

    public InsertMode modeOrDefault() {
        return mode == null ? InsertMode.SINGLE : mode;
    }

    public DeletionMode deletionModeOrDefault() {
        if (deletionMode != null) return deletionMode;
        return Boolean.TRUE.equals(includeChildren)
                ? DeletionMode.WITH_CHILDREN
                : DeletionMode.NATIVE;
    }
}
