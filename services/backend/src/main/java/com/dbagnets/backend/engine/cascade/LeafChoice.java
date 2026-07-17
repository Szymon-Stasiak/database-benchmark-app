package com.dbagnets.backend.engine.cascade;

public record LeafChoice(String entityName, long recordCount) {
    public LeafChoice {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName is required");
        }
        if (recordCount <= 0) {
            throw new IllegalArgumentException("recordCount must be positive");
        }
    }
}
