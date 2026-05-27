package com.dbagnets.backend.insert.schema;

import java.util.List;

public record LogicalEntity(
    String name,
    String description,
    List<LogicalAttribute> attributes
) {
    public List<LogicalAttribute> attributesOrEmpty() {
        return attributes != null ? attributes : List.of();
    }
}
