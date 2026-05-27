package com.dbagnets.backend.insert.cascade;

/**
 * User-supplied override of the default cardinality ratio for one edge.
 * Sent from the UI through the {@code /cascade-preview} endpoint or as part of a
 * {@code StartInsertRunRequest}.
 */
public record EdgeRatioOverride(String childEntity, String parentEntity, double ratio) {
    public EdgeRatioOverride {
        if (ratio <= 0) throw new IllegalArgumentException("ratio must be > 0, got " + ratio);
    }
}
