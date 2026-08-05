package com.dbagnets.backend.engine.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingMapping(
        @JsonProperty("entity_name") String entityName,
        @JsonProperty("is_embedded") boolean isEmbedded,
        @JsonProperty("parent_entity") String parentEntity,
        @JsonProperty("field_name") String fieldName
) {
    @JsonCreator
    public EmbeddingMapping {
    }
}