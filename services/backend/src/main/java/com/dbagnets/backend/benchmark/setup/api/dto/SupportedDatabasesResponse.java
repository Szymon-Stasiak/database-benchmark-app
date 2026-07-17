package com.dbagnets.backend.benchmark.setup.api.dto;

import java.util.List;
import java.util.Map;

public record SupportedDatabasesResponse(
    Map<String, List<DatabaseOption>> types
) {
    public record DatabaseOption(
        String name,
        String displayName,
        List<String> versions
    ) {}
}