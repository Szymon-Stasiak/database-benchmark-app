package com.dbagnets.backend.benchmark.timing;

public record RecordedId(
        String entityName,
        String logicalId,
        String physicalId
) {
}
