package com.dbagnets.backend.engine.driver.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dbagnets.backend.engine.datagen.GeneratedRow;

public final class DriverValues {

    public static final int WEBCLIENT_MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    private DriverValues() {}

    public static Map<String, Object> rowToMap(GeneratedRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            map.put(entry.getKey(), serialize(entry.getValue()));
        }
        return map;
    }

    public static Object serialize(Object value) {
        if (value instanceof float[] arr) {
            List<Double> list = new ArrayList<>(arr.length);
            for (float f : arr) list.add((double) f);
            return list;
        }
        if (value instanceof BigDecimal bd) return bd.doubleValue();
        if (value instanceof Instant ins) return ins.toString();
        if (value instanceof LocalDate ld) return ld.toString();
        return value;
    }
}
