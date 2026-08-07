package com.dbagnets.backend.infrastructure.scriptgen;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmbeddingMapping(
        @JsonProperty("entity_name") String entityName,
        @JsonProperty("is_embedded") boolean isEmbedded,
        @JsonProperty("parent_entity") String parentEntity,
        @JsonProperty("field_name") String fieldName) {}
