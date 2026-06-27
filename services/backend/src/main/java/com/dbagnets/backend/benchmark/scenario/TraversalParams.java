package com.dbagnets.backend.benchmark.scenario;

public record TraversalParams(
        String startEntity,
        String startLogicalId,
        int depth
) implements ScenarioParams {

    public static final int MIN_DEPTH = 1;
    public static final int MAX_DEPTH = 5;

    public TraversalParams {
        if (startEntity == null || startEntity.isBlank()) {
            throw new IllegalArgumentException("startEntity is required for GRAPH_TRAVERSAL");
        }
        if (startLogicalId == null || startLogicalId.isBlank()) {
            throw new IllegalArgumentException("startLogicalId is required for GRAPH_TRAVERSAL");
        }
        if (depth < MIN_DEPTH || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("depth must be between " + MIN_DEPTH + " and " + MAX_DEPTH);
        }
    }

    @Override
    public ScenarioType type() {
        return ScenarioType.GRAPH_TRAVERSAL;
    }
}
