package com.dbagnets.backend.insert.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LogicalRelationship(
    String name,
    @JsonProperty("source_entity") String sourceEntity,
    @JsonProperty("target_entity") String targetEntity,
    String cardinality,
    String description
) {}
