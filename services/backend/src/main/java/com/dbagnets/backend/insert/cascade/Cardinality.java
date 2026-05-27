package com.dbagnets.backend.insert.cascade;

/**
 * Normalised cardinality between two entities.
 *
 * <p>Source-of-truth strings come from an LLM, so {@link CardinalityParser} normalises every
 * variant ("1:N", "one-to-many", "1-N", etc.) into one of these three values.
 */
public enum Cardinality {
    ONE_TO_ONE(1.0),
    ONE_TO_MANY(5.0),
    MANY_TO_MANY(5.0);

    private final double defaultRatio;

    Cardinality(double defaultRatio) {
        this.defaultRatio = defaultRatio;
    }

    /** Default number of children per parent for this relationship. Editable by the user. */
    public double defaultRatio() {
        return defaultRatio;
    }
}
