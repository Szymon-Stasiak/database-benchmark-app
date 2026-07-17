package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.engine.driver.InsertMode;

import java.util.List;

public record StartInsertRunRequest(
        List<EntityCascadeChoiceDto> entities,
        InsertMode mode,
        Integer batchSize,
        Integer workerCount,
        List<String> databaseIds
) {
    public StartInsertRunRequest {
        entities = entities == null ? List.of() : List.copyOf(entities);
        databaseIds = databaseIds == null ? List.of() : List.copyOf(databaseIds);
    }
}
