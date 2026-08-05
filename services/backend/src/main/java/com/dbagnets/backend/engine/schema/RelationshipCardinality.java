package com.dbagnets.backend.engine.schema;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;

public enum RelationshipCardinality {
    ONE_TO_ONE("1:1"),
    ONE_TO_MANY("1:N"),
    MANY_TO_MANY("M:N");

    private final String wireFormat;

    RelationshipCardinality(String wireFormat) {
        this.wireFormat = wireFormat;
    }

    public String wireFormat() {
        return wireFormat;
    }

    public double defaultRatio() {
        return switch (this) {
            case ONE_TO_ONE -> 1.0;
            case ONE_TO_MANY -> 5.0;
            case MANY_TO_MANY -> 3.0;
        };
    }

    @JsonCreator
    public static RelationshipCardinality from(String value) {
        return Arrays.stream(values())
                .filter(c -> c.wireFormat.equalsIgnoreCase(value) || c.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown cardinality: " + value));
    }
}