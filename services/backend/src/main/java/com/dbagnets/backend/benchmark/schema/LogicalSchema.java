package com.dbagnets.backend.benchmark.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogicalSchema(
        @JsonProperty("idea") String idea,
        @JsonProperty("depth") int depth,
        @JsonProperty("depth_chain") List<String> depthChain,
        @JsonProperty("entities") List<LogicalEntity> entities,
        @JsonProperty("relationships") List<LogicalRelationship> relationships,
        @JsonProperty("data_size_hints") List<DataSizeHint> dataSizeHints
) {
    @JsonCreator
    public LogicalSchema {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        depthChain = depthChain == null ? List.of() : List.copyOf(depthChain);
        dataSizeHints = dataSizeHints == null ? List.of() : List.copyOf(dataSizeHints);
        idea = idea == null ? "" : idea;
    }

    public Optional<LogicalEntity> findEntity(String name) {
        return entities.stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst();
    }

    public LogicalEntity requireEntity(String name) {
        return findEntity(name).orElseThrow(
                () -> new IllegalArgumentException("Entity not found in schema: " + name));
    }

    public List<LogicalRelationship> relationshipsTargeting(String childEntity) {
        return relationships.stream()
                .filter(r -> r.childEntity().equalsIgnoreCase(childEntity))
                .filter(r -> !r.isManyToMany())
                .toList();
    }

    public Map<String, Long> dataSizeHintsByEntity() {
        return dataSizeHints.stream()
                .collect(Collectors.toMap(DataSizeHint::entityName, DataSizeHint::expectedRowCount));
    }
}
