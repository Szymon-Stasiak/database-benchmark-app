package com.dbagnets.backend.engine.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogicalEntity(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("attributes") List<LogicalAttribute> attributes
) {
    @JsonCreator
    public LogicalEntity {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Entity name is required");
        }
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        description = description == null ? "" : description;
    }

    public Optional<LogicalAttribute> primaryKey() {
        return attributes.stream().filter(LogicalAttribute::isPrimaryKey).findFirst();
    }

    public Optional<LogicalAttribute> findAttribute(String attrName) {
        return attributes.stream().filter(a -> a.name().equalsIgnoreCase(attrName)).findFirst();
    }
}
