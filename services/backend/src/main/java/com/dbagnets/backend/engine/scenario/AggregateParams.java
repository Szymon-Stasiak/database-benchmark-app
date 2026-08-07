package com.dbagnets.backend.engine.scenario;

public record AggregateParams(String childEntity, String parentEntity) implements ScenarioParams {

    public AggregateParams {
        if (childEntity == null || childEntity.isBlank()) {
            throw new IllegalArgumentException("childEntity is required for AGGREGATE_GROUP_COUNT");
        }
        if (parentEntity == null || parentEntity.isBlank()) {
            throw new IllegalArgumentException(
                    "parentEntity is required for AGGREGATE_GROUP_COUNT");
        }
    }

    @Override
    public ScenarioType type() {
        return ScenarioType.AGGREGATE_GROUP_COUNT;
    }
}
