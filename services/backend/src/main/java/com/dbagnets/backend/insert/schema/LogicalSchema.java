package com.dbagnets.backend.insert.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogicalSchema(
    String idea,
    int depth,
    @JsonProperty("depth_chain") List<String> depthChain,
    List<LogicalEntity> entities,
    List<LogicalRelationship> relationships,
    @JsonProperty("data_size_hints") List<DataSizeHint> dataSizeHints
) {
    public List<LogicalEntity> entitiesOrEmpty() {
        return entities != null ? entities : List.of();
    }

    public Optional<LogicalEntity> findEntity(String entityName) {
        if (entityName == null) return Optional.empty();
        return entitiesOrEmpty().stream()
            .filter(e -> entityName.equalsIgnoreCase(e.name()))
            .findFirst();
    }
}
