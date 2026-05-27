package com.dbagnets.backend.insert.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LogicalAttribute(
    String name,
    @JsonProperty("data_type") String dataType,
    AttributeConstraints constraints,
    String description
) {
    public AttributeConstraints constraintsOrDefault() {
        return constraints != null ? constraints : AttributeConstraints.defaults();
    }
}
