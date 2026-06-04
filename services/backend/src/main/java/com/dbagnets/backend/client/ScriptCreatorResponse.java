package com.dbagnets.backend.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record ScriptCreatorResponse(
    boolean success,
    @JsonProperty("logical_schema") Object logicalSchema,
    List<ScriptResult> scripts
) {
    public record ScriptResult(
        @JsonProperty("db_type") String dbType,
        @JsonProperty("db_name") String dbName,
        @JsonProperty("db_version") String dbVersion,
        ContainerInfo container,
        String script,
        boolean success,
        @JsonProperty("iterations_used") int iterationsUsed,
        @JsonProperty("embedding_mappings") List<EmbeddingMapping> embeddingMappings
    ) {
        public List<EmbeddingMapping> embeddingMappings() {
            return embeddingMappings == null ? List.of() : embeddingMappings;
        }
    }

    public record ContainerInfo(
        @JsonProperty("docker_image") String dockerImage,
        @JsonProperty("default_port") int defaultPort,
        Map<String, String> environment
    ) {}
}