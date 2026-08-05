package com.dbagnets.backend.engine.timing;

public record RecordedId(
        String entityName,
        String logicalId,
        String physicalId
) {
}