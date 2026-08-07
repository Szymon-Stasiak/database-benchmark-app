package com.dbagnets.backend.engine.scenario;

import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;

public final class ScenarioSupport {

    private ScenarioSupport() {}

    public static boolean isNumericLike(LogicalAttribute attr) {
        return switch (attr.dataType()) {
            case INTEGER, BIGINT, FLOAT, DOUBLE, DECIMAL, DATE, TIMESTAMP -> true;
            default -> false;
        };
    }

    public static LogicalRelationship findRelationship(
            LogicalSchema schema, String parent, String child) {
        return schema.relationships().stream()
                .filter(
                        r ->
                                r.parentEntity().equalsIgnoreCase(parent)
                                        && r.childEntity().equalsIgnoreCase(child))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No relationship found between parent="
                                                + parent
                                                + " and child="
                                                + child));
    }
}
