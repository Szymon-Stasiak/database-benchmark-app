package com.dbagnets.backend.engine.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LogicalSchemaLoader {

    private final ObjectMapper objectMapper;

    public LogicalSchema parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Logical schema JSON is empty — benchmark scripts may not have finished generating yet");
        }
        try {
            return objectMapper.readValue(json, LogicalSchema.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse logical schema JSON", e);
        }
    }
}
