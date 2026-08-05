package com.dbagnets.backend.engine.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record DataSizeHint(
        @JsonProperty("entity_name") String entityName,
        @JsonProperty("expected_row_count") long expectedRowCount
) {
    @JsonCreator
    public DataSizeHint {
    }
}