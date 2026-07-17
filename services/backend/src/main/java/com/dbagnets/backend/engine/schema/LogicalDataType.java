package com.dbagnets.backend.engine.schema;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;

public enum LogicalDataType {
    STRING,
    TEXT,
    INTEGER,
    BIGINT,
    FLOAT,
    DOUBLE,
    DECIMAL,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    UUID,
    JSON,
    VECTOR,
    ENUM;

    @JsonCreator
    public static LogicalDataType from(String value) {
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown logical data type: " + value));
    }
}
