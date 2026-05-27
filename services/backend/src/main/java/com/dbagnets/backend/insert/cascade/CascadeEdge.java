package com.dbagnets.backend.insert.cascade;

/**
 * One edge in the FK cascade graph: {@code child} entity has a foreign-key reference to
 * {@code parent}. {@code ratio} is the number of children per parent (default from
 * {@link Cardinality#defaultRatio()}; user-editable from the UI).
 */
public record CascadeEdge(
    String childEntity,
    String parentEntity,
    Cardinality cardinality,
    double ratio
) {
    public CascadeEdge {
        if (ratio <= 0) throw new IllegalArgumentException("ratio must be > 0, got " + ratio);
    }

    /** Returns a copy with a different ratio (used when the user overrides via the UI). */
    public CascadeEdge withRatio(double newRatio) {
        return new CascadeEdge(childEntity, parentEntity, cardinality, newRatio);
    }
}
