package com.dbagnets.backend.insert.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AttributeConstraints(
    @JsonProperty("is_primary_key") boolean isPrimaryKey,
    @JsonProperty("is_unique") boolean isUnique,
    @JsonProperty("is_nullable") boolean isNullable,
    @JsonProperty("is_indexed") boolean isIndexed
) {
    public static AttributeConstraints defaults() {
        return new AttributeConstraints(false, false, true, false);
    }
}
