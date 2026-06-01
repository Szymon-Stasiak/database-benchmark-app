package com.dbagnets.backend.benchmark.model;

import java.util.List;

public record StartReadRunRequest(
        String entityName,
        Integer sampleSize,
        Boolean includeChildren,
        List<String> databaseIds
) {
}
