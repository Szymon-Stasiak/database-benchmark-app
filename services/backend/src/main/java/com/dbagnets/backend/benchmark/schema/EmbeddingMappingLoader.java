package com.dbagnets.backend.benchmark.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class EmbeddingMappingLoader {

    private final ObjectMapper objectMapper;

    public List<EmbeddingMapping> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EmbeddingMapping>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse embedding mappings JSON", e);
        }
    }
}
