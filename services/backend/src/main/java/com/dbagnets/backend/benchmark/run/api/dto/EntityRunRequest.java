package com.dbagnets.backend.benchmark.run.api.dto;

import java.util.List;

import com.dbagnets.backend.engine.registry.SelectionStrategy;

public interface EntityRunRequest {
    String entityName();

    Integer sampleSize();

    Boolean includeChildren();

    List<String> databaseIds();

    SelectionStrategy strategyOrDefault();
}
