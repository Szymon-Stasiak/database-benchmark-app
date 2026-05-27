package com.dbagnets.backend.insert.datagen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable row of generated values, keyed by attribute name.
 * The map preserves insertion order so column lists in INSERTs are deterministic.
 */
public record GeneratedRecord(Map<String, Object> values) {

    public GeneratedRecord {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Object get(String attribute) {
        return values.get(attribute);
    }

    public int size() {
        return values.size();
    }
}
