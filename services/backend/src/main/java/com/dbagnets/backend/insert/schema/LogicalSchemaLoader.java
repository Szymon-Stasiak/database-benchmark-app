package com.dbagnets.backend.insert.schema;

import com.dbagnets.backend.entity.Benchmark;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LogicalSchemaLoader {

    private final ObjectMapper objectMapper;

    public LogicalSchemaLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<LogicalSchema> load(Benchmark benchmark) {
        String json = benchmark.getLogicalSchema();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, LogicalSchema.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse logical schema for benchmark " + benchmark.getId(), e);
        }
    }
}
