package com.dbagnets.backend.engine.scenario;

public record RangeParams(
        String entityName,
        String attribute,
        double min,
        double max
) implements ScenarioParams {

    public RangeParams {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName is required for RANGE_FILTER");
        }
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("attribute is required for RANGE_FILTER");
        }
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
        }
    }

    @Override
    public ScenarioType type() {
        return ScenarioType.RANGE_FILTER;
    }
}
