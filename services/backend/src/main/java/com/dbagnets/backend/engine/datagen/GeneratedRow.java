package com.dbagnets.backend.engine.datagen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GeneratedRow {

    private final String entityName;
    private final String logicalId;
    private final LinkedHashMap<String, Object> values;

    public GeneratedRow(String entityName, String logicalId, LinkedHashMap<String, Object> values) {
        this.entityName = entityName;
        this.logicalId = logicalId;
        this.values = new LinkedHashMap<>(values);
    }

    public String entityName() {
        return entityName;
    }

    public String logicalId() {
        return logicalId;
    }

    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    public Object get(String column) {
        return values.get(column);
    }
}
