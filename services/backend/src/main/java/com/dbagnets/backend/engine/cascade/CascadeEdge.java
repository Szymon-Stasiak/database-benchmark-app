package com.dbagnets.backend.engine.cascade;

public record CascadeEdge(
        String parentEntity,
        String childEntity,
        String fkColumnInChild,
        double parentToChildRatio
) {
    public CascadeEdge {
        if (parentToChildRatio <= 0) {
            throw new IllegalArgumentException("parentToChildRatio must be positive");
        }
    }
}
