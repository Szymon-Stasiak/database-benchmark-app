package com.dbagnets.backend.benchmark.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AttributeConstraints(
        @JsonProperty("is_primary_key") boolean isPrimaryKey,
        @JsonProperty("is_unique") boolean isUnique,
        @JsonProperty("is_nullable") boolean isNullable,
        @JsonProperty("is_indexed") boolean isIndexed,
        @JsonProperty("default_value") String defaultValue
) {
    public static final AttributeConstraints NONE =
            new AttributeConstraints(false, false, true, false, null);

    @JsonCreator
    public AttributeConstraints {
    }
}
