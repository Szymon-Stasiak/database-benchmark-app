package com.dbagnets.backend.engine.cascade;

import java.util.List;

public record CascadeNode(
        String entityName, long recordCount, List<CascadeEdge> incomingFromParents) {
    public CascadeNode {
        if (recordCount < 0) {
            throw new IllegalArgumentException("recordCount cannot be negative");
        }
        incomingFromParents =
                incomingFromParents == null ? List.of() : List.copyOf(incomingFromParents);
    }
}
