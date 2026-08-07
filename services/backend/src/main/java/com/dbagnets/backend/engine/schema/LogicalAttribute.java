package com.dbagnets.backend.engine.schema;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogicalAttribute(
        @JsonProperty("name") String name,
        @JsonProperty("data_type") LogicalDataType dataType,
        @JsonProperty("constraints") AttributeConstraints constraints,
        @JsonProperty("description") String description,
        @JsonProperty("vector_dimensions") Integer vectorDimensions,
        @JsonProperty("enum_values") List<String> enumValues,
        @JsonProperty("precision") Integer precision,
        @JsonProperty("scale") Integer scale) {
    @JsonCreator
    public LogicalAttribute {
        constraints = constraints == null ? AttributeConstraints.NONE : constraints;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        description = description == null ? "" : description;
    }

    public boolean isPrimaryKey() {
        return constraints != null && constraints.isPrimaryKey();
    }

    public boolean isNullable() {
        return constraints != null && constraints.isNullable();
    }
}
