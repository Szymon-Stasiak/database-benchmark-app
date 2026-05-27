package com.dbagnets.backend.insert.cascade;

import java.util.List;

/**
 * Resolved FK-cascade plan: an ordered list of {@link EntityNode}s (parents first) and the edges
 * connecting them. Immutable — safe to share across the parallel per-DB insert tasks.
 */
public record CascadePlan(
    List<EntityNode> orderedEntities,
    List<CascadeEdge> edges
) {
    public CascadePlan {
        orderedEntities = List.copyOf(orderedEntities);
        edges = List.copyOf(edges);
    }

    public EntityNode nodeFor(String entityName) {
        for (EntityNode n : orderedEntities) {
            if (n.name().equalsIgnoreCase(entityName)) return n;
        }
        throw new IllegalArgumentException("Entity " + entityName + " not in cascade plan");
    }

    public boolean contains(String entityName) {
        for (EntityNode n : orderedEntities) {
            if (n.name().equalsIgnoreCase(entityName)) return true;
        }
        return false;
    }
}
