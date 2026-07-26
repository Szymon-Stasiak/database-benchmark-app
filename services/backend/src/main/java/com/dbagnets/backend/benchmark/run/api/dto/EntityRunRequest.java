package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.engine.registry.SelectionStrategy;

import java.util.List;

public interface EntityRunRequest {
    String entityName();
    Integer sampleSize();
    Boolean includeChildren();
    List<String> databaseIds();
    SelectionStrategy strategyOrDefault();
}
