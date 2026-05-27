package com.dbagnets.backend.insert.cascade;

import com.dbagnets.backend.insert.schema.LogicalEntity;

import java.util.List;

/**
 * One node in a {@link CascadePlan}: a logical entity along with the resolved record count
 * and the list of parent entity names whose PKs it needs to satisfy its FKs.
 */
public record EntityNode(
    String name,
    LogicalEntity entity,
    int recordCount,
    List<String> parents
) {
    public EntityNode {
        if (recordCount < 0) throw new IllegalArgumentException("recordCount must be >= 0");
        parents = parents == null ? List.of() : List.copyOf(parents);
    }

    public EntityNode withRecordCount(int newCount) {
        return new EntityNode(name, entity, newCount, parents);
    }

    public boolean isLeaf() {
        return parents.isEmpty();
    }
}
