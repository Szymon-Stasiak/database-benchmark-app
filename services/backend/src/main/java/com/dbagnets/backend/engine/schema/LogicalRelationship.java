package com.dbagnets.backend.engine.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogicalRelationship(
        @JsonProperty("name") String name,
        @JsonProperty("source_entity") String sourceEntity,
        @JsonProperty("target_entity") String targetEntity,
        @JsonProperty("cardinality") RelationshipCardinality cardinality,
        @JsonProperty("description") String description,
        @JsonProperty("attributes") List<LogicalAttribute> attributes,
        @JsonProperty("fk_column_in_child") String fkColumnInChild
) {
    @JsonCreator
    public LogicalRelationship {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        description = description == null ? "" : description;
    }

    public boolean isManyToMany() {
        return cardinality == RelationshipCardinality.MANY_TO_MANY;
    }

    public String parentEntity() {
        return sourceEntity;
    }

    public String childEntity() {
        return targetEntity;
    }
}