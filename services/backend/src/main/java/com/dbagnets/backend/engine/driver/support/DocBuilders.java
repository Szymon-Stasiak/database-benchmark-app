package com.dbagnets.backend.engine.driver.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.schema.LogicalEntity;

public final class DocBuilders {

    private DocBuilders() {}

    public static Map<String, Object> withPk(
            String pkField, LogicalEntity entity, GeneratedRow row) {
        return withPk(pkField, entity, row, Function.identity());
    }

    public static Map<String, Object> withPkAsString(
            String pkField, LogicalEntity entity, GeneratedRow row) {
        return withPk(pkField, entity, row, v -> v == null ? null : String.valueOf(v));
    }

    public static Map<String, Object> withPk(
            String pkField,
            LogicalEntity entity,
            GeneratedRow row,
            Function<Object, Object> pkCoercer) {
        Map<String, Object> doc = new LinkedHashMap<>();
        entity.primaryKey().ifPresent(pk -> doc.put(pkField, pkCoercer.apply(row.get(pk.name()))));
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            doc.put(entry.getKey(), DriverValues.serialize(entry.getValue()));
        }
        return doc;
    }
}
