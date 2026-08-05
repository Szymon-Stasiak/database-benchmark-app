package com.dbagnets.backend.engine.cascade;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record CascadePlan(
        List<CascadeNode> nodesInInsertOrder
) {
    public CascadePlan {
        nodesInInsertOrder = nodesInInsertOrder == null ? List.of() : List.copyOf(nodesInInsertOrder);
    }

    public Map<String, CascadeNode> byEntity() {
        return nodesInInsertOrder.stream()
                .collect(Collectors.toUnmodifiableMap(CascadeNode::entityName, n -> n));
    }
}