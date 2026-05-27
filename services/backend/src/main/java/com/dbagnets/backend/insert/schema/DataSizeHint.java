package com.dbagnets.backend.insert.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DataSizeHint(
    @JsonProperty("entity_name") String entityName,
    @JsonProperty("expected_row_count") long expectedRowCount
) {}
